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
import io.github.aqua0801.droidlink.battery.toBatteryStatus
import io.github.aqua0801.droidlink.graph
import io.github.aqua0801.droidlink.link.LinkState
import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Keeps the process alive while linked (vivo/OriginOS kills background apps aggressively)
 * and hosts the features that run while the UI is closed. For now: battery reporting.
 */
class LinkService : LifecycleService() {
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
            buildNotification("準備中…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { sticky -> battery.value = sticky.toBatteryStatus() }

        graph.nsd.start()

        lifecycleScope.launch {
            graph.link.state.collect { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(describe(state)))
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
        // A null intent means the system restarted us after killing the process.
        if (intent == null) graph.link.resumeIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        graph.nsd.stop()
        super.onDestroy()
    }

    private fun describe(state: LinkState): String = when (state) {
        LinkState.Idle -> "未連線"
        is LinkState.Pairing -> "正在與 ${state.serverName} 配對…"
        is LinkState.Connecting -> "正在連線到 ${state.pcName}…"
        is LinkState.Connected -> "已連線到 ${state.pcName}"
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "連線狀態", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DroidLink")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "link"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }
}
