package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.platform.SystemClipboard
import io.github.aqua0801.droidlink.shared.DroidLinkDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ClipEvent(val text: String, val at: Long = System.currentTimeMillis())

/**
 * Bridges the PC clipboard and connected phones, suppressing echoes: text we just
 * received is remembered so the watcher doesn't bounce it straight back.
 */
class ClipboardSync(
    private val clipboard: SystemClipboard,
    private val scope: CoroutineScope,
    private val onLocalText: suspend (String) -> Boolean,
) {
    @Volatile
    private var lastKnown: String? = null

    private val _lastSent = MutableStateFlow<ClipEvent?>(null)
    val lastSent: StateFlow<ClipEvent?> = _lastSent.asStateFlow()

    private val _lastReceived = MutableStateFlow<ClipEvent?>(null)
    val lastReceived: StateFlow<ClipEvent?> = _lastReceived.asStateFlow()

    fun start() {
        clipboard.startWatching { text ->
            if (text == lastKnown) return@startWatching
            lastKnown = text
            if (text.isEmpty() || text.length > DroidLinkDefaults.CLIPBOARD_MAX_CHARS) return@startWatching
            scope.launch {
                if (onLocalText(text)) _lastSent.value = ClipEvent(text)
            }
        }
    }

    fun stop() = clipboard.stopWatching()

    fun applyRemote(text: String) {
        lastKnown = text
        clipboard.write(text)
        _lastReceived.value = ClipEvent(text)
    }
}
