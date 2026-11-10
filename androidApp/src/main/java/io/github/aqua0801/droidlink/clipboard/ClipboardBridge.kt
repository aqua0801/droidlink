package io.github.aqua0801.droidlink.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import io.github.aqua0801.droidlink.AppGraph
import io.github.aqua0801.droidlink.R
import io.github.aqua0801.droidlink.shared.DroidLinkDefaults
import io.github.aqua0801.droidlink.shared.protocol.ClipboardText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

data class ClipEvent(val text: String, val at: Long = System.currentTimeMillis())

/**
 * Basic-mode clipboard: the phone can always *write* the clipboard, but since Android 10 it
 * can only *read* it while one of our windows has focus. So PC → phone is automatic and
 * phone → PC is explicit (text selection menu, share sheet, QS tile, or opening the app).
 */
class ClipboardBridge(private val context: Context, private val graph: AppGraph) {
    enum class SendResult(@param:StringRes val message: Int) {
        SENT(R.string.clip_sent),
        NOT_CONNECTED(R.string.clip_not_connected),
        EMPTY(R.string.clip_empty),
        TOO_LARGE(R.string.clip_too_large),
        FAILED(R.string.clip_failed),
    }

    private val manager = context.getSystemService(ClipboardManager::class.java)

    private val _lastSent = MutableStateFlow<ClipEvent?>(null)
    val lastSent: StateFlow<ClipEvent?> = _lastSent.asStateFlow()

    private val _lastReceived = MutableStateFlow<ClipEvent?>(null)
    val lastReceived: StateFlow<ClipEvent?> = _lastReceived.asStateFlow()

    suspend fun applyRemote(text: String) {
        if (!graph.settings.clipboardReceive.value) return
        withContext(Dispatchers.Main) {
            manager.setPrimaryClip(ClipData.newPlainText("DroidLink", text))
        }
        _lastReceived.value = ClipEvent(text)
    }

    /** Only returns content while a DroidLink window has input focus; null otherwise. */
    fun readClipboard(): String? =
        manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()

    suspend fun send(text: String?): SendResult {
        if (text.isNullOrEmpty()) return SendResult.EMPTY
        if (text.length > DroidLinkDefaults.CLIPBOARD_MAX_CHARS) return SendResult.TOO_LARGE
        val session = graph.link.session.value ?: return SendResult.NOT_CONNECTED
        return try {
            session.send(ClipboardText(text))
            _lastSent.value = ClipEvent(text)
            SendResult.SENT
        } catch (_: IOException) {
            SendResult.FAILED
        }
    }
}
