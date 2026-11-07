package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.platform.SystemClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardSyncTest {
    /** Behaves like the OS: every write is reported back to the watcher. */
    private class FakeClipboard : SystemClipboard {
        var watcher: ((String) -> Unit)? = null
        val written = mutableListOf<String>()

        override fun startWatching(onText: (String) -> Unit) {
            watcher = onText
        }

        override fun stopWatching() {
            watcher = null
        }

        override fun write(text: String) {
            written += text
            watcher?.invoke(text)
        }

        fun userCopies(text: String) = watcher?.invoke(text)
    }

    @Test
    fun forwardsLocalCopiesButNotEchoesOfRemoteText() {
        val clipboard = FakeClipboard()
        val forwarded = mutableListOf<String>()
        val sync = ClipboardSync(clipboard, CoroutineScope(Dispatchers.Unconfined)) { forwarded += it; true }
        sync.start()

        clipboard.userCopies("hello")
        assertEquals(listOf("hello"), forwarded)
        assertEquals("hello", sync.lastSent.value?.text)

        // Text from the phone lands on the PC clipboard without bouncing back.
        sync.applyRemote("from phone")
        assertEquals(listOf("from phone"), clipboard.written)
        assertEquals(listOf("hello"), forwarded)
        assertEquals("from phone", sync.lastReceived.value?.text)

        // Copying the same text again counts as unchanged; something new is forwarded.
        clipboard.userCopies("from phone")
        clipboard.userCopies("next")
        assertEquals(listOf("hello", "next"), forwarded)
    }

    @Test
    fun undeliveredTextIsNotRecordedAsSent() {
        val clipboard = FakeClipboard()
        val sync = ClipboardSync(clipboard, CoroutineScope(Dispatchers.Unconfined)) { false }
        sync.start()
        clipboard.userCopies("nobody connected")
        assertNull(sync.lastSent.value)
    }
}
