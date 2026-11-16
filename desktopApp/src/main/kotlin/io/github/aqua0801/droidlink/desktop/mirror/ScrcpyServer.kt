package io.github.aqua0801.droidlink.desktop.mirror

import java.io.File

/**
 * The bundled scrcpy-server (Apache-2.0, unmodified; see THIRD_PARTY_NOTICES.md). [VERSION]
 * must match the jar exactly, or the server refuses to start.
 *
 * Launch flow (same shape as the enhanced-mode agent):
 *   adb push <jar> REMOTE_JAR
 *   adb forward tcp:0 localabstract:scrcpy_<scid>
 *   adb shell CLASSPATH=REMOTE_JAR app_process / com.genymobile.scrcpy.Server VERSION <options>
 *   connect to the forwarded port: first the video socket (then control, in stage 2)
 */
object ScrcpyServer {
    const val VERSION = "5.0.1"
    const val REMOTE_JAR = "/data/local/tmp/droidlink-scrcpy-server.jar"
    const val MAIN_CLASS = "com.genymobile.scrcpy.Server"
    private const val RESOURCE = "/scrcpy/scrcpy-server.jar"

    data class Options(
        /** 31-bit id that names the abstract socket, so several sessions do not collide. */
        val scid: Int,
        val maxSize: Int = 1600,
        val maxFps: Int = 60,
        val videoBitRate: Int = 8_000_000,
        val codec: VideoCodec = VideoCodec.H264,
        /** Stage 2 turns this on (second socket for input events). */
        val control: Boolean = false,
    ) {
        val socketName: String get() = "scrcpy_%08x".format(scid)

        fun toArgs(): List<String> = listOf(
            "scid=%08x".format(scid),
            "log_level=info",
            "tunnel_forward=true",
            "audio=false",
            "control=$control",
            "cleanup=true",
            // DroidLink has its own clipboard sync; keep the server's out of the way.
            "clipboard_autosync=false",
            "video_codec=${codec.name.lowercase()}",
            "max_size=$maxSize",
            "max_fps=$maxFps",
            "video_bit_rate=$videoBitRate",
        )
    }

    fun shellArgs(options: Options): List<String> =
        listOf("CLASSPATH=$REMOTE_JAR", "app_process", "/", MAIN_CLASS, VERSION) + options.toArgs()

    @Volatile
    private var extracted: File? = null

    /** Copies the bundled jar to a temp file so adb can push it; null if it is not bundled. */
    fun jarFile(): File? {
        extracted?.takeIf { it.isFile }?.let { return it }
        val stream = ScrcpyServer::class.java.getResourceAsStream(RESOURCE) ?: return null
        val out = File.createTempFile("droidlink-scrcpy-server", ".jar").apply { deleteOnExit() }
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        extracted = out
        return out
    }
}
