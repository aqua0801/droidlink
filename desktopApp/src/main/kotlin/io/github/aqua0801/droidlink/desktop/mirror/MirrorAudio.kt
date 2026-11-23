package io.github.aqua0801.droidlink.desktop.mirror

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Reader for the scrcpy-server (v5.0.1) audio socket:
 *
 *     [u32 codec id]          0 = disabled by the device (e.g. Android < 11), 1 = capture error
 *     repeated media packets, same 12-byte header as video; no session packets
 *
 * DroidLink requests `audio_codec=raw`: 16-bit little-endian PCM, 48 kHz, stereo. Over adb that
 * is ~1.5 Mbit/s, cheap enough to skip decoding and keep latency minimal.
 */
class ScrcpyAudioStream(input: InputStream) {
    private val data = DataInputStream(input)

    sealed interface Start {
        data object Raw : Start
        data object Disabled : Start
        data class Unsupported(val codecId: Int) : Start
    }

    fun readStart(): Start = when (val id = data.readInt()) {
        RAW -> Start.Raw
        DISABLED, ERROR -> Start.Disabled
        else -> Start.Unsupported(id)
    }

    /** Next PCM payload (config packets skipped), or null at the end of the stream. */
    fun readPcm(): ByteArray? {
        while (true) {
            val head = try {
                data.readLong()
            } catch (_: EOFException) {
                return null
            }
            val size = data.readInt()
            require(size in 0..MAX_PACKET) { "bad audio packet size $size" }
            val payload = ByteArray(size).also(data::readFully)
            if (head and ScrcpyStream.FLAG_CONFIG == 0L) return payload
        }
    }

    companion object {
        const val RAW = 0x00726177 // "raw"
        const val DISABLED = 0
        const val ERROR = 1
        private const val MAX_PACKET = 1024 * 1024
    }
}

/**
 * Plays 48 kHz stereo s16le PCM through the default output device.
 *
 * Packets arrive in bursts, so the line keeps a small cushion ([PREFILL_MS]) and drops packets
 * whenever more than [MAX_LATENCY_MS] is queued; audio then never drifts behind the video.
 */
class PcmPlayer private constructor(private val line: SourceDataLine) : Closeable {
    @Volatile
    var muted: Boolean = false
        set(value) {
            field = value
            if (value) line.flush()
        }

    fun play(pcm: ByteArray) {
        if (muted) return
        val length = pcm.size - pcm.size % FRAME_BYTES
        val queued = line.bufferSize - line.available()
        if (queued > bytes(MAX_LATENCY_MS)) return
        if (queued == 0) line.write(ByteArray(bytes(PREFILL_MS)), 0, bytes(PREFILL_MS))
        line.write(pcm, 0, length)
    }

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.close() }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_BYTES = 4 // 2 channels x 16 bit
        private const val BUFFER_MS = 400
        private const val MAX_LATENCY_MS = 120
        private const val PREFILL_MS = 40

        private fun bytes(ms: Int) = SAMPLE_RATE / 1000 * ms * FRAME_BYTES

        /** Null when the system has no usable output device (headless, no sound server, ...). */
        fun open(): PcmPlayer? = runCatching {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, bytes(BUFFER_MS))
            line.start()
            PcmPlayer(line)
        }.onFailure { System.err.println("DroidLink: no audio output: $it") }.getOrNull()
    }
}
