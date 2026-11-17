package io.github.aqua0801.droidlink.mirror

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.Display
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * Encodes the screen with MediaCodec (H.264) and writes it in the scrcpy-server stream
 * layout, so the PC reuses its scrcpy parser and decoder:
 *
 *     [64 bytes device name][u32 codec id "h264"]
 *     session packet on start and after each size change: [u32 0x80000000][u32 w][u32 h]
 *     media packets: [u64 flags|pts][u32 size][bytes]   (bit 62 config, bit 61 key frame)
 *
 * Android 14+ allows one VirtualDisplay per MediaProjection, so a rotation keeps the display
 * and only swaps in a new encoder surface and size.
 */
class ScreenStreamer(
    private val projection: MediaProjection,
    private val display: Display,
    private val deviceName: String,
    private val maxSize: Int,
) {
    @Volatile
    private var running = true

    @Volatile
    private var sizeChanged = false

    /** Call when the screen may have rotated; the encoder restarts at the new size if it changed. */
    fun onConfigurationChanged() {
        sizeChanged = true
    }

    fun stop() {
        running = false
    }

    /** Blocking; returns when stopped or when writing fails (the exception propagates). */
    fun run(output: OutputStream) {
        val out = DataOutputStream(BufferedOutputStream(output, 64 * 1024))
        out.write(deviceName.toByteArray(Charsets.UTF_8).copyOf(DEVICE_NAME_FIELD))
        out.writeInt(CODEC_H264)
        out.flush()

        var virtualDisplay: VirtualDisplay? = null
        try {
            while (running) {
                val size = captureSize()
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format(size), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                if (virtualDisplay == null) {
                    virtualDisplay = projection.createVirtualDisplay(
                        "DroidLink mirror", size.width, size.height, size.dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
                    )
                } else {
                    virtualDisplay.resize(size.width, size.height, size.dpi)
                    virtualDisplay.surface = surface
                }
                out.writeInt(SESSION_FLAG)
                out.writeInt(size.width)
                out.writeInt(size.height)
                out.flush()
                sizeChanged = false
                try {
                    pump(codec, out, size)
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                    surface.release()
                }
            }
        } finally {
            virtualDisplay?.release()
        }
    }

    /** Drains the encoder until stopped, or returns so [run] can restart at a new size. */
    private fun pump(codec: MediaCodec, out: DataOutputStream, size: Size) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            if (sizeChanged) {
                // Any configuration change sets the flag; restart only if the size really changed.
                sizeChanged = false
                if (captureSize() != size) return
            }
            val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (index < 0) continue
            try {
                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(index) ?: continue
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    var head = if (config) FLAG_CONFIG else info.presentationTimeUs and PTS_MASK
                    if (key) head = head or FLAG_KEY_FRAME
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    out.writeLong(head)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private data class Size(val width: Int, val height: Int, val dpi: Int)

    /** Real screen size scaled so the longer side is at most [maxSize], rounded down to a multiple of 8. */
    @Suppress("DEPRECATION") // getRealMetrics: the non-deprecated API needs a WindowManager per context and API 30
    private fun captureSize(): Size {
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val scale = minOf(1f, maxSize.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels))
        fun fit(px: Int) = ((px * scale).toInt() and 7.inv()).coerceAtLeast(8)
        return Size(fit(metrics.widthPixels), fit(metrics.heightPixels), metrics.densityDpi)
    }

    private fun format(size: Size): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            // Keep producing frames on a still screen so the PC never waits on a stale picture.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }

    private companion object {
        const val DEVICE_NAME_FIELD = 64
        const val CODEC_H264 = 0x68323634
        const val SESSION_FLAG = 1 shl 31
        const val FLAG_CONFIG = 1L shl 62
        const val FLAG_KEY_FRAME = 1L shl 61
        const val PTS_MASK = FLAG_KEY_FRAME - 1
        const val BIT_RATE = 8_000_000
        const val DEQUEUE_TIMEOUT_US = 100_000L
    }
}
