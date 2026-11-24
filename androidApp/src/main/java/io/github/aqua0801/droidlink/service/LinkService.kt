package io.github.aqua0801.droidlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.battery.toBatteryStatus
import io.github.aqua0801.droidlink.graph
import io.github.aqua0801.droidlink.i18n.AppLocales
import io.github.aqua0801.droidlink.link.LinkState
import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.ui.MainActivity
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while linked (vivo/OriginOS kills background apps aggressively)
 * and hosts the features that run while the UI is closed: battery reporting and
 * screenshot forwarding.
 */
class LinkService : LifecycleService() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    private val battery = MutableStateFlow<BatteryStatus?>(null)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            battery.value = intent.toBatteryStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notif_preparing)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { sticky -> battery.value = sticky.toBatteryStatus() }

        graph.nsd.start()
        graph.screenshots.start(lifecycleScope)

        lifecycleScope.launch {
            graph.link.state.collect { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(describe(state), actionFor(state)))
            }
        }

        // Send the battery state whenever it changes, and once more for every new session.
        lifecycleScope.launch {
            combine(graph.link.session, battery.filterNotNull().distinctUntilChanged()) { session, status ->
                session to status
            }.collect { (session, status) ->
                if (session != null) {
                    try {
                        session.send(status)
                    } catch (_: IOException) {
                        // The link manager notices the broken session and reconnects.
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when {
            // A null intent means the system restarted us after killing the process.
            intent == null -> graph.link.resumeIfNeeded()
            intent.action == ACTION_RETRY -> graph.link.retryIfGaveUp()
            // Stops this service too.
            intent.action == ACTION_DISCONNECT -> graph.link.disconnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        graph.nsd.stop()
        graph.screenshots.stop()
        super.onDestroy()
    }

    private fun describe(state: LinkState): String = when (state) {
        LinkState.Idle -> getString(R.string.status_idle)
        is LinkState.Pairing -> getString(R.string.status_pairing, state.serverName)
        is LinkState.Connecting -> getString(R.string.status_connecting, state.pcName)
        is LinkState.Connected -> getString(R.string.status_connected, state.pcName)
        is LinkState.Disconnected -> getString(R.string.notif_gave_up, state.pcName)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_link), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** The notification button for each state: disconnect while connected, reconnect after giving up. */
    private enum class NotificationAction { NONE, RETRY, DISCONNECT }

    private fun actionFor(state: LinkState) = when (state) {
        is LinkState.Connected -> NotificationAction.DISCONNECT
        is LinkState.Disconnected -> NotificationAction.RETRY
        else -> NotificationAction.NONE
    }

    /**
     * Tapping the body opens the app; after giving up, that also retries (MainActivity.onResume),
     * so either way a tap reconnects.
     */
    private fun buildNotification(text: String, action: NotificationAction = NotificationAction.NONE): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
        when (action) {
            NotificationAction.RETRY -> builder.addAction(0, getString(R.string.action_reconnect), serviceIntent(1, ACTION_RETRY))
            NotificationAction.DISCONNECT -> builder.addAction(0, getString(R.string.action_disconnect), serviceIntent(2, ACTION_DISCONNECT))
            NotificationAction.NONE -> Unit
        }
        return builder.build()
    }

    private fun serviceIntent(requestCode: Int, action: String): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, LinkService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL_ID = "link"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_RETRY = "io.github.aqua0801.droidlink.action.RETRY_LINK"
        private const val ACTION_DISCONNECT = "io.github.aqua0801.droidlink.action.DISCONNECT_LINK"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }
}
