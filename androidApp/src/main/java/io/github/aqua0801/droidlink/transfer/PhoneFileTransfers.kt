package io.github.aqua0801.droidlink.transfer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.aqua0801.droidlink.AppGraph
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.shared.transfer.Direction
import io.github.aqua0801.droidlink.shared.transfer.FileSource
import io.github.aqua0801.droidlink.shared.transfer.FileTransfers
import io.github.aqua0801.droidlink.shared.transfer.Transfer
import io.github.aqua0801.droidlink.shared.transfer.TransferStatus
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Phone side of file transfer: sending content URIs, saving incoming files, notifications. */
class PhoneFileTransfers(private val context: Context, private val graph: AppGraph) {
    val engine = FileTransfers(graph.scope, MediaStoreIncomingFiles(context))

    /** Streams opened up front for pending sends; closed when each transfer ends. */
    private val openStreams = ConcurrentHashMap<Long, InputStream>()

    data class SendSummary(val started: Int, val unreadable: Int, val notConnected: Boolean) {
        /** Everything was queued; nothing for the user to act on. */
        val allStarted: Boolean get() = !notConnected && started > 0 && unreadable == 0

        fun message(context: Context): String = when {
            notConnected -> context.getString(R.string.files_not_connected)
            started == 0 -> context.getString(R.string.files_unreadable_all)
            unreadable > 0 -> context.resources.getQuantityString(R.plurals.files_sending, started, started) +
                context.getString(R.string.files_unreadable_some, unreadable)
            else -> context.resources.getQuantityString(R.plurals.files_sending, started, started)
        }
    }

    init {
        graph.scope.launch {
            engine.finished.collect { t ->
                openStreams.remove(t.id)?.let { runCatching { it.close() } }
                if (t.direction == Direction.RECEIVE && t.status == TransferStatus.DONE &&
                    graph.settings.notifyFileReceived.value.value
                ) {
                    notifyReceived(t)
                }
            }
        }
    }

    /**
     * Sends [uris] to the connected PC. Blocking (queries the providers): call off the main
     * thread, and while the URI read grants are still valid (before a sharing activity
     * finishes). Each stream is opened here because an open descriptor stays readable after
     * the grant lapses, and the actual read only happens once the PC accepts.
     */
    fun send(uris: List<Uri>): SendSummary {
        val session = graph.link.session.value ?: return SendSummary(0, 0, notConnected = true)
        var started = 0
        var unreadable = 0
        for (uri in uris) {
            val meta = runCatching { describe(uri) }.getOrNull()
            val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            if (meta == null || stream == null) {
                runCatching { stream?.close() }
                unreadable++
                continue
            }
            val id = engine.send(session, FileSource(meta.name, meta.size, meta.mime) { stream })
            openStreams[id] = stream
            // If it already ended (e.g. the link dropped), the finished event may have come first.
            if (engine.transfers.value.firstOrNull { it.id == id }?.isActive == false) {
                openStreams.remove(id)?.let { runCatching { it.close() } }
            }
            started++
        }
        return SendSummary(started, unreadable, notConnected = false)
    }

    /** An intent that opens a received file, or null if it isn't one. */
    fun viewIntent(t: Transfer): Intent? {
        if (t.direction != Direction.RECEIVE || t.status != TransferStatus.DONE) return null
        val uri = t.location?.toUri() ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private class Meta(val name: String, val size: Long, val mime: String?)

    private fun describe(uri: Uri): Meta? {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        if (size < 0) {
            size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        }
        if (size < 0) return null // the protocol needs the size up front
        return Meta(name ?: uri.lastPathSegment ?: "file", size, resolver.getType(uri))
    }

    private fun notifyReceived(t: Transfer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_files), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = viewIntent(t)?.let {
            PendingIntent.getActivity(context, t.id.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.files_received_title))
            .setContentText(context.getString(R.string.files_received_body, t.name, t.peerName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(t.id.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "files"
    }
}
