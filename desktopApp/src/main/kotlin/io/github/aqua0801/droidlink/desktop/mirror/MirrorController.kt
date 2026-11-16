package io.github.aqua0801.droidlink.desktop.mirror

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.aqua0801.droidlink.desktop.adb.Adb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.IOException
import java.net.Socket
import kotlin.random.Random

/**
 * Stage 1 of screen mirroring: view-only, over ADB, using the bundled scrcpy-server.
 * One mirror at a time. Frames are decoded on an IO thread and published as the latest
 * [ImageBitmap]; the window only ever draws the newest one.
 */
class MirrorController(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    private var job: Job? = null
    @Volatile
    private var session: Running? = null

    private class Running(val serial: String, val process: Process, val pcPort: Int, val socket: Socket)

    fun start(serial: String) {
        stop()
        _state.value = MirrorState.Starting(serial)
        job = scope.launch(Dispatchers.IO) {
            try {
                run(serial)
                if (_state.value !is MirrorState.Failed) _state.value = MirrorState.Idle
            } catch (e: Exception) {
                if (isActive) _state.value = MirrorState.Failed(serial, e.message ?: e.javaClass.simpleName)
            } finally {
                cleanup()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        cleanup()
        _frame.value = null
        _state.value = MirrorState.Idle
    }

    private suspend fun run(serial: String) {
        val jar = ScrcpyServer.jarFile() ?: throw IllegalStateException("scrcpy-server is not bundled")
        val push = Adb.run("-s", serial, "push", jar.absolutePath, ScrcpyServer.REMOTE_JAR, timeoutMs = 30_000)
        if (!push.ok) throw IOException("push failed: ${push.output}")

        val options = ScrcpyServer.Options(scid = Random.nextInt(0x7FFF_FFFF))
        val forward = Adb.run("-s", serial, "forward", "tcp:0", "localabstract:${options.socketName}")
        val pcPort = forward.output.lines().firstNotNullOfOrNull { it.trim().toIntOrNull() }
        if (!forward.ok || pcPort == null) throw IOException("forward failed: ${forward.output}")

        val process = Adb.start("-s", serial, "shell", *ScrcpyServer.shellArgs(options).toTypedArray())
            ?: throw IllegalStateException("adb not found")
        drain(process)

        val (socket, stream, header) = connect(pcPort)
        session = Running(serial, process, pcPort, socket)

        FfmpegVideoDecoder(header.codec).use { decoder ->
            var width = 0
            var height = 0
            while (currentCoroutineContext().isActive) {
                when (val item = stream.read() ?: break) {
                    is StreamItem.Session -> {
                        width = item.width
                        height = item.height
                        _state.value = MirrorState.Running(serial, header.deviceName, width, height)
                    }
                    is StreamItem.Packet -> decoder.decode(item)?.let { publish(it) }
                }
            }
        }
    }

    /**
     * With a forward tunnel the TCP connect succeeds as soon as adb listens, even before the
     * server is up; the dummy byte only arrives once it is. So retry until the header reads.
     */
    private suspend fun connect(port: Int): Triple<Socket, ScrcpyStream, StreamHeader> {
        var last: Exception? = null
        repeat(CONNECT_ATTEMPTS) {
            val socket = Socket("127.0.0.1", port).apply { tcpNoDelay = true }
            try {
                val stream = ScrcpyStream(socket.getInputStream().buffered(256 * 1024))
                return Triple(socket, stream, stream.readHeader())
            } catch (e: IOException) {
                last = e
                runCatching { socket.close() }
                delay(CONNECT_RETRY_MS)
            }
        }
        throw IOException("scrcpy-server did not answer", last)
    }

    private fun publish(frame: DecodedFrame) {
        val info = ImageInfo(frame.width, frame.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val bitmap = Bitmap()
        if (!bitmap.installPixels(info, frame.pixels, frame.width * 4)) return
        bitmap.setImmutable()
        _frame.value = bitmap.asComposeImageBitmap()
    }

    private fun drain(process: Process) {
        for (stream in listOf(process.inputStream, process.errorStream)) {
            Thread {
                runCatching { stream.bufferedReader().forEachLine { line -> if (line.isNotBlank()) println("[scrcpy] $line") } }
            }.apply { isDaemon = true; name = "scrcpy-log"; start() }
        }
    }

    private fun cleanup() {
        val running = session ?: return
        session = null
        runCatching { running.socket.close() }
        // cleanup=true lets the server tidy up on the phone once the socket closes.
        runCatching { running.process.destroy() }
        runCatching { Adb.run("-s", running.serial, "forward", "--remove", "tcp:${running.pcPort}", timeoutMs = 5_000) }
    }

    private companion object {
        const val CONNECT_ATTEMPTS = 50
        const val CONNECT_RETRY_MS = 100L
    }
}
