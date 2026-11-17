package io.github.aqua0801.droidlink.desktop.mirror

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Video codec announced by the server at the start of the stream. */
enum class VideoCodec(val id: Int) {
    H264(0x68323634), // "h264"
    H265(0x68323635), // "h265"
    AV1(0x00617631), // "av1"
    ;

    companion object {
        fun of(id: Int): VideoCodec? = entries.firstOrNull { it.id == id }
    }
}

/** Sent once after the connection opens. */
data class StreamHeader(val deviceName: String, val codec: VideoCodec)

/** One item read from the video socket. */
sealed interface StreamItem {
    /** A new capture session started (first frame, or the device rotated): frames now have this size. */
    data class Session(val width: Int, val height: Int, val clientResized: Boolean) : StreamItem

    /**
     * One encoded unit. [config] packets carry codec setup data (SPS/PPS) and must reach the
     * decoder together with the next frame; [ptsUs] is meaningless for them.
     */
    class Packet(val ptsUs: Long, val config: Boolean, val keyFrame: Boolean, val data: ByteArray) : StreamItem
}

/**
 * Reader for the scrcpy-server (v5.0.1) video socket, forward tunnel, metadata enabled:
 *
 *     [1 byte dummy]                              forward tunnel only
 *     [64 bytes device name, UTF-8, NUL-padded]
 *     [u32 codec id]
 *     repeated 12-byte header + payload:
 *       session: [u32 flags: bit 31 set, bit 0 = client resized][u32 width][u32 height]
 *       media:   [u64 bit 63 clear, bit 62 config, bit 61 key frame, low 61 bits pts µs][u32 size][size bytes]
 *
 * Big-endian throughout. The format changes between scrcpy versions; it must match the
 * bundled server (see ScrcpyServer.VERSION and docs/screen-mirror.md).
 */
class ScrcpyStream(input: InputStream, private val forwardTunnel: Boolean = true) {
    private val data = DataInputStream(input)
    private var dummyRead = !forwardTunnel

    /**
     * Forward tunnel only: the server writes this byte as soon as it accepts the video
     * socket, so reading it proves the server is up. The rest of the header only follows
     * once every socket (video, then control) is connected.
     */
    fun readDummy() {
        if (!dummyRead) {
            data.readUnsignedByte()
            dummyRead = true
        }
    }

    fun readHeader(): StreamHeader {
        readDummy()
        val nameBytes = ByteArray(DEVICE_NAME_FIELD).also(data::readFully)
        val nameEnd = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
        val name = String(nameBytes, 0, nameEnd, Charsets.UTF_8)
        val codecId = data.readInt()
        val codec = VideoCodec.of(codecId) ?: throw IllegalStateException("unknown codec 0x%08x".format(codecId))
        return StreamHeader(name, codec)
    }

    /** Next item, or null at a clean end of stream. */
    fun read(): StreamItem? {
        val head = try {
            data.readLong()
        } catch (_: EOFException) {
            return null
        }
        val tail = data.readInt()
        if (head and FLAG_SESSION != 0L) {
            return StreamItem.Session(
                width = (head and 0xFFFF_FFFFL).toInt(),
                height = tail,
                clientResized = (head ushr 32) and 1L != 0L,
            )
        }
        require(tail in 0..MAX_PACKET) { "bad packet size $tail" }
        val payload = ByteArray(tail).also(data::readFully)
        return StreamItem.Packet(
            ptsUs = head and PTS_MASK,
            config = head and FLAG_CONFIG != 0L,
            keyFrame = head and FLAG_KEY_FRAME != 0L,
            data = payload,
        )
    }

    companion object {
        const val DEVICE_NAME_FIELD = 64
        const val FLAG_SESSION = 1L shl 63
        const val FLAG_CONFIG = 1L shl 62
        const val FLAG_KEY_FRAME = 1L shl 61
        const val PTS_MASK = FLAG_KEY_FRAME - 1
        /** Sanity cap; a 4K key frame is well below this. */
        const val MAX_PACKET = 32 * 1024 * 1024
    }
}
