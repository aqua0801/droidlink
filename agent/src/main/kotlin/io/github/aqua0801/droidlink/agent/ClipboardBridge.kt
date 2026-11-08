package io.github.aqua0801.droidlink.agent

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Keeps the clipboard in sync. Uses clipboard change callbacks when available, with polling
 * as a safety net or fallback. [apply] writes incoming text and remembers it so the worker
 * does not echo it back.
 */
internal class ClipboardBridge(private val onChange: (String) -> Unit) {
    private val service = ClipboardService.create()
    private val stateLock = Any()
    private val wakeSignals = LinkedBlockingQueue<Unit>(1)

    private var lastSeen: String? = null
    private var listener: Any? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Returns false if clipboard access is not available on this device. */
    @Synchronized
    fun start(): Boolean {
        val svc = service ?: return false
        if (running) return true

        synchronized(stateLock) {
            lastSeen = svc.getText()
        }

        wakeSignals.clear()
        running = true

        val registeredListener = svc
            .createListener { wakeSignals.offer(Unit) }
            ?.takeIf { svc.addListener(it) }
        listener = registeredListener
        val intervalMs = if (registeredListener != null) SAFETY_POLL_MS else FALLBACK_POLL_MS

        thread = Thread {
            while (running) {
                checkForChange(svc)

                try {
                    wakeSignals.poll(intervalMs, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "clipboard-sync"
            start()
        }

        return true
    }

    fun apply(text: String) {
        val svc = service ?: return

        synchronized(stateLock) {
            // Update lastSeen only after a successful write, while the worker is locked out.
            if (svc.setText(text)) {
                lastSeen = text
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false

        val svc = service
        val currentListener = listener
        if (svc != null && currentListener != null) {
            svc.removeListener(currentListener)
        }
        listener = null

        thread?.interrupt()
        thread = null
        wakeSignals.clear()
    }

    private fun checkForChange(svc: ClipboardService) {
        val changedText = synchronized(stateLock) {
            val text = svc.getText()
            if (text != null && text != lastSeen) {
                lastSeen = text
                text
            } else {
                null
            }
        } ?: return

        // Call out after releasing the lock so slow I/O cannot block apply().
        runCatching { onChange(changedText) }
    }

    private companion object {
        const val FALLBACK_POLL_MS = 750L
        const val SAFETY_POLL_MS = 5_000L
    }
}