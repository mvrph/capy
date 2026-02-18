package com.custom.minimizer.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.LifecycleService
import com.custom.minimizer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MinimizerOverlayService : LifecycleService() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View

    override fun onCreate() {
        super.onCreate()
    }

    // Triggered when another android component sends an Intent to this running service
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var currentJob: Job = Job()
        val scope = CoroutineScope(Dispatchers.Default)
        var timer: Long = 0

        if(intent != null){
            timer = intent.getIntExtra("delay", timer.toInt()).toLong()
            Log.d("test", timer.toString() + " delay")
        } else {
            timer = 2 * 60 * 1000
        }

        val delayInSecs = timer / 1000
        Toast.makeText(this, "Goes to home screen $delayInSecs seconds after the last touch input.", Toast.LENGTH_SHORT).show()

        //lastAppPackageName = ""
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Inflate a layout for your floating view
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

        // Position (top left corner)
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        windowManager.addView(overlayView, params)

        overlayView.setOnTouchListener(object : View.OnTouchListener {

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_OUTSIDE -> {
                        currentJob.cancel()
                        Log.d("test", "successful")
                        //runApp(lastAppPackageName)
                        currentJob = scope.launch {
                            withContext(Dispatchers.Default){
                                delay(timer)
                                goHome()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(overlayView)
    }

    private fun goHome() {
        val goHomeScreen = Intent(Intent.ACTION_MAIN)
        goHomeScreen.addCategory(Intent.CATEGORY_HOME)
        goHomeScreen.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(goHomeScreen)
    }
}
