package com.custom.minimizer.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class GoHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("Minimizer", "GoHomeReceiver: alarm fired, going home")
        val goHome = Intent(Intent.ACTION_MAIN)
        goHome.addCategory(Intent.CATEGORY_HOME)
        goHome.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(goHome)
    }
}
