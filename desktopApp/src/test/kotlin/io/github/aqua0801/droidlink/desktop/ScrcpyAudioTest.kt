package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.mirror.ScrcpyAudioStream
import io.github.aqua0801.droidlink.desktop.mirror.ScrcpyServer
import io.github.aqua0801.droidlink.desktop.mirror.ScrcpyStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrcpyAudioTest {
    private fun stream(build: DataOutputStream.() -> Unit): ScrcpyAudioStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).build()
        return ScrcpyAudioStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test
    fun readsRawPcmAndSkipsConfigPackets() {
        val pcm = ByteArray(16) { it.toByte() }
        val s = stream {
            writeInt(ScrcpyAudioStream.RAW)
            writeLong(ScrcpyStream.FLAG_CONFIG) // config packet: skipped
            writeInt(2)
            write(byteArrayOf(9, 9))
            writeLong(20_000L) // pts
            writeInt(pcm.size)
            write(pcm)
        }
        assertEquals(ScrcpyAudioStream.Start.Raw, s.readStart())
        assertContentEquals(pcm, s.readPcm())
        assertNull(s.readPcm())
    }

    @Test
    fun recognizesDisabledAndUnknownCodecs() {
        assertEquals(ScrcpyAudioStream.Start.Disabled, stream { writeInt(0) }.readStart())
        assertEquals(ScrcpyAudioStream.Start.Disabled, stream { writeInt(1) }.readStart())
        assertIs<ScrcpyAudioStream.Start.Unsupported>(stream { writeInt(0x6F707573) }.readStart()) // opus
    }

    @Test
    fun audioServerArguments() {
        val off = ScrcpyServer.Options(scid = 1).toArgs()
        assertTrue("audio=false" in off)
        assertFalse(off.any { it.startsWith("audio_codec") })

        val on = ScrcpyServer.Options(scid = 1, audio = true).toArgs()
        assertTrue("audio=true" in on && "audio_codec=raw" in on)
        assertFalse(on.any { it.startsWith("audio_dup") })

        val dup = ScrcpyServer.Options(scid = 1, audio = true, audioKeepOnDevice = true).toArgs()
        assertTrue("audio_source=playback" in dup && "audio_dup=true" in dup)
    }
}
