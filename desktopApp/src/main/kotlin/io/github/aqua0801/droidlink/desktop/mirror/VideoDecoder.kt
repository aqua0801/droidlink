package io.github.aqua0801.droidlink.desktop.mirror

import java.io.Closeable

/** A decoded picture in packed BGRA (Skia's native order on Windows), `width * 4` bytes per row. */
class DecodedFrame(val width: Int, val height: Int, val pixels: ByteArray, val ptsUs: Long)

/** Turns encoded packets into pictures. Not thread-safe: feed it from one thread. */
interface VideoDecoder : Closeable {
    /** Returns a frame when one is ready; config packets and some frames produce none. */
    fun decode(packet: StreamItem.Packet): DecodedFrame?
}

/** What the mirror window is showing. */
sealed interface MirrorState {
    data object Idle : MirrorState
    data class Starting(val serial: String) : MirrorState
    data class Running(val serial: String, val deviceName: String, val width: Int, val height: Int) : MirrorState
    data class Failed(val serial: String, val reason: String) : MirrorState
}
