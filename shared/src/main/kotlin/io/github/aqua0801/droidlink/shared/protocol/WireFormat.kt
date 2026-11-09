package io.github.aqua0801.droidlink.shared.protocol

import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Frame layout (inside TLS):
 *
 * ```
 * [u32 length][u8 type][payload: length-1 bytes]
 * ```
 *
 * `type` lets binary streams (file chunks, video, logcat) share the connection with JSON
 * control messages later on without changing the framing.
 */
object FrameType {
    /** JSON-encoded [Message]. */
    const val MESSAGE: Byte = 1

    /** File data: `[i64 transfer id][bytes]`. See the transfer package. */
    const val FILE_CHUNK: Byte = 2
}

class Frame(val type: Byte, val payload: ByteArray)

object WireFormat {
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024

    val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(message: Message): ByteArray =
        json.encodeToString(Message.serializer(), message).encodeToByteArray()

    /** Returns null for messages this version does not understand. */
    fun decodeOrNull(payload: ByteArray): Message? =
        try {
            json.decodeFromString(Message.serializer(), payload.decodeToString())
        } catch (_: IllegalArgumentException) { // includes SerializationException
            null
        }
}

class FrameWriter(output: OutputStream) {
    private val out = DataOutputStream(BufferedOutputStream(output, 64 * 1024))

    fun write(type: Byte, payload: ByteArray) {
        require(payload.size < WireFormat.MAX_FRAME_BYTES) { "frame too large: ${payload.size}" }
        out.writeInt(payload.size + 1)
        out.writeByte(type.toInt())
        out.write(payload)
        out.flush()
    }
}

class FrameReader(input: InputStream) {
    private val input = DataInputStream(BufferedInputStream(input, 64 * 1024))

    /** Blocking. Returns null on a clean end of stream. */
    fun read(): Frame? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length < 1 || length > WireFormat.MAX_FRAME_BYTES) throw IOException("invalid frame length $length")
        val type = input.readByte()
        val payload = ByteArray(length - 1)
        input.readFully(payload)
        return Frame(type, payload)
    }
}
