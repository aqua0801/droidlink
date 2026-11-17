package io.github.aqua0801.droidlink.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.graph
import io.github.aqua0801.droidlink.i18n.AppLocales
import io.github.aqua0801.droidlink.shared.protocol.MirrorStreamHello
import kotlinx.coroutines.runBlocking

/**
 * Foreground service (type mediaProjection) that owns one screen-capture session: opens the
 * stream connection to the PC and runs [ScreenStreamer] on its own thread. Ends when the PC
 * closes the stream, the user taps "stop", or the system revokes the projection.
 */
class MirrorCaptureService : Service() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    private var projection: MediaProjection? = null
    private var streamer: ScreenStreamer? = null
    private var worker: Thread? = null
    private var requestId = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val target = intent.getLongExtra(EXTRA_REQUEST_ID, 0)
            if (target == 0L || target == requestId) finishSession(notifyPc = target == 0L)
            return START_NOT_STICKY
        }
        if (intent == null || worker != null) return START_NOT_STICKY

        requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0)
        val pcName = intent.getStringExtra(EXTRA_PC_NAME).orEmpty()
        // The foreground type must be in place before the projection is created (Android 14+).
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(pcName),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = data?.let { manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), it) }
        if (projection == null) {
            finishSession(notifyPc = true)
            return START_NOT_STICKY
        }
        this.projection = projection
        // Required before createVirtualDisplay on Android 14+.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { finishSession(notifyPc = true) }
            }
        }, mainHandler)

        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val streamer = ScreenStreamer(projection, display, graph.deviceName, intent.getIntExtra(EXTRA_MAX_SIZE, 1600))
        this.streamer = streamer
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        worker = Thread({
            try {
                val socket = runBlocking { graph.link.openStream(MirrorStreamHello(token, graph.deviceName)) }
                socket.use { streamer.run(it.outputStream) }
            } catch (_: Exception) {
                // Closed by the PC, network loss, or the projection stopped: all end the session.
            }
            mainHandler.post { finishSession(notifyPc = true) }
        }, "mirror-stream").apply { start() }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        streamer?.onConfigurationChanged()
    }

    private fun finishSession(notifyPc: Boolean) {
        if (projection == null && worker == null) {
            stopSelf()
            return
        }
        streamer?.stop()
        streamer = null
        projection?.stop()
        projection = null
        worker = null
        if (notifyPc) graph.mirror.stopped(requestId)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        streamer?.stop()
        projection?.stop()
        super.onDestroy()
    }

    private fun notification(pcName: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVE, getString(R.string.mirror_channel_active), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MirrorCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ACTIVE)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle(getString(R.string.mirror_active, pcName))
            .setOngoing(true)
            .addAction(0, getString(R.string.mirror_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "io.github.aqua0801.droidlink.action.STOP_MIRROR"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_MAX_SIZE = "max_size"
        private const val EXTRA_PC_NAME = "pc_name"
        private const val CHANNEL_ACTIVE = "mirror_active"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context, resultCode: Int, data: Intent, requestId: Long, token: String, maxSize: Int, pcName: String) {
            val intent = Intent(context, MirrorCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_MAX_SIZE, maxSize)
                .putExtra(EXTRA_PC_NAME, pcName)
            context.startForegroundService(intent)
        }

        /** Stops the session for [requestId] (from the PC); does nothing if another one is running. */
        fun stop(context: Context, requestId: Long) {
            val intent = Intent(context, MirrorCaptureService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_REQUEST_ID, requestId)
            // startService, not startForegroundService: a stop must not oblige a startForeground call.
            runCatching { context.startService(intent) }
        }
    }
}
