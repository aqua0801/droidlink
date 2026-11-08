package io.github.aqua0801.droidlink.agent

import java.io.File

/**
 * Installs an APK the desktop streamed over. Because the agent runs as the shell user,
 * `pm install` completes without the on-device confirmation dialog (an unattended install,
 * the same mechanism `adb install` uses).
 */
internal object ApkInstaller {
    /** Returns "ok\t<detail>" or "err\t<detail>". The temp file is always removed. */
    fun install(apk: File): String =
        try {
            val proc = ProcessBuilder("pm", "install", "-r", apk.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (proc.exitValue() == 0 && output.contains("Success", ignoreCase = true)) {
                "ok\t$output"
            } else {
                "err\t${output.ifEmpty { "exit code ${proc.exitValue()}" }}"
            }
        } catch (e: Exception) {
            "err\t${e.message ?: e.toString()}"
        } finally {
            apk.delete()
        }
}
