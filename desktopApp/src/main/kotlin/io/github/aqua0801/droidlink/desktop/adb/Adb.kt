package io.github.aqua0801.droidlink.desktop.adb

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Locates the `adb` executable and runs short ADB commands. ADB is the transport for the
 * enhanced-mode helper (see [AgentController]); it is used only against devices the user has
 * already authorized for debugging.
 */
object Adb {
    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    val isWindows: Boolean get() = System.getProperty("os.name").lowercase().contains("win")

    /** Resolved once: DROIDLINK_ADB override, then PATH, then the Android SDK's platform-tools. */
    val executable: String? by lazy { locate() }

    private fun locate(): String? {
        val name = if (isWindows) "adb.exe" else "adb"
        System.getenv("DROIDLINK_ADB")?.let { File(it).takeIf(File::isFile)?.let { f -> return f.absolutePath } }
        (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).forEach { dir ->
            File(dir, name).takeIf(File::isFile)?.let { return it.absolutePath }
        }
        sdkDir()?.let { File(it, "platform-tools/$name").takeIf(File::isFile)?.let { f -> return f.absolutePath } }
        return null
    }

    private fun sdkDir(): File? =
        (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let(::File)?.takeIf(File::isDirectory)
            ?: System.getenv("LOCALAPPDATA")?.let { File(it, "Android/Sdk") }?.takeIf(File::isDirectory)

    /** Runs adb with [args], merging stdout and stderr. Blocks up to [timeoutMs]. */
    fun run(vararg args: String, timeoutMs: Long = 15_000): Result {
        val adb = executable ?: return Result(-1, "adb not found")
        return try {
            val process = ProcessBuilder(listOf(adb) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return Result(-1, output.ifBlank { "timed out" })
            }
            Result(process.exitValue(), output.trim())
        } catch (e: Exception) {
            Result(-1, e.message ?: e.toString())
        }
    }

    /** Starts a long-running adb command (e.g. an interactive shell) without waiting for it. */
    fun start(vararg args: String): Process? {
        val adb = executable ?: return null
        return ProcessBuilder(listOf(adb) + args).start()
    }
}
