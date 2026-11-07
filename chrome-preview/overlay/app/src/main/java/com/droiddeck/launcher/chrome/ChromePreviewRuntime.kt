package com.droiddeck.launcher.chrome

import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SessionPhase
import org.json.JSONObject
import java.io.File
import java.util.UUID

object ChromePreviewRuntime {
    fun stateDir(context: Context) = File(context.filesDir, "chrome-preview").apply { mkdirs() }
    fun toolsDir(context: Context) = File(context.filesDir, "chrome-preview-tools").apply { mkdirs() }
    fun socketPath(context: Context) = File(stateDir(context), "rfb").path
    fun installed(context: Context) = LinuxRuntime.hasRootfs(context)

    fun state(context: Context): JSONObject = runCatching {
        val file = File(stateDir(context), "status.json")
        if (!file.isFile || file.length() > 8192) JSONObject().put("phase", "stopped")
        else JSONObject(file.readText())
    }.getOrElse { JSONObject().put("phase", "stopped") }

    fun password(context: Context): CharArray? = runCatching {
        val file = File(stateDir(context), "vnc.password")
        if (file.isFile && file.length() in 1L..8L) file.readText().toCharArray() else null
    }.getOrNull()

    fun checkStartAllowed(context: Context) {
        check(!LinuxRuntimeInstaller.isBusy()) { "Runtime maintenance is in progress" }
        check(!SessionState.running && SessionState.phase in setOf(SessionPhase.IDLE, SessionPhase.FAILED)) { "OTHER_SESSION_ACTIVE" }
        check(state(context).optString("phase") != "stopping") { "PREVIEW_STOPPING" }
    }

    fun start(context: Context) {
        checkStartAllowed(context)
        context.startForegroundService(Intent(context, ChromePreviewService::class.java))
    }

    fun stop(context: Context) {
        File(stateDir(context), "stop").writeText("stop\n")
    }

    fun enqueueUrl(context: Context, address: String, internal: Boolean = false) {
        val target = if (internal && address in setOf("chrome://settings", "chrome://sandbox")) address
                     else ChromeAddress.normalize(address)
        val directory = File(stateDir(context), "urls").apply { mkdirs() }
        check((directory.listFiles()?.size ?: 0) < 20) { "Too many pending browser addresses" }
        val file = File(directory, UUID.randomUUID().toString() + ".json")
        val temporary = File(directory, file.name + ".tmp")
        temporary.writeText(JSONObject().put("url", target).toString())
        check(temporary.renameTo(file)) { "Could not queue browser address" }
    }

    fun stageTools(context: Context): File {
        val directory = toolsDir(context)
        context.assets.list("chrome-preview").orEmpty().forEach { name ->
            require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "..")
            context.assets.open("chrome-preview/$name").use { source ->
                File(directory, name).outputStream().use { source.copyTo(it) }
            }
        }
        return directory
    }
}
