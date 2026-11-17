package io.github.aqua0801.droidlink.clipboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import io.github.aqua0801.droidlink.graph
import io.github.aqua0801.droidlink.i18n.AppLocales
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible activity that forwards text to the PC. Entry points:
 * - ACTION_PROCESS_TEXT: the "Send to PC" item in other apps' text selection menu
 * - ACTION_SEND (text/plain): the share sheet
 * - [ACTION_SEND_CLIPBOARD]: the Quick Settings tile; reads the clipboard once this
 *   window has focus, which is when Android allows it
 */
class ClipboardSendActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    private var waitingForFocus = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT ->
                deliver(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
            Intent.ACTION_SEND ->
                deliver(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            ACTION_SEND_CLIPBOARD -> {
                waitingForFocus = true
                // Never linger invisibly if focus doesn't arrive (e.g. the screen locked).
                handler.postDelayed({ if (!isFinishing) finish() }, FOCUS_TIMEOUT_MS)
            }
            else -> finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && waitingForFocus) {
            waitingForFocus = false
            deliver(graph.clipboard.readClipboard())
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun deliver(text: String?) {
        val app = applicationContext
        app.graph.scope.launch {
            val result = app.graph.clipboard.send(text)
            // Failures always show; the success toast can be turned off in settings.
            if (result == ClipboardBridge.SendResult.SENT && !app.graph.settings.notifyClipboardSent.value.value) return@launch
            withContext(Dispatchers.Main) {
                Toast.makeText(app, result.message, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        const val ACTION_SEND_CLIPBOARD = "io.github.aqua0801.droidlink.action.SEND_CLIPBOARD"
        private const val FOCUS_TIMEOUT_MS = 3_000L
    }
}
