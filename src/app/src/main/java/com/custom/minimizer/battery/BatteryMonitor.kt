package com.custom.minimizer.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.custom.minimizer.net.DiscordClient
import com.custom.minimizer.net.PulsarClient
import org.json.JSONObject

/**
 * Watches battery level via the ACTION_BATTERY_CHANGED sticky broadcast and
 * reports to pulsar when the level drops to/below a configurable threshold
 * (default 20%) while unplugged. Owned by MinimizerOverlayService so it lives
 * exactly as long as the always-on foreground service.
 */
class BatteryMonitor(private val context: Context) {
    companion object {
        const val PREFS_NAME = "capy_prefs"
        const val KEY_THRESHOLD = "battery_threshold"
        const val DEFAULT_THRESHOLD = 20
        private const val TAG = "BatteryMonitor"
    }

    // Latched so a single low episode produces one alert, not one per % tick.
    // Re-armed once charging or back above threshold.
    @Volatile private var lowAlertSent = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = handle(intent)
    }

    fun start() {
        // registerReceiver returns the current sticky intent, so we know the
        // battery state right away and can send a baseline "started" report.
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = sticky?.let { levelPct(it) } ?: -1
        val charging = sticky?.let { isCharging(it) } ?: false
        Log.i(TAG, "started: battery=$pct% charging=$charging threshold=${threshold()}%")
        PulsarClient.postReport(
            context, "battery", "homebody started — battery $pct%",
            baseJson(pct, charging).put("event", "monitor_started")
        )
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun handle(intent: Intent) {
        val pct = levelPct(intent)
        if (pct < 0) return
        val charging = isCharging(intent)
        val threshold = threshold()

        if (charging || pct > threshold) {
            lowAlertSent = false // recovered — re-arm for the next dip
            return
        }
        if (!lowAlertSent) {
            lowAlertSent = true
            Log.i(TAG, "battery low: $pct% <= $threshold% — alerting")
            PulsarClient.postReport(
                context, "battery", "Battery low: $pct%",
                baseJson(pct, charging).put("event", "low_battery").put("threshold", threshold)
            )
            // Alert via the Discord bot (discord_bot_api → DM).
            DiscordClient.sendMessage(
                "🔋 homebody (${Build.MODEL}) battery low: $pct% (threshold $threshold%), unplugged."
            )
        }
    }

    private fun threshold(): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    private fun levelPct(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    private fun isCharging(intent: Intent): Boolean =
        when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }

    private fun baseJson(pct: Int, charging: Boolean): JSONObject =
        JSONObject()
            .put("level", pct)
            .put("charging", charging)
            .put("device", Build.MODEL)
            .put("ts", System.currentTimeMillis())
}
