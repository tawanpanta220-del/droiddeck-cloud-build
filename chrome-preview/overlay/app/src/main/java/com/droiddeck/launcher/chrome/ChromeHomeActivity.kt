package com.droiddeck.launcher.chrome

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.R

class ChromeHomeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var phase: TextView
    private lateinit var address: EditText
    private var openWhenAvailable = false
    private val poll = object : Runnable {
        override fun run() {
            val current = ChromePreviewRuntime.state(this@ChromeHomeActivity).optString("phase", "stopped")
            phase.setText(phaseText(current))
            if (openWhenAvailable && current !in setOf("failed", "stopped") &&
                ChromePreviewRuntime.password(this@ChromeHomeActivity)?.also { it.fill('\u0000') } != null) {
                openWhenAvailable = false
                showDesktop()
            }
            handler.postDelayed(this, 750)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(14, 26, 40))
        }
        fun label(resource: Int, size: Float) = TextView(this).apply {
            setText(resource)
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(12))
            content.addView(this)
        }
        label(R.string.chrome_preview_title, 26f)
        label(R.string.chrome_preview_notice, 15f)
        phase = label(R.string.chrome_preview_stopped, 17f)
        fun button(resource: Int, action: () -> Unit) {
            content.addView(Button(this).apply {
                setText(resource)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        button(R.string.chrome_preview_launch) { launch() }
        button(R.string.chrome_preview_desktop) { showDesktop() }
        button(R.string.chrome_preview_stop) { ChromePreviewRuntime.stop(this) }
        address = EditText(this).apply {
            setHint(R.string.chrome_preview_url_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        content.addView(address)
        button(R.string.chrome_preview_open_url) {
            runCatching {
                ChromePreviewRuntime.enqueueUrl(this, address.text.toString().trim())
                launch()
            }.onFailure { address.error = getString(R.string.chrome_preview_invalid_url) }
        }
        button(R.string.chrome_preview_settings) {
            ChromePreviewRuntime.enqueueUrl(this, "chrome://settings", internal = true)
            launch()
        }
        button(R.string.chrome_preview_diagnostics) {
            val state = ChromePreviewRuntime.state(this)
            AlertDialog.Builder(this).setTitle(R.string.chrome_preview_diagnostics)
                .setMessage(state.toString(2)).setPositiveButton(android.R.string.ok, null).show()
        }
        button(R.string.chrome_preview_runtime_settings) {
            val current = ChromePreviewRuntime.state(this).optString("phase", "stopped")
            if (current !in setOf("failed", "stopped")) {
                AlertDialog.Builder(this).setMessage(R.string.chrome_preview_stop_before_maintenance)
                    .setPositiveButton(android.R.string.ok, null).show()
            } else startActivity(Intent(this, FrontEndActivity::class.java))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun launch() {
        if (!ChromePreviewRuntime.installed(this)) {
            AlertDialog.Builder(this).setMessage(R.string.chrome_preview_prepared_required)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        runCatching { ChromePreviewRuntime.start(this) }
            .onSuccess { openWhenAvailable = true }
            .onFailure {
                AlertDialog.Builder(this).setMessage(R.string.chrome_preview_start_failed)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
    }

    private fun showDesktop() {
        startActivity(Intent(this, ChromeDisplayActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun phaseText(value: String) = when (value) {
        "checking", "display", "kernel", "services", "chrome_starting" -> R.string.chrome_preview_starting
        "running" -> R.string.chrome_preview_running
        "stopping" -> R.string.chrome_preview_stopping
        "failed" -> R.string.chrome_preview_start_failed
        else -> R.string.chrome_preview_stopped
    }
}
