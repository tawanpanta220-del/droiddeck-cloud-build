package com.droiddeck.launcher.chrome

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.droiddeck.launcher.R

class ChromeDisplayActivity : Activity() {
    private lateinit var display: ChromeDisplayView
    private lateinit var status: TextView
    private var connectionError = false
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            val phase = ChromePreviewRuntime.state(this@ChromeDisplayActivity).optString("phase", "stopped")
            if (!connectionError) status.setText(when (phase) {
                "running" -> R.string.chrome_preview_running
                "failed" -> R.string.chrome_preview_start_failed
                "stopping" -> R.string.chrome_preview_stopping
                "stopped" -> R.string.chrome_preview_stopped
                else -> R.string.chrome_preview_starting
            })
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        display = ChromeDisplayView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(14, 26, 40))
        }
        fun button(resource: Int, action: () -> Unit) {
            toolbar.addView(Button(this).apply {
                setText(resource)
                setOnClickListener { action() }
            })
        }
        button(R.string.chrome_preview_back) { finish() }
        button(R.string.chrome_preview_open_url) {
            display.requestFocus()
            display.shortcut('l'.code)
            getSystemService(InputMethodManager::class.java).showSoftInput(display, InputMethodManager.SHOW_IMPLICIT)
        }
        button(R.string.chrome_preview_keyboard) {
            display.requestFocus()
            @Suppress("DEPRECATION")
            getSystemService(InputMethodManager::class.java).toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
        }
        button(R.string.chrome_preview_escape) { display.tapKey(0xff1b) }
        button(R.string.chrome_preview_retry) { reconnect() }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(12, 8, 8, 8)
        }
        toolbar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar)
        root.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        display.onFailure = {
            connectionError = true
            status.setText(R.string.chrome_preview_display_failed)
        }
    }

    private fun reconnect() {
        val password = ChromePreviewRuntime.password(this)
        if (password == null) {
            connectionError = true
            status.setText(R.string.chrome_preview_display_unavailable)
            return
        }
        connectionError = false
        display.connect(ChromePreviewRuntime.socketPath(this), password)
        display.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        reconnect()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(display.windowToken, 0)
        super.onPause()
    }

    override fun onStop() {
        display.disconnect()
        super.onStop()
    }
}
