package io.github.aqua0801.droidlink.data

import android.content.Context
import androidx.core.content.edit
import io.github.aqua0801.droidlink.shared.settings.AppLanguage
import io.github.aqua0801.droidlink.shared.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhoneSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    /** "Sent" toast after sending text from the selection menu, share sheet or tile. */
    val notifyClipboardSent = Toggle("notify_clipboard_sent", true)

    /** Result toast after sharing files to DroidLink from another app. */
    val notifyFilesSent = Toggle("notify_files_sent", true)

    /** Send each new screenshot to the connected PC (needs image read access). */
    val sendScreenshots = Toggle("send_screenshots", true)

    /** System notification when a file from the PC has been saved. */
    val notifyFileReceived = Toggle("notify_file_received", true)

    private val _syncTreeUri = MutableStateFlow(prefs.getString(KEY_SYNC_TREE, null))
    /** Folder picked for the sync mirror (a tree URI with a persisted grant). */
    val syncTreeUri: StateFlow<String?> = _syncTreeUri.asStateFlow()

    fun setSyncTreeUri(uri: String?) {
        _syncTreeUri.value = uri
        prefs.edit { putString(KEY_SYNC_TREE, uri) }
    }

    private val _lastSync = MutableStateFlow(LastSync.decode(prefs.getString(KEY_LAST_SYNC, null)))
    val lastSync: StateFlow<LastSync?> = _lastSync.asStateFlow()

    fun setLastSync(value: LastSync) {
        _lastSync.value = value
        prefs.edit { putString(KEY_LAST_SYNC, value.encode()) }
    }

    private val _theme = MutableStateFlow(ThemeMode.parse(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit { putString(KEY_THEME, mode.name) }
    }

    private val _language = MutableStateFlow(AppLanguage.parse(prefs.getString(KEY_LANGUAGE, null)))
    /** Apply changes through AppLocales, which also updates the platform setting. */
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /** False until a language has been stored (first run). */
    val hasLanguage: Boolean get() = prefs.contains(KEY_LANGUAGE)

    fun setLanguage(language: AppLanguage) {
        _language.value = language
        prefs.edit { putString(KEY_LANGUAGE, language.name) }
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_LANGUAGE = "language"

        /** For contexts created before the app graph (attachBaseContext). */
        fun readLanguage(context: Context): AppLanguage =
            AppLanguage.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null))

        private const val KEY_THEME = "theme"
        private const val KEY_SYNC_TREE = "sync_tree_uri"
        private const val KEY_LAST_SYNC = "sync_last"
    }
}

/** Summary of the most recent sync the PC reported. */
data class LastSync(val at: Long, val updated: Int, val unchanged: Int, val deleted: Int, val failed: Int) {
    fun encode(): String = listOf(at, updated, unchanged, deleted, failed).joinToString(",")

    companion object {
        fun decode(raw: String?): LastSync? {
            val parts = raw?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return null
            if (parts.size != 5) return null
            return LastSync(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt())
        }
    }
}
