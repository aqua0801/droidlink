package io.github.aqua0801.droidlink.screenshot

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import io.github.aqua0801.droidlink.AppGraph
import io.github.aqua0801.droidlink.shared.protocol.FileKind
import io.github.aqua0801.droidlink.shared.transfer.FileSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

/**
 * Sends each new screenshot to the connected PC.
 *
 * Listens for MediaStore image changes and looks at images added in the last couple of
 * minutes. Screenshots taken while no PC is connected are skipped, not queued, so a
 * reconnect never floods the PC with old images. MediaStore hides entries that are still
 * being written (IS_PENDING), so an image only shows up here once it is complete.
 */
class ScreenshotWatcher(private val context: Context, private val graph: AppGraph) {
    private val resolver = context.contentResolver
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var startedAtSec = 0L

    /** MediaStore ids already handled, with their DATE_ADDED, pruned as they age out. */
    private val handled = HashMap<Long, Long>()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            changes.trySend(Unit)
        }
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        startedAtSec = System.currentTimeMillis() / 1000
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        job = scope.launch(Dispatchers.IO) {
            while (true) {
                changes.receive()
                // Changes arrive in bursts while the screenshot is saved; let them settle.
                delay(SETTLE_MS)
                runCatching { scan() }
            }
        }
    }

    fun stop() {
        resolver.unregisterContentObserver(observer)
        job?.cancel()
        job = null
    }

    private fun scan() {
        if (!graph.settings.sendScreenshots.value.value || !ScreenshotAccess.granted(context)) return
        val nowSec = System.currentTimeMillis() / 1000
        val since = maxOf(startedAtSec, nowSec - WINDOW_SEC)
        handled.entries.removeAll { it.value < since }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
        )
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DATE_ADDED} >= ?",
            arrayOf(since.toString()),
            "${MediaStore.Images.Media._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in handled) continue
                val name = c.getString(1) ?: continue
                val size = c.getLong(2)
                if (size <= 0) continue // not finished yet; seen again on the next change
                handled[id] = c.getLong(5)
                if (isScreenshot(c.getString(4).orEmpty(), name)) send(id, name, size, c.getString(3))
            }
        }
    }

    private fun send(id: Long, name: String, size: Long, mime: String?) {
        val session = graph.link.session.value ?: return
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        graph.files.engine.send(
            session,
            FileSource(name, size, mime, FileKind.SCREENSHOT) {
                resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
            },
        )
    }

    /** Matches the usual screenshot folders and names, but never files DroidLink itself saved. */
    private fun isScreenshot(relativePath: String, name: String): Boolean {
        if (relativePath.startsWith(OWN_DOWNLOADS, ignoreCase = true)) return false
        return relativePath.contains("screenshot", ignoreCase = true) || name.startsWith("screenshot", ignoreCase = true)
    }

    private companion object {
        const val SETTLE_MS = 600L
        const val WINDOW_SEC = 120L
        val OWN_DOWNLOADS = Environment.DIRECTORY_DOWNLOADS + File.separator + "DroidLink"
    }
}
