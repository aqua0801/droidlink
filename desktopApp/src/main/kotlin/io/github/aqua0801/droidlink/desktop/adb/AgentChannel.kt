package io.github.aqua0801.droidlink.desktop.adb

import io.github.aqua0801.droidlink.shared.protocol.Frame
import io.github.aqua0801.droidlink.shared.protocol.FrameReader
import io.github.aqua0801.droidlink.shared.protocol.FrameWriter
import java.io.Closeable
import java.net.Socket

/**
 * Desktop end of the helper channel. The wire format ([u32 length][u8 type][payload]) is the
 * same generic framing the main protocol uses, so the IO classes are reused; the message
 * types below are specific to this channel and mirror the agent module's own copy.
 */
internal object AgentFrame {
    const val HELLO: Byte = 1 // agent -> desktop: "<model>\t<release>\t<sdk>"
    const val CMD: Byte = 2 // desktop -> agent: tab-separated command
    const val LOG: Byte = 3 // agent -> desktop: one system-log line
    const val EVENT: Byte = 4 // agent -> desktop: "<kind>\t<detail>"
    const val APK_DATA: Byte = 5 // desktop -> agent: a chunk of APK bytes
    const val APK_END: Byte = 6 // desktop -> agent: marks the end of the APK
}

/** One connected helper socket. A single thread reads; sends are serialized. */
class AgentChannel(private val socket: Socket) : Closeable {
    private val reader = FrameReader(socket.inputStream)
    private val writer = FrameWriter(socket.outputStream)
    private val sendLock = Any()

    /** Blocking; returns null at end of stream. Call from one reader thread only. */
    fun read(): Frame? = reader.read()

    fun send(type: Byte, payload: ByteArray = ByteArray(0)) {
        synchronized(sendLock) { writer.write(type, payload) }
    }

    fun sendText(type: Byte, text: String) = send(type, text.toByteArray(Charsets.UTF_8))

    override fun close() {
        runCatching { socket.close() }
    }
}
