package com.custom.minimizer

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.custom.minimizer.overlay.MinimizerOverlayService
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {
    //1 mins is the default delay
    private var delay = 1 * 60 * 1000
    private lateinit var intent: Intent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (!Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(overlayIntent)
        }

        intent = Intent(this, MinimizerOverlayService::class.java)
        intent.putExtra("delay", delay)
        startService(intent)
        setButtonSetUp()
        stopButtonSetUp()
    }

    private fun setButtonSetUp() {
        val editText = findViewById<EditText>(R.id.delay_in_seconds_input)
        val button = findViewById<Button>(R.id.setButton)

        button.setOnClickListener {
            try {
                val delayInSeconds = editText.text.toString().toInt()
                if(delayInSeconds >= 15 && delayInSeconds <= 3600){
                    delay = delayInSeconds * 1000
                    Toast.makeText(this, "Delay set: $delayInSeconds", Toast.LENGTH_SHORT).show()
                    restartService()
                } else {
                    Toast.makeText(this, "Invalid Input. Allowed range:15 seconds to 3600 seconds", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.d("test", e.toString())
            }
        }
    }

    private fun stopButtonSetUp() {
        val button = findViewById<Button>(R.id.stopButton)

        button.setOnClickListener {
            stopService(intent)
            finish()
        }
    }

    private fun restartService() {
        stopService(intent)
        intent = Intent(this, MinimizerOverlayService::class.java)
        intent.putExtra("delay", delay)
        startService(intent)
    }
}
