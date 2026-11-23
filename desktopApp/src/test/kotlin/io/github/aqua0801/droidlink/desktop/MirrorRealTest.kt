package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.adb.Adb
import io.github.aqua0801.droidlink.desktop.mirror.MirrorController
import io.github.aqua0801.droidlink.desktop.mirror.MirrorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.toAwtImage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end mirror check against a real device: push scrcpy-server, stream, decode.
 * Opt-in (needs a phone on adb): ./gradlew :desktopApp:test -PrealMirror=true --tests "*MirrorRealTest*"
 */
class MirrorRealTest {
    @Test
    fun decodesFramesFromTheDevice() {
        if (System.getProperty("droidlink.realMirror") != "true") return
        val serial = Adb.run("devices").output.lines()
            .map { it.split('\t') }
            .firstOrNull { it.size == 2 && it[1] == "device" && !it[0].startsWith("emulator") }
            ?.get(0) ?: error("no physical device on adb")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mirror = MirrorController(scope, kotlin.io.path.createTempDirectory("mirror").toFile()) { MirrorSettings() }
        try {
            runBlocking {
                mirror.startAdb(serial)
                val first = withTimeoutOrNull(20_000) { mirror.frame.filterNotNull().first() }
                    ?: error("no frame; state=${mirror.state.value}")
                println("first frame ${first.width}x${first.height}, state=${mirror.state.value}")
                kotlinx.coroutines.delay(1_500)
                val img = mirror.frame.value!!.toAwtImage()
                var sum = 0L; var n = 0; var nonBlack = 0
                for (y in 0 until img.height step 16) for (x in 0 until img.width step 16) {
                    val rgb = img.getRGB(x, y)
                    val v = ((rgb shr 16) and 255) + ((rgb shr 8) and 255) + (rgb and 255)
                    sum += v; n++; if (v > 30) nonBlack++
                }
                println("brightness avg=${sum / n / 3} nonBlack=$nonBlack/$n argb0=%08x".format(img.getRGB(img.width / 2, img.height / 2)))

                var frames = 0
                val counter = scope.launch { mirror.frame.collect { frames++ } }
                kotlinx.coroutines.delay(3_000)
                counter.cancel()
                println("frames in 3 s: $frames (static screens produce few frames; that is expected)")
                assertTrue(mirror.state.value is MirrorState.Running, "state: ${mirror.state.value}")
            }
        } finally {
            mirror.stop()
            scope.cancel()
        }
    }
}
