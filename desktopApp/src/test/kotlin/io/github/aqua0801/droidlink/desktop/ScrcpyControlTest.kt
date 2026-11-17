package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.mirror.ScrcpyControl
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** Layouts follow scrcpy-server v5.0.1 ControlMessageReader. */
class ScrcpyControlTest {
    private fun read(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))

    @Test
    fun touchLayout() {
        val bytes = ScrcpyControl.touch(ScrcpyControl.ACTION_DOWN, ScrcpyControl.Point(100, 200, 720, 1600))
        assertEquals(32, bytes.size)
        read(bytes).apply {
            assertEquals(2, readUnsignedByte())
            assertEquals(ScrcpyControl.ACTION_DOWN, readUnsignedByte())
            assertEquals(ScrcpyControl.POINTER_ID_FINGER, readLong())
            assertEquals(100, readInt()); assertEquals(200, readInt())
            assertEquals(720, readUnsignedShort()); assertEquals(1600, readUnsignedShort())
            assertEquals(0xffff, readUnsignedShort())
            assertEquals(0, readInt()); assertEquals(0, readInt())
        }
        val up = ScrcpyControl.touch(ScrcpyControl.ACTION_UP, ScrcpyControl.Point(1, 1, 720, 1600))
        assertEquals(0, read(up).apply { skipBytes(22) }.readUnsignedShort())
    }

    @Test
    fun keycodeTextScrollClipboard() {
        val key = ScrcpyControl.keycode(ScrcpyControl.ACTION_UP, ScrcpyControl.KEYCODE_HOME)
        assertEquals(14, key.size)
        read(key).apply {
            assertEquals(0, readUnsignedByte()); assertEquals(1, readUnsignedByte()); assertEquals(3, readInt())
        }

        val text = ScrcpyControl.text("héllo")
        read(text).apply {
            assertEquals(1, readUnsignedByte())
            assertEquals(6, readInt()) // é is two bytes
        }

        val scroll = ScrcpyControl.scroll(ScrcpyControl.Point(5, 6, 720, 1600), h = 0f, v = -1f)
        assertEquals(21, scroll.size)
        read(scroll).apply {
            assertEquals(3, readUnsignedByte()); skipBytes(12)
            assertEquals(0, readShort().toInt())
            assertEquals(-2048, readShort().toInt()) // -1/16 in Q15
        }

        val clip = ScrcpyControl.setClipboard("ab", paste = true)
        read(clip).apply {
            assertEquals(9, readUnsignedByte()); assertEquals(0L, readLong()); assertEquals(1, readUnsignedByte())
            assertEquals(2, readInt())
        }
        assertEquals(listOf<Byte>(11), ScrcpyControl.rotateDevice().toList())
    }
}
