package com.custom.minimizer.motion

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private var keyguardLock: KeyguardManager.KeyguardLock? = null
    private var lastMagnitude: Float = 0f
    private var initialized = false

    // Last time we restored an app, to debounce: a sensor-triggered restore
    // turns the screen on, which would otherwise re-fire the screen-on path.
    @Volatile private var lastRestoreAt: Long = 0L

    // Pickup detection is unreliable on this hardware (tilt is hardware-
    // thresholded, significant-motion is coarse), so we ALSO resume when the
    // screen turns on — which a power-button press does. Guarded to only fire
    // at the launcher, so it never yanks the user out of an app they opened.
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            if (System.currentTimeMillis() - lastRestoreAt < RESTORE_DEBOUNCE_MS) return
            if (foregroundIsLauncher()) {
                Log.i("MotionWake", "Screen on at launcher — restoring last app")
                dismissKeyguard()
                restoreLastApp()
                reenableKeyguard()
            }
        }
    }

    // How much acceleration change (m/s²) counts as "picked up" — accelerometer
    // fallback only; the tilt detector has its own hardware threshold.
    private var sensitivity: Float = DEFAULT_SENSITIVITY

    companion object {
        const val DEFAULT_SENSITIVITY = 3.0f
        const val PREFS_NAME = "capy_prefs"
        const val KEY_ENABLED = "motion_wake_enabled"
        private const val CHANNEL_ID = "motion_wake_channel"
        private const val NOTIFICATION_ID = 2
        private const val RESTORE_DEBOUNCE_MS = 3000L

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

        // Resume-on-wake: catches the power-button press when the tilt sensor
        // doesn't fire. SCREEN_ON is a protected broadcast that can't be
        // declared in the manifest, so register it dynamically here.
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))

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
        // Stamp before the screen lights up so the screen-on receiver's debounce
        // check sees a recent timestamp and doesn't fire a second restore.
        lastRestoreAt = System.currentTimeMillis()
        val screenLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "capy:motionwake_screen"
        )
        screenLock.acquire(3000L)
        dismissKeyguard()
        restoreLastApp()
        reenableKeyguard()
        screenLock.release()
    }

    private fun dismissKeyguard() {
        // Otherwise the restored activity launches behind the lock screen and the
        // user just sees "home".
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
        // background anyway), and any failure is caught + logged.
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            @Suppress("DEPRECATION")
            keyguardLock = keyguardManager.newKeyguardLock("capy:motionwake")
            keyguardLock?.disableKeyguard()
        } catch (e: Exception) {
            Log.e("MotionWake", "Keyguard dismiss failed: $e")
        }
    }

    private fun reenableKeyguard() {
        try {
            keyguardLock?.reenableKeyguard()
        } catch (e: Exception) {
            Log.e("MotionWake", "Keyguard re-enable failed: $e")
        } finally {
            keyguardLock = null
        }
    }

    private fun restoreLastApp() {
        lastRestoreAt = System.currentTimeMillis()
        val prefs = getSharedPreferences(MinimizerOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val lastApp = prefs.getString(MinimizerOverlayService.KEY_LAST_APP, null)

        if (lastApp == null) {
            // Nothing captured yet (no inactivity→home cycle has run) — the
            // screen is already on, so just leave the device awake.
            Log.i("MotionWake", "No saved app — woke device only")
            return
        }

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

    /** The current default launcher's package — the "home" we don't want to
     *  treat as an app to leave the user on. */
    private fun launcherPackage(): String? = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
    } catch (e: Exception) {
        null
    }

    /** True when the foreground (at screen-on) is the launcher. Returns false when
     *  the foreground app is unknown or can't be determined, to avoid interrupting
     *  a user who is actively using another app. */
    private fun foregroundIsLauncher(): Boolean {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60 * 60 * 1000L, now)
            val ev = UsageEvents.Event()
            var fg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    val pkg = ev.packageName
                    if (pkg != null && pkg != packageName) fg = pkg
                }
            }
            fg != null && fg == launcherPackage()
        } catch (e: Exception) {
            false // can't determine foreground; skip restore to avoid interrupting the user
        }
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
        try { unregisterReceiver(screenOnReceiver) } catch (_: Exception) {}
        reenableKeyguard()
        cpuWakeLock?.let {
            if (it.isHeld) it.release()
        }
        Log.i("MotionWake", "Service destroyed, sensor and wake lock released")
    }
}
