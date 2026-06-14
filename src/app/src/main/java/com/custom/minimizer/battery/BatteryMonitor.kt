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
        // Hysteresis: warn at/below the threshold, but only treat the battery as
        // "recovered" (clear the alert + re-arm) at/above this — so a small bounce
        // near the warn line doesn't flap. Charging always counts as recovered.
        const val KEY_RECOVERY = "battery_recovery"
        const val DEFAULT_RECOVERY = 80
        // The low-battery kiosk card self-expires after this — a backstop so a
        // stale "low" can't linger forever even if no recovery is observed.
        const val LOW_PUSH_TTL_SECONDS = 4 * 3600
        // The "recovered" confirmation card is transient.
        const val RECOVERED_PUSH_TTL_SECONDS = 30 * 60
        private const val TAG = "BatteryMonitor"

        /**
         * Converts raw battery level/scale extras to a 0–100 percentage.
         * Returns -1 when either value is unavailable (level < 0 or scale <= 0).
         */
        internal fun computeLevelPct(level: Int, scale: Int): Int =
            if (level >= 0 && scale > 0) level * 100 / scale else -1

        /**
         * Returns true when the battery has recovered (charging or above the
         * threshold) and the low-alert latch should be re-armed.
         */
        internal fun shouldRearm(pct: Int, charging: Boolean, threshold: Int): Boolean =
            charging || pct > threshold
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

        // Recovered (charging, or back above the recovery line with hysteresis):
        // clear the low alert on the kiosk and re-arm. Announce once per dip.
        if (shouldRearm(pct, charging, recovery())) {
            if (lowAlertSent) {
                lowAlertSent = false
                Log.i(TAG, "battery recovered: $pct% charging=$charging — clearing alert")
                // A short-lived "recovered" card supersedes the low one, and the
                // low card also ages out via its own TTL. (pulsar has no
                // server-driven dismiss, so this is how the kiosk stops showing
                // "low" once the battery is good again — capy#19.)
                PulsarClient.publishPush(
                    source = "update",
                    title = "🔋 homebody battery recovered: $pct%",
                    body = "${Build.MODEL} back to $pct%${if (charging) ", charging" else ""}.",
                    ttl = RECOVERED_PUSH_TTL_SECONDS
                )
            }
            return
        }
        if (pct <= threshold && !lowAlertSent) {
            lowAlertSent = true
            Log.i(TAG, "battery low: $pct% <= $threshold% — alerting")
            PulsarClient.postReport(
                context, "battery", "Battery low: $pct%",
                baseJson(pct, charging).put("event", "low_battery").put("threshold", threshold)
            )
            // Surface it on the stele wall display via pulsar's push bus
            // (source=incident → renders as a notification card on the kiosk).
            // TTL'd so it self-clears even if a recovery event is missed.
            PulsarClient.publishPush(
                source = "incident",
                title = "🔋 homebody battery low: $pct%",
                body = "${Build.MODEL} at $pct% (threshold $threshold%), unplugged.",
                ttl = LOW_PUSH_TTL_SECONDS
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

    private fun recovery(): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_RECOVERY, DEFAULT_RECOVERY)

    private fun levelPct(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return computeLevelPct(level, scale)
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
