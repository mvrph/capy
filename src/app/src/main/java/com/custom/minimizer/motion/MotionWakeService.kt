package com.custom.minimizer.motion

import android.app.KeyguardManager
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
import kotlin.math.abs
import kotlin.math.sqrt

class MotionWakeService : LifecycleService(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var lastMagnitude: Float = 0f
    private var initialized = false

    // How much acceleration change (m/s²) counts as "picked up" — accelerometer
    // fallback only; the tilt detector has its own hardware threshold.
    private var sensitivity: Float = DEFAULT_SENSITIVITY

    companion object {
        const val DEFAULT_SENSITIVITY = 3.0f
        const val PREFS_NAME = "capy_prefs"
        const val KEY_ENABLED = "motion_wake_enabled"
        private const val CHANNEL_ID = "motion_wake_channel"
        private const val NOTIFICATION_ID = 2

        // Sensor.TYPE_TILT_DETECTOR is @hide in the public SDK, so reference it
        // by its stable platform int (android.sensor.tilt_detector). It's a
        // wake-up sensor that fires on a lift/tilt — i.e. picking the device up.
        // Significant-motion was too coarse (built for "started walking") and
        // never fired on a desk pickup.
        private const val TYPE_TILT_DETECTOR = 22

        /** Euclidean magnitude of a 3-axis accelerometer reading (m/s²). */
        internal fun vectorMagnitude(x: Float, y: Float, z: Float): Float =
            sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        /** True when the change in magnitude exceeds the configured sensitivity threshold. */
        internal fun isMotionExceeded(delta: Float, sensitivity: Float): Boolean =
            delta > sensitivity
    }

    /** The tilt detector, if this device exposes one as a wake-up sensor. */
    private fun tiltSensor(): Sensor? =
        sensorManager.getDefaultSensor(TYPE_TILT_DETECTOR)?.takeIf { it.isWakeUpSensor }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent is null when the system restarts us via START_STICKY after a
        // kill — fall back to the default sensitivity in that case.
        sensitivity = intent?.getFloatExtra("sensitivity", DEFAULT_SENSITIVITY) ?: DEFAULT_SENSITIVITY

        startForegroundNotification()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val tilt = tiltSensor()
        if (tilt != null) {
            // Preferred: the tilt detector is a wake-up sensor, so it wakes the
            // AP itself on a lift — no held wake lock. That survives Doze/suspend
            // and barely touches the battery (the old always-on accelerometer +
            // permanent wake lock is what drained it and got the app reaped).
            sensorManager.registerListener(this, tilt, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i("MotionWake", "Using tilt-detector wake-up sensor")
        } else {
            // Fallback for devices without a tilt detector: poll the
            // accelerometer. A non-wake-up accelerometer needs the CPU awake to
            // keep delivering while the screen is off, so the partial wake lock
            // lives here only (and only when the accelerometer isn't wake-up).
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            if (accelerometer != null) {
                if (!accelerometer.isWakeUpSensor) {
                    cpuWakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "capy:motionwake_cpu"
                    ).also { it.acquire() }
                }
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
                Log.i("MotionWake", "Using accelerometer fallback (wakeUp=${accelerometer.isWakeUpSensor}), sensitivity=$sensitivity")
            } else {
                Log.e("MotionWake", "No accelerometer available on this device")
                stopSelf()
            }
        }

        super.onStartCommand(intent, flags, startId)
        // START_STICKY: if the process is reclaimed, the system restarts us
        // (null intent) and we re-arm the sensor above.
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Tilt detector: each event is a discrete "device was lifted/tilted".
        if (event.sensor.type == TYPE_TILT_DETECTOR) {
            if (!powerManager.isInteractive) {
                Log.i("MotionWake", "Tilt detected, waking")
                wakeAndRestoreApp()
            }
            return
        }

        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val magnitude = vectorMagnitude(event.values[0], event.values[1], event.values[2])

        if (!initialized) {
            lastMagnitude = magnitude
            initialized = true
            return
        }

        val delta = abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude

        // Only wake if screen is off and motion exceeds threshold
        if (isMotionExceeded(delta, sensitivity) && !powerManager.isInteractive) {
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

        // Dismiss the keyguard, otherwise the restored activity launches behind
        // the lock screen and the user just sees "home".
        //
        // We use the deprecated KeyguardLock.disableKeyguard() deliberately:
        // DISABLE_KEYGUARD is a *normal*-protection permission (auto-granted at
        // install — no signature/system app needed, despite the scary name), and
        // this is a Service restoring a *third-party* app, so the modern
        // Activity-only APIs (setShowWhenLocked / requestDismissKeyguard) don't
        // apply — we can't set showWhenLocked on another app's activity.
        // disableKeyguard() only suppresses a *non-secure* keyguard (no
        // PIN/pattern) — exactly this handheld's setup; on a secure lock it's a
        // best-effort no-op (you can't auto-dismiss a secure keyguard from the
        // background anyway), and any failure is caught + logged below.
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            @Suppress("DEPRECATION")
            keyguardManager.newKeyguardLock("capy:motionwake").disableKeyguard()
        } catch (e: Exception) {
            Log.e("MotionWake", "Keyguard dismiss failed: $e")
        }

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
        } else {
            // Nothing captured yet (no inactivity→home cycle has run) — just
            // wake the device, which the screen lock + keyguard dismiss above
            // already did.
            Log.i("MotionWake", "No saved app — woke device only")
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
