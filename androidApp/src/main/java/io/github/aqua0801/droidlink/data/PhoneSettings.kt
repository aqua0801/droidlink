package io.github.aqua0801.droidlink.data

import android.content.Context
import androidx.core.content.edit
import io.github.aqua0801.droidlink.shared.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhoneSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** A boolean preference exposed as a flow. */
    inner class Toggle(private val key: String, default: Boolean) {
        private val state = MutableStateFlow(prefs.getBoolean(key, default))
        val value: StateFlow<Boolean> = state.asStateFlow()

        fun set(enabled: Boolean) {
            state.value = enabled
            prefs.edit { putBoolean(key, enabled) }
        }
    }

    val clipboardReceive = Toggle("clipboard_receive", true)

    /** "已傳送" toast after sending text from the selection menu, share sheet or tile. */
    val notifyClipboardSent = Toggle("notify_clipboard_sent", true)

    /** Result toast after sharing files to DroidLink from another app. */
    val notifyFilesSent = Toggle("notify_files_sent", true)

    /** Send each new screenshot to the connected PC (needs image read access). */
    val sendScreenshots = Toggle("send_screenshots", true)

    /** System notification when a file from the PC has been saved. */
    val notifyFileReceived = Toggle("notify_file_received", true)

    private val _theme = MutableStateFlow(ThemeMode.parse(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit { putString(KEY_THEME, mode.name) }
    }

    private companion object {
        const val KEY_THEME = "theme"
    }
}
