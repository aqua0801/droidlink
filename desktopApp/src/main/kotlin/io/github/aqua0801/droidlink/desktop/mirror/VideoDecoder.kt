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
    /** [waitingForPhone]: wireless request sent, the user still has to accept on the phone. */
    data class Starting(val target: String, val waitingForPhone: Boolean = false) : MirrorState

    /** [controllable] is false for wireless mirroring (view only). */
    data class Running(
        val target: String,
        val deviceName: String,
        val width: Int,
        val height: Int,
        val controllable: Boolean = true,
    ) : MirrorState

    data class Failed(val target: String, val reason: MirrorFailure, val detail: String? = null) : MirrorState
}

enum class MirrorFailure { ERROR, DECLINED, NO_ANSWER
}
