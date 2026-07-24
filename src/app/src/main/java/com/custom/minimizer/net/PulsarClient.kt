package com.custom.minimizer.net

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.custom.minimizer.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Minimal client for the pulsar telemetry backend (FastAPI on the Olares server).
 *
 * Flow: register a session once (idempotent server-side), then POST reports.
 * Uses plain HttpURLConnection — no extra dependencies, and cleartext to the
 * pulsar host is whitelisted in network_security_config.xml. All calls run on a
 * single worker thread so concurrent POSTs can't contend on pulsar's SQLite
 * (which otherwise 500s under simultaneous writes).
 */
object PulsarClient {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pulsar-client").apply { isDaemon = true }
    }

    // pulsar listens on 0.0.0.0:8001 on Olares (192.168.4.222). The handheld is
    // on the same /22 LAN, so the LAN address is the fast path.
    const val BASE_URL = "http://192.168.4.222:8001"
    // Injected via BuildConfig from the gitignored keystore.properties (or the
    // PULSAR_TOKEN env var in CI) — never hardcoded. Empty in a build without
    // the secret, in which case the Authorization header is omitted and pulsar
    // rejects the call, which the callers already handle gracefully.
    private val AUTH_TOKEN = BuildConfig.PULSAR_TOKEN
    private const val APP_NAME = "homebody"
    private const val APP_VERSION = "1.0"
    private const val TAG = "Pulsar"

    @Volatile private var sessionReady = false

    private fun sessionId(ctx: Context): String {
        @Suppress("HardwareIds")
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return "homebody-$androidId"
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            if (AUTH_TOKEN.isNotEmpty()) setRequestProperty("Authorization", "Bearer $AUTH_TOKEN")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 8000
            readTimeout = 8000
            if (method == "POST") doOutput = true
        }

    /** Register the session. Idempotent on the server (returns existing on retry). */
    private fun ensureSession(ctx: Context): Boolean {
        if (sessionReady) return true
        return try {
            val conn = open("/v1/sessions", "POST")
            val body = JSONObject()
                .put("id", sessionId(ctx))
                .put("app", APP_NAME)
                .put("app_version", APP_VERSION)
                .put("platform", "android-${Build.VERSION.SDK_INT}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            (code in 200..299).also {
                sessionReady = it
                if (!it) Log.w(TAG, "ensureSession HTTP $code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureSession failed: $e")
            false
        }
    }

    /**
     * Publish a push message to pulsar's broadcast bus (POST /v1/push/publish),
     * which fans out over SSE to live consumers like the stele wall display.
     * Independent of sessions/reports — no session registration needed.
     *
     * [source] must be one of pulsar's allow-list: announcement | incident |
     * account | update | security. [title] is capped at 120 chars server-side,
     * [body] at 600; we trim defensively so a long value can't 4xx the publish.
     */
    fun publishPush(source: String, title: String, body: String? = null, ttl: Int? = null) {
        worker.execute {
            try {
                val conn = open("/v1/push/publish", "POST")
                val payload = JSONObject()
                    .put("source", source)
                    .put("title", title.take(120))
                if (body != null) payload.put("body", body.take(600))
                // ttl (seconds): the kiosk drops the card once it's older than
                // this, so a stale alert can't linger forever even if no recovery
                // event is observed.
                if (ttl != null) payload.put("ttl", ttl)
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = conn.responseCode
                Log.i(TAG, "push '$title' -> HTTP $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "publishPush failed: $e")
            }
        }
    }

    /**
     * POST a report to pulsar on a background thread. [body] is sent as a JSON
     * object (pulsar's ReportCreate.body accepts dict | str).
     */
    fun postReport(ctx: Context, channel: String, title: String, body: JSONObject) {
        val app = ctx.applicationContext
        worker.execute {
            if (!ensureSession(app)) return@execute
            try {
                val conn = open("/v1/reports", "POST")
                val payload = JSONObject()
                    .put("session_id", sessionId(app))
                    .put("channel", channel)
                    .put("title", title)
                    .put("body", body)
                    .put("events", JSONArray())
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = conn.responseCode
                Log.i(TAG, "report '$title' -> HTTP $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "postReport failed: $e")
            }
        }
    }
}
