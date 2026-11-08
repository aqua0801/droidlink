package io.github.aqua0801.droidlink.agent

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire format for the agent channel: `[u32 length][u8 type][payload]`, big-endian length
 * covering the type byte. This mirrors the desktop side byte for byte. It is deliberately
 * separate from the app's main protocol so this module stands on its own.
 */
internal object FrameType {
    const val HELLO: Byte = 1 // agent -> desktop: "<model>\t<release>\t<sdk>"
    const val CMD: Byte = 2 // desktop -> agent: command text, tab-separated
    const val LOG: Byte = 3 // agent -> desktop: one system-log line
    const val EVENT: Byte = 4 // agent -> desktop: "<kind>\t<detail>"
    const val APK_DATA: Byte = 5 // desktop -> agent: a chunk of APK bytes
    const val APK_END: Byte = 6 // desktop -> agent: file name, marks the end of the APK
    const val CLIP: Byte = 7 // both directions: clipboard text (UTF-8)
}

internal const val MAX_FRAME_BYTES = 16 * 1024 * 1024

internal class FrameReader(input: InputStream) {
    private val din = DataInputStream(input.buffered(64 * 1024))

    /** Blocking. Returns null at clean end of stream. */
    fun read(): Pair<Byte, ByteArray>? {
        val length = try {
            din.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length < 1 || length > MAX_FRAME_BYTES) throw IOException("invalid frame length $length")
        val type = din.readByte()
        val payload = ByteArray(length - 1)
        din.readFully(payload)
        return type to payload
    }
}

internal class FrameWriter(output: OutputStream) {
    private val dout = DataOutputStream(output.buffered(64 * 1024))

    @Synchronized
    fun write(type: Byte, payload: ByteArray) {
        dout.writeInt(payload.size + 1)
        dout.writeByte(type.toInt())
        dout.write(payload)
        dout.flush()
    }

    @Synchronized
    fun writeText(type: Byte, text: String) = write(type, text.toByteArray(Charsets.UTF_8))
}
