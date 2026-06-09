package com.custom.minimizer.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms Motion Wake after a reboot. Without this, the service is gone until
 * the user re-opens the app — which is how Motion Wake "turned itself off" on
 * the handheld (a reboot killed it, the switch state didn't persist). We only
 * restart it if the user had it enabled (see [MotionWakeService.KEY_ENABLED]).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(MotionWakeService.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MotionWakeService.KEY_ENABLED, false)) {
            context.startForegroundService(Intent(context, MotionWakeService::class.java))
            Log.i("MotionWake", "Re-armed Motion Wake on boot")
        }
    }
}
