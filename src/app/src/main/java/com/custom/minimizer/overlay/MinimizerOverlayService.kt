package com.custom.minimizer.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import com.custom.minimizer.R


class MinimizerOverlayService : LifecycleService() {
    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: PowerManager
    private var overlayView: View? = null
    private var timer: Long = 60 * 1000
    private var wakeLock: PowerManager.WakeLock? = null

    // Thread-safe timer using a dedicated thread
    @Volatile private var lastTouchTime: Long = 0
    @Volatile private var timerRunning = false
    private var timerThread: Thread? = null

    companion object {
        const val PREFS_NAME = "capy_prefs"
        const val KEY_LAST_APP = "last_app_package"
        private const val CHANNEL_ID = "minimizer_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Hold a partial wake lock so our timer thread doesn't get suspended
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "capy:minimizer_timer"
        )
        wakeLock?.acquire()

        startForegroundNotification()
        Toast.makeText(this, "Minimizer is running", Toast.LENGTH_SHORT).show()
    }

    private fun startForegroundNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Minimizer Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Minimizer")
            .setContentText("Monitoring for inactivity")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            timer = intent.getIntExtra("delay", timer.toInt()).toLong()
        }
        Log.i("Minimizer", "onStartCommand: timer=${timer}ms")

        // Remove existing overlay if re-started
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }

        val delayInSecs = timer / 1000
        Toast.makeText(this, "Goes to home screen $delayInSecs seconds after the last touch input.", Toast.LENGTH_SHORT).show()

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        windowManager.addView(overlayView, params)

        // Start the timer thread if not already running
        startTimerThread()

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_OUTSIDE -> {
                    lastTouchTime = System.currentTimeMillis()
                    Log.i("Minimizer", "Touch detected, timer resets (${timer}ms)")
                    saveCurrentForegroundApp()
                    true
                }
                else -> false
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun startTimerThread() {
        if (timerRunning) return
        timerRunning = true
        lastTouchTime = 0

        timerThread = Thread {
            Log.i("Minimizer", "Timer thread started")
            try {
                while (timerRunning) {
                    Thread.sleep(1000) // Check every second
                    val last = lastTouchTime
                    if (last > 0 && System.currentTimeMillis() - last >= timer) {
                        Log.i("Minimizer", "Timer expired, going home")
                        goHome()
                        lastTouchTime = 0 // Reset, wait for next touch
                    }
                }
            } catch (e: InterruptedException) {
                Log.i("Minimizer", "Timer thread interrupted")
            }
            Log.i("Minimizer", "Timer thread stopped")
        }
        timerThread?.isDaemon = false
        timerThread?.priority = Thread.MAX_PRIORITY
        timerThread?.start()
    }

    private fun saveCurrentForegroundApp() {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 5000,
                now
            )
            if (stats != null && stats.isNotEmpty()) {
                val recentApp = stats
                    .filter { it.packageName != packageName && it.packageName != "com.android.launcher3" }
                    .maxByOrNull { it.lastTimeUsed }

                recentApp?.let {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(KEY_LAST_APP, it.packageName).apply()
                    Log.i("Minimizer", "Saved last app: ${it.packageName}")
                }
            }
        } catch (e: Exception) {
            Log.e("Minimizer", "Failed to get foreground app: $e")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerRunning = false
        timerThread?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
    }

    private fun goHome() {
        val goHomeScreen = Intent(Intent.ACTION_MAIN)
        goHomeScreen.addCategory(Intent.CATEGORY_HOME)
        goHomeScreen.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(goHomeScreen)
    }
}
