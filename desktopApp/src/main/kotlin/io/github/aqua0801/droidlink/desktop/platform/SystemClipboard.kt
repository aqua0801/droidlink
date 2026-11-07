package io.github.aqua0801.droidlink.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * OS clipboard access. Text only for now.
 * Platform-specific so Linux (X11 / Wayland data-control) can get its own implementation.
 */
interface SystemClipboard {
    /** Invokes [onText] on a background thread whenever the clipboard's text changes. */
    fun startWatching(onText: (String) -> Unit)
    fun stopWatching()
    fun write(text: String)

    companion object {
        fun create(): SystemClipboard {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (isWindows) {
                runCatching { return WindowsClipboard() }
                    .onFailure { System.err.println("DroidLink: Windows clipboard watcher unavailable, polling instead: $it") }
            }
            return PollingClipboard()
        }
    }
}

/** Shared AWT read/write plus a single thread that serializes every clipboard operation. */
abstract class AwtClipboard : SystemClipboard {
    protected val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "droidlink-clipboard").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null

    /** Null when there's no text, or the clipboard is momentarily locked by another app. */
    protected fun readText(): String? = try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            clipboard.getData(DataFlavor.stringFlavor) as? String
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    override fun write(text: String) {
        executor.execute {
            repeat(5) { attempt ->
                try {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                    return@execute
                } catch (_: IllegalStateException) {
                    // Another process holds the clipboard open; back off briefly.
                    Thread.sleep(50L * (attempt + 1))
                }
            }
        }
    }

    protected fun schedule(periodMs: Long, tick: () -> Unit) {
        task?.cancel(false)
        task = executor.scheduleWithFixedDelay(tick, periodMs, periodMs, TimeUnit.MILLISECONDS)
    }

    override fun stopWatching() {
        task?.cancel(false)
        task = null
    }
}

/**
 * Windows: polls the cheap `GetClipboardSequenceNumber` and only reads the content when it
 * changes, so large clipboards aren't re-read and other apps aren't blocked.
 */
class WindowsClipboard : AwtClipboard() {
    @Suppress("FunctionName")
    private interface User32 : Library {
        fun GetClipboardSequenceNumber(): Int
    }

    private val user32: User32 = Native.load("user32", User32::class.java)
    @Volatile
    private var lastSequence = user32.GetClipboardSequenceNumber()

    override fun startWatching(onText: (String) -> Unit) {
        lastSequence = user32.GetClipboardSequenceNumber()
        schedule(250) {
            val sequence = user32.GetClipboardSequenceNumber()
            if (sequence == lastSequence) return@schedule
            // Delayed-rendering sources can be slow; if the read fails, retry on the next tick.
            val text = readText() ?: run {
                if (!hasText()) lastSequence = sequence
                return@schedule
            }
            lastSequence = sequence
            onText(text)
        }
    }

    private fun hasText(): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
    }.getOrDefault(true)
}

/** Fallback for other platforms: compares the text content periodically. */
class PollingClipboard : AwtClipboard() {
    @Volatile
    private var last: String? = null

    override fun startWatching(onText: (String) -> Unit) {
        executor.execute { last = readText() }
        schedule(700) {
            val text = readText() ?: return@schedule
            if (text != last) {
                last = text
                onText(text)
            }
        }
    }
}
