@file:JvmName("Agent")

package io.github.aqua0801.droidlink.agent

import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Helper launched on the phone through ADB via app_process, so it runs as the shell user.
 * It listens on a loopback TCP port (the desktop reaches it through `adb forward`) and
 * speaks the small framed protocol in [FrameType]: it streams the system log, installs an
 * APK the desktop sends, and mirrors the clipboard in both directions.
 *
 * It is intentionally self-contained — it shares no code with the app's encrypted P2P
 * channel. The transport here is the ADB connection the user has already authorized. The
 * process exits as soon as the desktop disconnects, so nothing is left running afterwards.
 */
private const val READY_PREFIX = "DROIDLINK-AGENT READY "

fun main() {
    // Bind loopback only; the desktop connects through an adb-forwarded port.
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
        // The launcher reads this line from app_process's stdout to learn the port.
        println(READY_PREFIX + server.localPort)
        System.out.flush()
        val socket = server.accept()
        socket.tcpNoDelay = true
        AgentSession(socket).run()
    }
}

private class AgentSession(private val socket: Socket) {
    private val reader = FrameReader(socket.inputStream)
    private val writer = FrameWriter(socket.outputStream)
    private val logcat = LogcatReader { line -> runCatching { writer.writeText(FrameType.LOG, line) } }
    private val clipboard = ClipboardBridge { text -> runCatching { writer.writeText(FrameType.CLIP, text) } }

    /** For one-off reads (clip.get); independent of the sync bridge, which may be stopped. */
    private val clipboardReader by lazy { ClipboardService.create() }

    private var apkFile: File? = null
    private var apkOut: FileOutputStream? = null

    fun run() {
        try {
            writer.writeText(FrameType.HELLO, deviceInfo())
            while (true) {
                val (type, payload) = reader.read() ?: break
                when (type) {
                    FrameType.CMD -> if (!onCommand(String(payload, Charsets.UTF_8))) break
                    FrameType.APK_DATA -> apkStream().write(payload)
                    FrameType.APK_END -> finishApk()
                    FrameType.CLIP -> clipboard.apply(String(payload, Charsets.UTF_8))
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // socket closed or a malformed frame; fall through to cleanup
        } finally {
            logcat.stop()
            clipboard.stop()
            runCatching { apkOut?.close() }
            apkFile?.delete()
            runCatching { socket.close() }
        }
    }

    /** Returns false to end the session. */
    private fun onCommand(command: String): Boolean {
        val parts = command.split('\t')
        when (parts.firstOrNull()) {
            "logcat.start" -> logcat.start(parts.drop(1).filter { it.isNotEmpty() })
            "logcat.stop" -> logcat.stop()
            "clip.start" -> if (!clipboard.start()) runCatching { writer.writeText(FrameType.EVENT, "clip\tunavailable") }
            "clip.stop" -> clipboard.stop()
            // Reply with the current text (empty if none or unavailable); always answers.
            "clip.get" -> runCatching {
                writer.writeText(FrameType.CLIP_REPLY, runCatching { clipboardReader?.getText() }.getOrNull().orEmpty())
            }
            "shutdown" -> return false
        }
        return true
    }

    private fun apkStream(): FileOutputStream =
        apkOut ?: FileOutputStream(
            File.createTempFile("droidlink-", ".apk", File("/data/local/tmp")).also { apkFile = it },
        ).also { apkOut = it }

    private fun finishApk() {
        val out = apkOut ?: return
        out.flush()
        out.close()
        apkOut = null
        val file = apkFile ?: return
        apkFile = null
        writer.writeText(FrameType.EVENT, "install\t${ApkInstaller.install(file)}")
    }

    /** Reads a few build properties without importing android.*: getprop is on the shell PATH. */
    private fun deviceInfo(): String {
        fun prop(key: String) = runCatching {
            ProcessBuilder("getprop", key).start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")
        return listOf(prop("ro.product.model"), prop("ro.build.version.release"), prop("ro.build.version.sdk"))
            .joinToString("\t")
    }
}
