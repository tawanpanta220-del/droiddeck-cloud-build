package com.droiddeck.launcher.chrome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.RootSpawner
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ChromePreviewService : Service() {
    private var worker: Thread? = null
    private val cancelled = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.chrome_preview_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, ChromeHomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ChromePreviewService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1840, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.chrome_preview_title))
            .setContentText(getString(R.string.chrome_preview_starting))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.chrome_preview_stop), stop).build())
            .build())
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:chrome-preview")
            .apply { acquire(30 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            ChromePreviewRuntime.stop(this)
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY
        cancelled.set(false)
        worker = Thread({
            val state = ChromePreviewRuntime.stateDir(this)
            try {
                File(state, "stop").delete()
                File(state, "vnc.password").delete()
                File(state, "status.json").writeText(JSONObject().put("phase", "checking").toString())
                val tools = ChromePreviewRuntime.stageTools(this)
                RootSpawner.start(this)
                val exit = GuestCommand.run(this,
                    listOf("python3", File(tools, "supervisor.py").path, state.path),
                    cancelled = cancelled) { }
                if (exit != 0) File(state, "status.json").writeText(JSONObject()
                    .put("phase", "failed").put("error", "Preview supervisor exited with code $exit").toString())
            } catch (error: Exception) {
                File(state, "status.json").writeText(JSONObject().put("phase", "failed")
                    .put("error", error.javaClass.simpleName + ": " + error.message.orEmpty().take(256)).toString())
            } finally {
                android.os.Handler(mainLooper).post { stopSelf() }
            }
        }, "chrome-preview-runtime").apply { start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ChromePreviewRuntime.stop(this)
        cancelled.set(true)
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "chrome-preview"
        private const val STOP = "com.droiddeck.launcher.chrome.STOP"
    }
}
