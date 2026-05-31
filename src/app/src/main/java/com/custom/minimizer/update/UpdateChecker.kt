package com.custom.minimizer.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Semi-automatic OTA updater for sideloaded homebody builds.
 *
 * Polls a JSON manifest hosted next to pulsar on the Olares server; if it
 * advertises a higher versionCode than the installed build, downloads the APK
 * and launches the system installer (one user tap to confirm). Hosting the
 * manifest + APK on the LAN means no Play Store and no public release needed.
 *
 * Manifest shape:
 *   { "versionCode": 2, "versionName": "1.1", "apkUrl": "http://192.168.4.222:8002/homebody/homebody-2.apk", "notes": "..." }
 */
object UpdateChecker {
    const val MANIFEST_URL = "http://192.168.4.222:8002/homebody/latest.json"
    private const val TAG = "OTA"

    /**
     * True when the manifest advertises a build newer than the installed one.
     * Returns false for the sentinel value -1 (manifest field absent) so a
     * missing or malformed manifest never triggers a download.
     */
    internal fun isNewer(manifestVersionCode: Int, installedVersionCode: Int): Boolean =
        manifestVersionCode > installedVersionCode

    fun checkAndPrompt(activity: Activity) {
        Thread {
            try {
                val manifest = fetchManifest() ?: return@Thread
                val latest = manifest.optInt("versionCode", -1)
                val current = currentVersionCode(activity)
                Log.i(TAG, "manifest versionCode=$latest, installed=$current")
                if (!isNewer(latest, current)) return@Thread

                val apkUrl = manifest.optString("apkUrl").ifEmpty { return@Thread }
                val apk = downloadApk(activity, apkUrl, latest) ?: return@Thread
                activity.runOnUiThread { promptInstall(activity, apk) }
            } catch (e: Exception) {
                Log.e(TAG, "update check failed: $e")
            }
        }.start()
    }

    private fun fetchManifest(): JSONObject? {
        val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
        }
        return try {
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.e(TAG, "fetchManifest: $e"); null
        } finally {
            conn.disconnect()
        }
    }

    private fun currentVersionCode(activity: Activity): Int {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        @Suppress("DEPRECATION")
        return pi.longVersionCode.toInt()
    }

    private fun downloadApk(activity: Activity, url: String, versionCode: Int): File? {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "homebody-$versionCode.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 20000
        }
        return try {
            if (conn.responseCode != 200) {
                Log.e(TAG, "apk download HTTP ${conn.responseCode}"); return null
            }
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            Log.i(TAG, "downloaded ${out.length()} bytes -> $out")
            out
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk: $e"); null
        } finally {
            conn.disconnect()
        }
    }

    private fun promptInstall(activity: Activity, apk: File) {
        // Android O+: the app needs the user's "install unknown apps" grant.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        activity.startActivity(intent)
    }
}
