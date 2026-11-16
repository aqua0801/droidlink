package io.github.aqua0801.droidlink.desktop.mirror

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.DoublePointer

/**
 * Software decoder on FFmpeg's avcodec, tuned for latency: low-delay flag, slice threads
 * only (frame threads would hold frames back), output converted to BGRA with
 * sws_scale_frame (the frame-based API; no hand-built plane pointer arrays).
 * Config packets are held and prepended to the next frame, like the scrcpy client does.
 */
class FfmpegVideoDecoder(codec: VideoCodec) : VideoDecoder {
    private val context: AVCodecContext
    private val packet = avcodec.av_packet_alloc()
    private val frame = avutil.av_frame_alloc()
    private var scaler: SwsContext? = null
    /** Reused BGRA destination, reallocated only when the size changes (rotation). */
    private val bgra = avutil.av_frame_alloc()
    private var pendingConfig: ByteArray? = null

    init {
        val id = when (codec) {
            VideoCodec.H264 -> avcodec.AV_CODEC_ID_H264
            VideoCodec.H265 -> avcodec.AV_CODEC_ID_HEVC
            VideoCodec.AV1 -> avcodec.AV_CODEC_ID_AV1
        }
        val decoder = avcodec.avcodec_find_decoder(id) ?: throw IllegalStateException("no FFmpeg decoder for $codec")
        context = avcodec.avcodec_alloc_context3(decoder)
        context.flags(context.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
        context.thread_type(FF_THREAD_SLICE)
        val result = avcodec.avcodec_open2(context, decoder, null as AVDictionary?)
        check(result >= 0) { "avcodec_open2 failed ($result)" }
    }

    override fun decode(packet: StreamItem.Packet): DecodedFrame? {
        if (packet.config) {
            pendingConfig = packet.data
            return null
        }
        val config = pendingConfig
        pendingConfig = null
        val data = if (config != null) config + packet.data else packet.data

        if (avcodec.av_new_packet(this.packet, data.size) < 0) return null
        this.packet.data().put(data, 0, data.size)
        this.packet.pts(packet.ptsUs)
        val sent = avcodec.avcodec_send_packet(context, this.packet)
        avcodec.av_packet_unref(this.packet)
        if (sent < 0) return null

        // With low delay and no frame threads, one packet yields at most one frame.
        if (avcodec.avcodec_receive_frame(context, frame) < 0) return null
        return convert(packet.ptsUs)
    }

    private fun convert(ptsUs: Long): DecodedFrame {
        val width = frame.width()
        val height = frame.height()
        if (bgra.width() != width || bgra.height() != height) {
            avutil.av_frame_unref(bgra)
            bgra.format(avutil.AV_PIX_FMT_BGRA)
            bgra.width(width)
            bgra.height(height)
            check(avutil.av_frame_get_buffer(bgra, 0) >= 0) { "av_frame_get_buffer failed" }
            scaler?.let(swscale::sws_freeContext)
            scaler = swscale.sws_getContext(
                width, height, frame.format(),
                width, height, avutil.AV_PIX_FMT_BGRA,
                swscale.SWS_POINT, null, null, null as DoublePointer?,
            ) ?: error("sws_getContext failed")
        }
        val result = swscale.sws_scale_frame(scaler, bgra, frame)
        check(result >= 0) { "sws_scale_frame failed ($result)" }

        // Rows in the AVFrame may be padded; copy them into a tight width * 4 layout.
        val rowBytes = width * 4
        val stride = bgra.linesize(0)
        val src = bgra.data(0)
        val pixels = ByteArray(rowBytes * height)
        if (stride == rowBytes) {
            src.position(0).get(pixels)
        } else {
            for (row in 0 until height) src.position(row.toLong() * stride).get(pixels, row * rowBytes, rowBytes)
        }
        src.position(0)
        return DecodedFrame(width, height, pixels, ptsUs)
    }

    private companion object {
        /** avcodec.h FF_THREAD_SLICE; the macro is not exposed by the Java bindings. */
        const val FF_THREAD_SLICE = 2
    }

    override fun close() {
        scaler?.let(swscale::sws_freeContext)
        scaler = null
        avutil.av_frame_free(bgra)
        avutil.av_frame_free(frame)
        avcodec.av_packet_free(packet)
        avcodec.avcodec_free_context(context)
    }
}
