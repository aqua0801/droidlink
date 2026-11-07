package io.github.aqua0801.droidlink.shared.link

import io.github.aqua0801.droidlink.shared.crypto.Fingerprint
import io.github.aqua0801.droidlink.shared.protocol.FrameReader
import io.github.aqua0801.droidlink.shared.protocol.FrameType
import io.github.aqua0801.droidlink.shared.protocol.FrameWriter
import io.github.aqua0801.droidlink.shared.protocol.Message
import io.github.aqua0801.droidlink.shared.protocol.WireFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import javax.net.ssl.SSLSocket

/** A framed message stream over an already-handshaken TLS socket. */
class Connection(private val socket: SSLSocket) : Closeable {
    val peerFingerprint: Fingerprint = Fingerprint.of(socket.session.peerCertificates.first())
    val remoteAddress: String = socket.inetAddress?.hostAddress ?: "?"

    private val reader = FrameReader(socket.inputStream)
    private val writer = FrameWriter(socket.outputStream)
    private val writeLock = Mutex()

    var readTimeoutMs: Int
        get() = socket.soTimeout
        set(value) {
            socket.soTimeout = value
        }

    suspend fun send(message: Message) {
        val bytes = WireFormat.encode(message)
        writeLock.withLock {
            withContext(Dispatchers.IO) { writer.write(FrameType.MESSAGE, bytes) }
        }
    }

    /**
     * Blocking: call from an IO thread. Returns null on end of stream.
     * Frames of unknown type and messages this version can't decode are skipped.
     */
    fun receive(): Message? {
        while (true) {
            val frame = reader.read() ?: return null
            if (frame.type != FrameType.MESSAGE) continue
            WireFormat.decodeOrNull(frame.payload)?.let { return it }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
