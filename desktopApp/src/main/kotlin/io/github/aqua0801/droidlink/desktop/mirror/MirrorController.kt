package io.github.aqua0801.droidlink.desktop.mirror

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import io.github.aqua0801.droidlink.desktop.adb.Adb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import java.io.File
import java.io.IOException
import java.net.Socket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Phone screen over ADB with the bundled scrcpy-server: video in, touch/keys out.
 * One mirror at a time. Frames are decoded on an IO thread and published as the latest
 * [ImageBitmap]; control messages go through a single queue so their order is kept.
 */
class MirrorController(private val scope: CoroutineScope, private val screenshotDir: File) {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    private var job: Job? = null
    @Volatile
    private var session: Running? = null

    private class Running(
        val serial: String,
        val process: Process,
        val pcPort: Int,
        val video: Socket,
        val control: Socket,
        val outbox: Channel<ByteArray>,
    )

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

    /** Queues a control message (see [ScrcpyControl]); dropped when not mirroring. */
    fun send(message: ByteArray) {
        session?.outbox?.trySend(message)
    }

    /** Builds a position in the current frame's coordinates, or null before the first frame. */
    fun point(x: Int, y: Int): ScrcpyControl.Point? {
        val running = _state.value as? MirrorState.Running ?: return null
        return ScrcpyControl.Point(x.coerceIn(0, running.width - 1), y.coerceIn(0, running.height - 1), running.width, running.height)
    }

    fun pressKey(keycode: Int) {
        send(ScrcpyControl.keycode(ScrcpyControl.ACTION_DOWN, keycode))
        send(ScrcpyControl.keycode(ScrcpyControl.ACTION_UP, keycode))
    }

    /** Saves the current frame as PNG under the screenshot folder. Blocking; call off the UI thread. */
    fun saveScreenshot(): File? {
        val image = _frame.value ?: return null
        val name = (_state.value as? MirrorState.Running)?.deviceName?.replace(Regex("[^A-Za-z0-9_-]"), "") ?: "phone"
        screenshotDir.mkdirs()
        val file = File(screenshotDir, "mirror-$name-${LocalDateTime.now().format(STAMP)}.png")
        return runCatching { ImageIO.write(image.toAwtImage(), "png", file); file }.getOrNull()
    }

    private suspend fun run(serial: String) {
        val jar = ScrcpyServer.jarFile() ?: throw IllegalStateException("scrcpy-server is not bundled")
        val push = Adb.run("-s", serial, "push", jar.absolutePath, ScrcpyServer.REMOTE_JAR, timeoutMs = 30_000)
        if (!push.ok) throw IOException("push failed: ${push.output}")

        val options = ScrcpyServer.Options(scid = Random.nextInt(0x7FFF_FFFF), control = true)
        val forward = Adb.run("-s", serial, "forward", "tcp:0", "localabstract:${options.socketName}")
        val pcPort = forward.output.lines().firstNotNullOfOrNull { it.trim().toIntOrNull() }
        if (!forward.ok || pcPort == null) throw IOException("forward failed: ${forward.output}")

        val process = Adb.start("-s", serial, "shell", *ScrcpyServer.shellArgs(options).toTypedArray())
            ?: throw IllegalStateException("adb not found")
        drain(process)

        val (video, stream) = connectVideo(pcPort)
        val control = Socket("127.0.0.1", pcPort).apply { tcpNoDelay = true }
        val outbox = Channel<ByteArray>(Channel.UNLIMITED)
        session = Running(serial, process, pcPort, video, control, outbox)
        val header = stream.readHeader()

        // Writer: one coroutine, so messages keep their order.
        val channel = ControlChannel(control.getOutputStream().buffered())
        scope.launch(Dispatchers.IO) {
            runCatching { for (message in outbox) channel.send(message) }
        }
        // Device messages (clipboard, acks) are not used yet; read them so the socket never fills up.
        Thread {
            runCatching { control.getInputStream().let { input -> val buf = ByteArray(4096); while (input.read(buf) >= 0) Unit } }
        }.apply { isDaemon = true; name = "scrcpy-control-in"; start() }

        FfmpegVideoDecoder(header.codec).use { decoder ->
            while (currentCoroutineContext().isActive) {
                when (val item = stream.read() ?: break) {
                    is StreamItem.Session ->
                        _state.value = MirrorState.Running(serial, header.deviceName, item.width, item.height)
                    is StreamItem.Packet -> decoder.decode(item)?.let { publish(it) }
                }
            }
        }
    }

    /**
     * With a forward tunnel the TCP connect succeeds as soon as adb listens, even before the
     * server is up; the dummy byte only arrives once it is. So retry until it reads.
     */
    private suspend fun connectVideo(port: Int): Pair<Socket, ScrcpyStream> {
        var last: Exception? = null
        repeat(CONNECT_ATTEMPTS) {
            val socket = Socket("127.0.0.1", port).apply { tcpNoDelay = true }
            try {
                val stream = ScrcpyStream(socket.getInputStream().buffered(256 * 1024))
                stream.readDummy()
                return socket to stream
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
        running.outbox.close()
        runCatching { running.control.close() }
        runCatching { running.video.close() }
        // cleanup=true lets the server tidy up on the phone once the sockets close.
        runCatching { running.process.destroy() }
        runCatching { Adb.run("-s", running.serial, "forward", "--remove", "tcp:${running.pcPort}", timeoutMs = 5_000) }
    }

    private companion object {
        const val CONNECT_ATTEMPTS = 50
        const val CONNECT_RETRY_MS = 100L
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
