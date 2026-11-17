package io.github.aqua0801.droidlink.mirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.aqua0801.droidlink.AppGraph
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.shared.protocol.MirrorDeclined
import io.github.aqua0801.droidlink.shared.protocol.MirrorRequest
import io.github.aqua0801.droidlink.shared.protocol.MirrorStop
import io.github.aqua0801.droidlink.shared.protocol.Message
import kotlinx.coroutines.launch

/**
 * Phone side of wireless mirroring. A PC request becomes a notification, because apps may
 * not open activities from the background; tapping it shows the system screen-capture
 * consent dialog (MirrorConsentActivity), and on approval MirrorCaptureService streams.
 */
class PhoneMirror(private val context: Context, private val graph: AppGraph) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun onRequest(pcName: String, request: MirrorRequest) {
        ensureChannel()
        val open = Intent(context, MirrorConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MirrorConsentActivity.EXTRA_REQUEST_ID, request.requestId)
            .putExtra(MirrorConsentActivity.EXTRA_TOKEN, request.token)
            .putExtra(MirrorConsentActivity.EXTRA_MAX_SIZE, request.maxSize)
            .putExtra(MirrorConsentActivity.EXTRA_PC_NAME, pcName)
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REQUEST)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle(context.getString(R.string.mirror_request_title, pcName))
            .setContentText(context.getString(R.string.mirror_request_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(REQUEST_TIMEOUT_MS)
            .build()
        notifications.notify(REQUEST_NOTIFICATION_ID, notification)
    }

    /** The PC stopped (or gave up on) the request. */
    fun onStop(stop: MirrorStop) {
        notifications.cancel(REQUEST_NOTIFICATION_ID)
        MirrorCaptureService.stop(context, stop.requestId)
    }

    fun clearRequestNotification() {
        notifications.cancel(REQUEST_NOTIFICATION_ID)
    }

    fun declined(requestId: Long) = sendToPc(MirrorDeclined(requestId))

    fun stopped(requestId: Long) = sendToPc(MirrorStop(requestId))

    private fun sendToPc(message: Message) {
        val session = graph.link.session.value ?: return
        graph.scope.launch { runCatching { session.send(message) } }
    }

    private fun ensureChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_REQUEST, context.getString(R.string.mirror_channel_request), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_REQUEST = "mirror_request"
        private const val REQUEST_NOTIFICATION_ID = 40
        /** Matches the PC's wait for an answer. */
        const val REQUEST_TIMEOUT_MS = 90_000L
    }
}
