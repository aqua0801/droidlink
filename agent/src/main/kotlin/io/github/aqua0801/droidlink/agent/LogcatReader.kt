package io.github.aqua0801.droidlink.agent

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs the platform `logcat` tool and hands each line to [onLine]. The agent runs as the
 * shell user, which is permitted to read the full device log, so no extra grant is needed.
 */
internal class LogcatReader(private val onLine: (String) -> Unit) {
    @Volatile
    private var process: Process? = null

    @Synchronized
    fun start(extraArgs: List<String>) {
        stop()
        val command = mutableListOf("logcat", "-v", "threadtime").apply { addAll(extraArgs) }
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        process = proc
        Thread {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach(onLine)
                }
            }
        }.apply {
            isDaemon = true
            name = "logcat-reader"
            start()
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }
}
