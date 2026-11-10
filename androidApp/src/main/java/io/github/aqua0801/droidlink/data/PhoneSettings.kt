package io.github.aqua0801.droidlink.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhoneSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _clipboardReceive = MutableStateFlow(prefs.getBoolean(KEY_CLIP_RECEIVE, true))
    val clipboardReceive: StateFlow<Boolean> = _clipboardReceive.asStateFlow()

    fun setClipboardReceive(enabled: Boolean) {
        _clipboardReceive.value = enabled
        prefs.edit { putBoolean(KEY_CLIP_RECEIVE, enabled) }
    }

    private companion object {
        const val KEY_CLIP_RECEIVE = "clipboard_receive"
    }
}
