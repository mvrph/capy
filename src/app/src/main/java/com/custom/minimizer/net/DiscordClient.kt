package com.custom.minimizer.net

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends DMs via discord_bot_api (Flask bot controller on Render).
 * POST /send_message {"message": ...} → the bot DMs its configured DM_USER_ID.
 * The server's X-DBA-Token auth is currently disabled; set AUTH_TOKEN to match
 * if it's ever enabled.
 */
object DiscordClient {
    const val BASE_URL = "https://api.deepvoid.ai"
    private const val AUTH_TOKEN = "" // X-DBA-Token; empty = server auth disabled
    private const val TAG = "Discord"

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "discord-client").apply { isDaemon = true }
    }

    fun sendMessage(message: String) {
        worker.execute {
            try {
                val conn = (URL("$BASE_URL/send_message").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (AUTH_TOKEN.isNotEmpty()) setRequestProperty("X-DBA-Token", AUTH_TOKEN)
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                conn.outputStream.use { it.write(JSONObject().put("message", message).toString().toByteArray()) }
                val code = conn.responseCode
                Log.i(TAG, "send_message -> HTTP $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed: $e")
            }
        }
    }
}
