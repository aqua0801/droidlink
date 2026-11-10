package io.github.aqua0801.droidlink.data

import android.content.Context
import androidx.core.content.edit
import io.github.aqua0801.droidlink.shared.settings.ThemeMode
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

    private val _theme = MutableStateFlow(ThemeMode.parse(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit { putString(KEY_THEME, mode.name) }
    }

    private companion object {
        const val KEY_CLIP_RECEIVE = "clipboard_receive"
        const val KEY_THEME = "theme"
    }
}
