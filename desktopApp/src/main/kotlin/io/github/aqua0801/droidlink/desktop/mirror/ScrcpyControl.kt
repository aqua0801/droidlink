package io.github.aqua0801.droidlink.desktop.mirror

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * Client-to-device control messages for scrcpy-server v5.0.1 (ControlMessageReader on the
 * server side). Big-endian. Positions are in video-frame pixels and carry the frame size
 * they refer to; the server drops events whose size does not match the current video size.
 */
object ScrcpyControl {
    // Message types
    private const val INJECT_KEYCODE = 0
    private const val INJECT_TEXT = 1
    private const val INJECT_TOUCH = 2
    private const val INJECT_SCROLL = 3
    private const val BACK_OR_SCREEN_ON = 4
    private const val SET_CLIPBOARD = 9
    private const val ROTATE_DEVICE = 11

    // android.view.KeyEvent / MotionEvent values
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_POWER = 26
    const val KEYCODE_TAB = 61
    const val KEYCODE_ENTER = 66
    const val KEYCODE_DEL = 67
    const val KEYCODE_PAGE_UP = 92
    const val KEYCODE_PAGE_DOWN = 93
    const val KEYCODE_FORWARD_DEL = 112
    const val KEYCODE_MOVE_HOME = 122
    const val KEYCODE_MOVE_END = 123
    const val KEYCODE_APP_SWITCH = 187

    /** A finger rather than a mouse, so taps and drags behave like touch on every app. */
    const val POINTER_ID_FINGER = -2L

    /** Server-side limit for injected text per message is generous; stay small like the scrcpy client. */
    const val MAX_TEXT_BYTES = 300

    data class Point(val x: Int, val y: Int, val frameWidth: Int, val frameHeight: Int)

    fun keycode(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0): ByteArray = message {
        writeByte(INJECT_KEYCODE)
        writeByte(action)
        writeInt(keycode)
        writeInt(repeat)
        writeInt(metaState)
    }

    fun text(text: String): ByteArray = message {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "text too long" }
        writeByte(INJECT_TEXT)
        writeInt(bytes.size)
        write(bytes)
    }

    fun touch(action: Int, point: Point, pointerId: Long = POINTER_ID_FINGER): ByteArray = message {
        writeByte(INJECT_TOUCH)
        writeByte(action)
        writeLong(pointerId)
        position(point)
        // u16 fixed point; 0xffff means 1.0
        writeShort(if (action == ACTION_UP) 0 else 0xffff)
        writeInt(0) // action button
        writeInt(0) // buttons (ignored for finger events)
    }

    /** [h] / [v] in wheel notches; positive [v] scrolls content up (Android AXIS_VSCROLL). */
    fun scroll(point: Point, h: Float, v: Float): ByteArray = message {
        writeByte(INJECT_SCROLL)
        position(point)
        // i16 fixed point in [-1, 1]; the server multiplies by 16.
        writeShort(fixedI16(h / 16f))
        writeShort(fixedI16(v / 16f))
        writeInt(0)
    }

    /** Back key, or turns the screen on when it is off. */
    fun backOrScreenOn(action: Int): ByteArray = message {
        writeByte(BACK_OR_SCREEN_ON)
        writeByte(action)
    }

    /** Sets the device clipboard; with [paste] also pastes it into the focused field. */
    fun setClipboard(text: String, paste: Boolean): ByteArray = message {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeByte(SET_CLIPBOARD)
        writeLong(0) // sequence 0: no acknowledgement wanted
        writeByte(if (paste) 1 else 0)
        writeInt(bytes.size)
        write(bytes)
    }

    fun rotateDevice(): ByteArray = byteArrayOf(ROTATE_DEVICE.toByte())

    private fun DataOutputStream.position(p: Point) {
        writeInt(p.x)
        writeInt(p.y)
        writeShort(p.frameWidth)
        writeShort(p.frameHeight)
    }

    private fun fixedI16(value: Float): Int {
        val clamped = value.coerceIn(-1f, 1f)
        return if (clamped >= 1f) 0x7fff else (clamped * 0x8000).toInt()
    }

    private inline fun message(build: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(32)
        DataOutputStream(bytes).use(build)
        return bytes.toByteArray()
    }
}

/** Serializes writes from the UI thread and input handlers onto the control socket. */
class ControlChannel(private val output: OutputStream) {
    private val lock = Any()

    fun send(message: ByteArray) {
        synchronized(lock) {
            output.write(message)
            output.flush()
        }
    }
}
