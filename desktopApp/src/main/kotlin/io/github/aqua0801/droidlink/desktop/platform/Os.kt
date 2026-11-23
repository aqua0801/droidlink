package io.github.aqua0801.droidlink.desktop.platform

import java.io.File
import java.util.concurrent.TimeUnit

/** Which OS and desktop session the app runs in, plus helpers for calling desktop tools. */
object Os {
    private val name = System.getProperty("os.name").orEmpty().lowercase()

    val isWindows: Boolean = name.startsWith("windows")
    val isLinux: Boolean = name.startsWith("linux")
    val isMac: Boolean = name.startsWith("mac")

    /**
     * Linux session runs Wayland. The app itself still talks X11 through XWayland, but the
     * compositor withholds global keys and background clipboard access from X11 clients.
     */
    val isWayland: Boolean = isLinux &&
        (System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true) || System.getenv("WAYLAND_DISPLAY") != null)

    /** Finds [command] on PATH. */
    fun which(command: String): File? =
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { File(it, command) }
            .firstOrNull { it.isFile && it.canExecute() }

    /**
     * Runs a desktop tool and returns its exit code and stdout, or null if it can't be started
     * or doesn't finish in [timeoutMs]. Blocking.
     */
    fun run(vararg command: String, timeoutMs: Long = 5_000): Pair<Int, String>? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return null
        }
        process.exitValue() to output.trim()
    }.getOrNull()

    /** Starts a detached tool (a file manager, a viewer) without waiting for it. */
    fun launch(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    }.getOrDefault(false)
}
