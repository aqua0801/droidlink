package io.github.aqua0801.droidlink.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhoneSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _clipboardAutoSend = MutableStateFlow(prefs.getBoolean(KEY_CLIP_AUTO_SEND, true))
    /** Send the clipboard whenever DroidLink's own window gains focus (the only time Android lets us read it). */
    val clipboardAutoSend: StateFlow<Boolean> = _clipboardAutoSend.asStateFlow()

    private val _clipboardReceive = MutableStateFlow(prefs.getBoolean(KEY_CLIP_RECEIVE, true))
    val clipboardReceive: StateFlow<Boolean> = _clipboardReceive.asStateFlow()

    fun setClipboardAutoSend(enabled: Boolean) {
        _clipboardAutoSend.value = enabled
        prefs.edit { putBoolean(KEY_CLIP_AUTO_SEND, enabled) }
    }

    fun setClipboardReceive(enabled: Boolean) {
        _clipboardReceive.value = enabled
        prefs.edit { putBoolean(KEY_CLIP_RECEIVE, enabled) }
    }

    private companion object {
        const val KEY_CLIP_AUTO_SEND = "clipboard_auto_send"
        const val KEY_CLIP_RECEIVE = "clipboard_receive"
    }
}
