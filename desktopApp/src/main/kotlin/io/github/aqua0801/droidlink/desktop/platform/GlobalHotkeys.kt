package io.github.aqua0801.droidlink.desktop.platform

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import io.github.aqua0801.droidlink.desktop.hotkey.Hotkey
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * System-wide keyboard shortcuts: RegisterHotKey on Windows, XGrabKey on X11 sessions
 * ([X11GlobalHotkeys]). Wayland sessions report unsupported: compositors don't let apps grab
 * keys (the GlobalShortcuts portal could be added here later).
 */
interface GlobalHotkeys : Closeable {
    val isSupported: Boolean

    /** Replaces all registrations with [bindings] (id -> hotkey). Returns the ids that failed. */
    fun setBindings(bindings: Map<Int, Hotkey>): Set<Int>

    companion object {
        /**
         * Some Wayland compositors can forward keys to X11 apps (KDE: "Legacy X11 App Support"),
         * so DROIDLINK_X11_HOTKEYS=1 opts in to X11 grabs on Wayland.
         */
        private val forceX11 = System.getenv("DROIDLINK_X11_HOTKEYS") == "1"

        /** True when hotkeys are off only because the Wayland session blocks them. */
        val blockedByWayland: Boolean get() = Os.isWayland && !forceX11

        /** [onPressed] is called with the binding id, on a background thread. */
        fun create(onPressed: (Int) -> Unit): GlobalHotkeys {
            val created = runCatching {
                when {
                    Os.isWindows -> WindowsGlobalHotkeys(onPressed)
                    Os.isLinux && System.getenv("DISPLAY") != null && (!Os.isWayland || forceX11) -> X11GlobalHotkeys(onPressed)
                    else -> null
                }
            }.onFailure { System.err.println("DroidLink: global hotkeys unavailable: $it") }
            return created.getOrNull() ?: UnsupportedGlobalHotkeys
        }
    }
}

object UnsupportedGlobalHotkeys : GlobalHotkeys {
    override val isSupported = false
    override fun setBindings(bindings: Map<Int, Hotkey>): Set<Int> = bindings.keys
    override fun close() = Unit
}

/**
 * Uses the Win32 RegisterHotKey API: the OS delivers WM_HOTKEY for the registered combination
 * to this thread. No keyboard hook is installed, so nothing else the user types is observed.
 * Hotkeys belong to the registering thread, so registration runs on the message-loop thread.
 */
class WindowsGlobalHotkeys(private val onPressed: (Int) -> Unit) : GlobalHotkeys {
    override val isSupported = true

    private val user32 = User32.INSTANCE
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val started = CountDownLatch(1)
    private val registered = mutableSetOf<Int>() // touched only on the loop thread

    @Volatile
    private var threadId = 0

    @Volatile
    private var closed = false

    private val thread = Thread(::loop, "droidlink-hotkeys").apply {
        isDaemon = true
        start()
    }

    init {
        check(started.await(2, TimeUnit.SECONDS)) { "hotkey thread did not start" }
    }

    private fun loop() {
        val msg = WinUser.MSG()
        threadId = Kernel32.INSTANCE.GetCurrentThreadId()
        // Creates this thread's message queue so PostThreadMessage works right away.
        user32.PeekMessage(msg, null, 0, 0, 0)
        started.countDown()

        while (user32.GetMessage(msg, null, 0, 0) > 0) {
            when (msg.message) {
                WM_HOTKEY -> runCatching { onPressed(msg.wParam.toInt()) }
                WM_RUN_TASKS -> generateSequence { tasks.poll() }.forEach { it.run() }
            }
        }
        registered.forEach { user32.UnregisterHotKey(null, it) }
        registered.clear()
    }

    /** Runs [block] on the loop thread and waits for its result. */
    private fun <T> onLoop(block: () -> T): T {
        val result = CompletableFuture<T>()
        tasks.add { runCatching(block).fold(result::complete, result::completeExceptionally) }
        user32.PostThreadMessage(threadId, WM_RUN_TASKS, WinDef.WPARAM(0), WinDef.LPARAM(0))
        return result.get(2, TimeUnit.SECONDS)
    }

    override fun setBindings(bindings: Map<Int, Hotkey>): Set<Int> {
        if (closed) return bindings.keys
        return onLoop {
            registered.forEach { user32.UnregisterHotKey(null, it) }
            registered.clear()
            bindings.filter { (id, hotkey) ->
                // MOD_NOREPEAT: holding the keys down fires once, not repeatedly.
                val ok = user32.RegisterHotKey(null, id, hotkey.modifiers or MOD_NOREPEAT, hotkey.keyCode)
                if (ok) registered += id
                !ok
            }.keys
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        user32.PostThreadMessage(threadId, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        thread.join(1_000)
    }

    private companion object {
        const val WM_HOTKEY = 0x0312
        const val WM_RUN_TASKS = 0x8000 + 1 // WM_APP + 1
        const val MOD_NOREPEAT = 0x4000
    }
}
