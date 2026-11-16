package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.mirror.ScrcpyServer
import io.github.aqua0801.droidlink.desktop.mirror.ScrcpyStream
import io.github.aqua0801.droidlink.desktop.mirror.StreamItem
import io.github.aqua0801.droidlink.desktop.mirror.VideoCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrcpyStreamTest {
    private fun stream(build: DataOutputStream.() -> Unit): ScrcpyStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).build()
        return ScrcpyStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test
    fun parsesHeaderSessionsAndPackets() {
        val s = stream {
            writeByte(0) // dummy byte (forward tunnel)
            write("vivo X200".toByteArray().copyOf(ScrcpyStream.DEVICE_NAME_FIELD))
            writeInt(VideoCodec.H264.id)
            // session packet: bit 31 of the first u32, then width / height
            writeInt(1 shl 31); writeInt(720); writeInt(1600)
            writeLong(ScrcpyStream.FLAG_CONFIG); writeInt(3); write(byteArrayOf(1, 2, 3))
            writeLong(ScrcpyStream.FLAG_KEY_FRAME or 123_456L); writeInt(2); write(byteArrayOf(9, 8))
            writeLong(456_789L); writeInt(0)
            // rotation: new session with the client-resized bit set
            writeInt((1 shl 31) or 1); writeInt(1600); writeInt(720)
        }
        val header = s.readHeader()
        assertEquals("vivo X200", header.deviceName)
        assertEquals(VideoCodec.H264, header.codec)

        assertEquals(StreamItem.Session(720, 1600, clientResized = false), s.read())

        val config = assertIs<StreamItem.Packet>(s.read())
        assertTrue(config.config)
        assertContentEquals(byteArrayOf(1, 2, 3), config.data)

        val key = assertIs<StreamItem.Packet>(s.read())
        assertTrue(key.keyFrame)
        assertFalse(key.config)
        assertEquals(123_456L, key.ptsUs)

        val delta = assertIs<StreamItem.Packet>(s.read())
        assertFalse(delta.keyFrame)
        assertEquals(456_789L, delta.ptsUs)

        assertEquals(StreamItem.Session(1600, 720, clientResized = true), s.read())
        assertNull(s.read())
    }

    @Test
    fun launchArgumentsAndBundledJar() {
        val options = ScrcpyServer.Options(scid = 0x1234abcd, maxSize = 1280)
        assertEquals("scrcpy_1234abcd", options.socketName)
        val args = options.toArgs()
        assertTrue("tunnel_forward=true" in args)
        assertTrue("max_size=1280" in args)
        assertTrue("control=false" in args)
        assertTrue("clipboard_autosync=false" in args)
        assertEquals(
            listOf("CLASSPATH=${ScrcpyServer.REMOTE_JAR}", "app_process", "/", "com.genymobile.scrcpy.Server", "5.0.1"),
            ScrcpyServer.shellArgs(options).take(5),
        )
        assertNotNull(ScrcpyServer.jarFile(), "scrcpy-server.jar must be bundled as a resource")
    }
}
