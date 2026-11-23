package io.github.aqua0801.droidlink.desktop.mirror

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import io.github.aqua0801.droidlink.desktop.MirrorSettings
import io.github.aqua0801.droidlink.desktop.adb.Adb
import io.github.aqua0801.droidlink.shared.link.Connection
import io.github.aqua0801.droidlink.shared.link.LinkSession
import io.github.aqua0801.droidlink.shared.protocol.MirrorReady
import io.github.aqua0801.droidlink.shared.protocol.MirrorRequest
import io.github.aqua0801.droidlink.shared.protocol.MirrorStop
import io.github.aqua0801.droidlink.shared.protocol.MirrorStreamHello
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
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Shows one phone screen at a time, from either source:
 * - ADB: bundled scrcpy-server, video in and touch/keys out ([startAdb]).
 * - Wireless: the phone's own MediaProjection encoder over a second TLS connection, view
 *   only ([startWireless] / [onStream]); the stream uses the same layout, so the same parser
 *   and decoder serve both.
 * Frames are decoded on an IO thread and published as the latest [ImageBitmap]. Over ADB the
 * phone's audio is played as well ([audio]).
 */
class MirrorController(
    private val scope: CoroutineScope,
    private val screenshotDir: File,
    private val settings: () -> MirrorSettings,
) {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    /** Phone audio in the current mirror: OFF when not forwarded (wireless, off in settings, unsupported). */
    enum class Audio { OFF, PLAYING, MUTED }

    private val _audio = MutableStateFlow(Audio.OFF)
    val audio: StateFlow<Audio> = _audio.asStateFlow()

    @Volatile
    private var player: PcmPlayer? = null

    fun toggleMute() {
        val current = player ?: return
        current.muted = !current.muted
        _audio.value = if (current.muted) Audio.MUTED else Audio.PLAYING
    }

    private var job: Job? = null

    /** Everything to close when the current mirror ends. */
    private val resources = mutableListOf<Closeable>()

    @Volatile
    private var outbox: Channel<ByteArray>? = null

    /** Pending or active wireless request. */
    @Volatile
    private var wireless: Wireless? = null

    private class Wireless(val requestId: Long, val token: String, val session: LinkSession, val peerName: String)

    // ---- ADB -------------------------------------------------------------------------------

    fun startAdb(serial: String) {
        stop()
        _state.value = MirrorState.Starting(serial)
        launchRun(serial) { runAdb(serial) }
    }

    // ---- Wireless --------------------------------------------------------------------------

    /** Asks the phone behind [session] to start mirroring; the user has to accept on the phone. */
    fun startWireless(session: LinkSession, maxSize: Int = 1600) {
        stop()
        val request = Wireless(Random.nextLong(1, Long.MAX_VALUE), newToken(), session, session.peerName)
        wireless = request
        _state.value = MirrorState.Starting(session.peerName, waitingForPhone = true)
        job = scope.launch(Dispatchers.IO) {
            runCatching { session.send(MirrorRequest(request.requestId, request.token, maxSize)) }
            delay(ANSWER_TIMEOUT_MS)
            if (wireless === request && _state.value is MirrorState.Starting) {
                fail(request.peerName, MirrorFailure.NO_ANSWER)
                sendStop(request)
                wireless = null
            }
        }
    }

    /** A stream connection arrived (LinkServer). Returns false if it matches no pending request. */
    fun onStream(connection: Connection, hello: MirrorStreamHello): Boolean {
        val request = wireless ?: return false
        if (hello.token != request.token || connection.peerFingerprint != request.session.peerFingerprint) return false
        if (_state.value !is MirrorState.Starting) return false
        job?.cancel()
        launchRun(request.peerName) {
            connection.send(MirrorReady())
            val socket = connection.releaseSocket()
            synchronized(resources) { resources += socket }
            socket.soTimeout = STREAM_READ_TIMEOUT_MS
            val stream = ScrcpyStream(socket.inputStream.buffered(256 * 1024), forwardTunnel = false)
            pump(stream, stream.readHeader(), request.peerName, controllable = false)
        }
        return true
    }

    fun onDeclined(requestId: Long) {
        val request = wireless?.takeIf { it.requestId == requestId } ?: return
        wireless = null
        job?.cancel()
        fail(request.peerName, MirrorFailure.DECLINED)
    }

    /** The phone ended the session (stop button, projection revoked). */
    fun onPhoneStopped(requestId: Long) {
        if (wireless?.requestId != requestId) return
        wireless = null
        stop()
    }

    // ---- Common ----------------------------------------------------------------------------

    fun stop() {
        job?.cancel()
        job = null
        wireless?.let(::sendStop)
        wireless = null
        cleanup()
        _frame.value = null
        _state.value = MirrorState.Idle
    }

    /** Queues a control message (see [ScrcpyControl]); dropped when the mirror is view only. */
    fun send(message: ByteArray) {
        outbox?.trySend(message)
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

    private fun launchRun(target: String, body: suspend () -> Unit) {
        job = scope.launch(Dispatchers.IO) {
            try {
                body()
                if (_state.value !is MirrorState.Failed) _state.value = MirrorState.Idle
            } catch (e: Exception) {
                if (isActive) fail(target, MirrorFailure.ERROR, e.message ?: e.javaClass.simpleName)
            } finally {
                wireless = null
                cleanup()
            }
        }
    }

    private suspend fun runAdb(serial: String) {
        val jar = ScrcpyServer.jarFile() ?: throw IllegalStateException("scrcpy-server is not bundled")
        val push = Adb.run("-s", serial, "push", jar.absolutePath, ScrcpyServer.REMOTE_JAR, timeoutMs = 30_000)
        if (!push.ok) throw IOException("push failed: ${push.output}")

        val sdk = Adb.run("-s", serial, "shell", "getprop", "ro.build.version.sdk", timeoutMs = 5_000)
            .output.trim().toIntOrNull() ?: 0
        val prefs = settings()
        val options = ScrcpyServer.Options(
            scid = Random.nextInt(0x7FFF_FFFF),
            control = true,
            // Android 11 added playback capture; keeping the sound on the phone needs Android 13.
            audio = prefs.audio && sdk >= 30,
            audioKeepOnDevice = prefs.audioKeepOnPhone && sdk >= 33,
        )
        val forward = Adb.run("-s", serial, "forward", "tcp:0", "localabstract:${options.socketName}")
        val pcPort = forward.output.lines().firstNotNullOfOrNull { it.trim().toIntOrNull() }
        if (!forward.ok || pcPort == null) throw IOException("forward failed: ${forward.output}")
        track(Closeable { Adb.run("-s", serial, "forward", "--remove", "tcp:$pcPort", timeoutMs = 5_000) })

        val process = Adb.start("-s", serial, "shell", *ScrcpyServer.shellArgs(options).toTypedArray())
            ?: throw IllegalStateException("adb not found")
        // cleanup=true lets the server tidy up on the phone once the sockets close.
        track(Closeable { process.destroy() })
        drain(process)

        // The server sends device meta only after every socket is connected: read the dummy
        // byte on video, connect audio and control (in that order), then read the rest.
        val (video, stream) = connectVideo(pcPort)
        track(video)
        if (options.audio) {
            val audioSocket = Socket("127.0.0.1", pcPort).apply { tcpNoDelay = true }
            track(audioSocket)
            startAudio(audioSocket)
        }
        val control = Socket("127.0.0.1", pcPort).apply { tcpNoDelay = true }
        track(control)
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        outbox = queue
        track(Closeable { queue.close(); outbox = null })
        val header = stream.readHeader()

        val channel = ControlChannel(control.getOutputStream().buffered())
        scope.launch(Dispatchers.IO) { runCatching { for (message in queue) channel.send(message) } }
        // Device messages (clipboard, acks) are not used yet; read them so the socket never fills up.
        Thread {
            runCatching { control.getInputStream().let { input -> val buf = ByteArray(4096); while (input.read(buf) >= 0) Unit } }
        }.apply { isDaemon = true; name = "scrcpy-control-in"; start() }

        pump(stream, header, serial, controllable = true)
    }

    /**
     * Plays the audio socket on its own thread. The socket is always drained, even without an
     * output device or when muted, so the server never blocks on it.
     */
    private fun startAudio(socket: Socket) {
        Thread {
            runCatching {
                val stream = ScrcpyAudioStream(socket.getInputStream().buffered(64 * 1024))
                val start = stream.readStart()
                if (start is ScrcpyAudioStream.Start.Unsupported) System.err.println("DroidLink: unexpected audio codec $start")
                val output = if (start == ScrcpyAudioStream.Start.Raw) PcmPlayer.open() else null
                if (output != null && socket.isClosed) {
                    // The mirror ended while the output device was opening.
                    output.close()
                    return@runCatching
                }
                if (output != null) {
                    player = output
                    track(output)
                    _audio.value = Audio.PLAYING
                }
                while (true) {
                    val pcm = stream.readPcm() ?: break
                    output?.play(pcm)
                }
            }
        }.apply { isDaemon = true; name = "scrcpy-audio"; start() }
    }

    /** Reads and decodes until the stream ends or the job is cancelled. */
    private suspend fun pump(stream: ScrcpyStream, header: StreamHeader, target: String, controllable: Boolean) {
        FfmpegVideoDecoder(header.codec).use { decoder ->
            while (currentCoroutineContext().isActive) {
                when (val item = stream.read() ?: break) {
                    is StreamItem.Session ->
                        _state.value = MirrorState.Running(target, header.deviceName, item.width, item.height, controllable)
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

    private fun sendStop(request: Wireless) {
        scope.launch(Dispatchers.IO) { runCatching { request.session.send(MirrorStop(request.requestId)) } }
    }

    private fun fail(target: String, reason: MirrorFailure, detail: String? = null) {
        _frame.value = null
        _state.value = MirrorState.Failed(target, reason, detail)
    }

    private fun track(resource: Closeable) {
        synchronized(resources) { resources += resource }
    }

    private fun cleanup() {
        player = null
        _audio.value = Audio.OFF
        val toClose = synchronized(resources) { resources.toList().also { resources.clear() } }
        toClose.asReversed().forEach { runCatching { it.close() } }
    }

    private fun newToken(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))

    private companion object {
        const val CONNECT_ATTEMPTS = 50
        const val CONNECT_RETRY_MS = 100L
        /** How long the phone has to accept; matches the phone's notification timeout. */
        const val ANSWER_TIMEOUT_MS = 90_000L
        /** The phone repeats frames on a still screen, so a long silence means the link is gone. */
        const val STREAM_READ_TIMEOUT_MS = 15_000
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
