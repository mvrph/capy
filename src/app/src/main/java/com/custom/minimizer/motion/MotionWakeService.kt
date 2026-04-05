package com.custom.minimizer.motion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.LifecycleService
import com.custom.minimizer.overlay.MinimizerOverlayService
import kotlin.math.sqrt

class MotionWakeService : LifecycleService(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var lastMagnitude: Float = 0f
    private var initialized = false

    // How much acceleration change (m/s²) counts as "picked up"
    private var sensitivity: Float = DEFAULT_SENSITIVITY

    companion object {
        const val DEFAULT_SENSITIVITY = 3.0f
        private const val CHANNEL_ID = "motion_wake_channel"
        private const val NOTIFICATION_ID = 2
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sensitivity = intent?.getFloatExtra("sensitivity", DEFAULT_SENSITIVITY) ?: DEFAULT_SENSITIVITY

        startForegroundNotification()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Hold a partial wake lock to keep the CPU alive when screen is off
        // so we continue receiving sensor events
        cpuWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "capy:motionwake_cpu"
        )
        cpuWakeLock?.acquire()

        // Prefer the wake-up accelerometer variant so events fire while screen is off
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i("MotionWake", "Accelerometer registered (wakeUp=${accelerometer.isWakeUpSensor}), sensitivity=$sensitivity")
        } else {
            Log.e("MotionWake", "No accelerometer available on this device")
            cpuWakeLock?.release()
            stopSelf()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (!initialized) {
            lastMagnitude = magnitude
            initialized = true
            return
        }

        val delta = Math.abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude

        // Only wake if screen is off and motion exceeds threshold
        if (delta > sensitivity && !powerManager.isInteractive) {
            Log.i("MotionWake", "Motion detected (delta=$delta), waking and restoring app")
            wakeAndRestoreApp()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun wakeAndRestoreApp() {
        // Wake the screen
        val screenLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "capy:motionwake_screen"
        )
        screenLock.acquire(3000L)

        // Restore the last app
        val prefs = getSharedPreferences(MinimizerOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val lastApp = prefs.getString(MinimizerOverlayService.KEY_LAST_APP, null)

        if (lastApp != null) {
            // Try standard launch intent first
            var launchIntent = packageManager.getLaunchIntentForPackage(lastApp)

            // Fallback: query for any launchable activity in the package
            if (launchIntent == null) {
                val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(lastApp)
                }
                val activities = packageManager.queryIntentActivities(queryIntent, 0)
                if (activities.isNotEmpty()) {
                    val activityInfo = activities[0].activityInfo
                    launchIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setClassName(activityInfo.packageName, activityInfo.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }

            // Last fallback: just bring the task to front via monkey-style intent
            if (launchIntent == null) {
                launchIntent = Intent().apply {
                    setPackage(lastApp)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            }

            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.i("MotionWake", "Restored app: $lastApp")
            } catch (e: Exception) {
                Log.e("MotionWake", "Failed to restore app $lastApp: $e")
            }
        }

        screenLock.release()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Motion Wake Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Motion Wake")
            .setContentText("Listening for motion to wake screen")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        cpuWakeLock?.let {
            if (it.isHeld) it.release()
        }
        Log.i("MotionWake", "Service destroyed, sensor and wake lock released")
    }
}
