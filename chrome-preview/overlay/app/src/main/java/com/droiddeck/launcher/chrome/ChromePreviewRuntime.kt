package com.droiddeck.launcher.chrome

import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import org.json.JSONObject
import java.io.File
import java.net.URI
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

    fun start(context: Context) {
        check(!LinuxRuntimeInstaller.isBusy()) { "Runtime maintenance is in progress" }
        context.startForegroundService(Intent(context, ChromePreviewService::class.java))
    }

    fun stop(context: Context) {
        File(stateDir(context), "stop").writeText("stop\n")
    }

    fun enqueueUrl(context: Context, address: String, internal: Boolean = false) {
        if (!(internal && address in setOf("chrome://settings", "chrome://sandbox"))) {
            val uri = URI(address)
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
            require(address.length <= 4096 && address.none { it.code < 32 })
        }
        val directory = File(stateDir(context), "urls").apply { mkdirs() }
        check((directory.listFiles()?.size ?: 0) < 20) { "Too many pending browser addresses" }
        val file = File(directory, UUID.randomUUID().toString() + ".json")
        val temporary = File(directory, file.name + ".tmp")
        temporary.writeText(JSONObject().put("url", address).toString())
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
