package com.custom.minimizer

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.custom.minimizer.motion.MotionWakeService
import com.custom.minimizer.overlay.MinimizerOverlayService
import com.google.android.material.switchmaterial.SwitchMaterial


class MainActivity : AppCompatActivity() {
    //1 mins is the default delay
    private var delay = 1 * 60 * 1000
    private lateinit var minimizerIntent: Intent
    private var motionWakeIntent: Intent? = null
    private var minimizerServiceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        minimizerIntent = Intent(this, MinimizerOverlayService::class.java)
        minimizerIntent.putExtra("delay", delay)

        setButtonSetUp()
        minimizeButtonSetUp()
        stopButtonSetUp()
        motionWakeSwitchSetUp()
    }

    override fun onResume() {
        super.onResume()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            return
        }

        // Request battery optimization exemption so alarms aren't throttled
        requestBatteryOptimizationExemption()

        // Only start the service once — don't re-trigger onStartCommand on every onResume
        if (!minimizerServiceStarted) {
            Log.i("Minimizer", "Starting MinimizerOverlayService with delay=$delay")
            startForegroundService(minimizerIntent)
            minimizerServiceStarted = true
        }
    }

    private fun setButtonSetUp() {
        val editText = findViewById<EditText>(R.id.delay_in_seconds_input)
        val button = findViewById<Button>(R.id.setButton)

        button.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
                return@setOnClickListener
            }

            try {
                val delayInSeconds = editText.text.toString().toInt()
                if(delayInSeconds >= 15 && delayInSeconds <= 3600){
                    delay = delayInSeconds * 1000
                    Toast.makeText(this, "Delay set: $delayInSeconds", Toast.LENGTH_SHORT).show()
                    restartMinimizerService()
                } else {
                    Toast.makeText(this, "Invalid Input. Allowed range:15 seconds to 3600 seconds", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.i("test", e.toString())
            }
        }
    }

    private fun minimizeButtonSetUp() {
        val button = findViewById<Button>(R.id.minimizeButton)

        button.setOnClickListener {
            moveTaskToBack(true)
        }
    }

    private fun stopButtonSetUp() {
        val button = findViewById<Button>(R.id.stopButton)

        button.setOnClickListener {
            stopService(minimizerIntent)
            minimizerServiceStarted = false
            motionWakeIntent?.let { stopService(it) }
            finish()
        }
    }

    private fun motionWakeSwitchSetUp() {
        val switch = findViewById<SwitchMaterial>(R.id.motionWakeSwitch)

        switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!hasUsageStatsPermission()) {
                    switch.isChecked = false
                    Toast.makeText(this, "Usage Access permission required for app restore", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    return@setOnCheckedChangeListener
                }
                motionWakeIntent = Intent(this, MotionWakeService::class.java)
                startForegroundService(motionWakeIntent)
                Toast.makeText(this, "Motion Wake enabled", Toast.LENGTH_SHORT).show()
            } else {
                motionWakeIntent?.let { stopService(it) }
                motionWakeIntent = null
                Toast.makeText(this, "Motion Wake disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun restartMinimizerService() {
        stopService(minimizerIntent)
        minimizerIntent = Intent(this, MinimizerOverlayService::class.java)
        minimizerIntent.putExtra("delay", delay)
        startForegroundService(minimizerIntent)
        minimizerServiceStarted = true
    }
}
