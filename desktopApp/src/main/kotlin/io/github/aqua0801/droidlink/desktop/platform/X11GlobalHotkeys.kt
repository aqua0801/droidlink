package io.github.aqua0801.droidlink.desktop.platform

import com.sun.jna.platform.unix.X11
import io.github.aqua0801.droidlink.desktop.hotkey.Hotkey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * X11 (Xorg sessions): XGrabKey on the root window over a private display connection, so the
 * AWT connection is never touched from this thread. Like RegisterHotKey, only the grabbed
 * combination is delivered; no other keystrokes are observed.
 *
 * Not used on Wayland: the compositor only forwards keys to X11 clients that have focus, so a
 * grab there would work only while DroidLink's own window is active.
 */
class X11GlobalHotkeys(private val onPressed: (Int) -> Unit) : GlobalHotkeys {
    override val isSupported = true

    private val x11 = X11.INSTANCE
    private val display: X11.Display = x11.XOpenDisplay(null) ?: error("cannot open X display")
    private val root: X11.Window = x11.XDefaultRootWindow(display)
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    /** keycode + modifier mask -> binding id; touched only on the loop thread. */
    private val grabs = mutableMapOf<Pair<Int, Int>, Int>()

    @Volatile
    private var closed = false

    private val thread = Thread(::loop, "droidlink-hotkeys").apply {
        isDaemon = true
        start()
    }

    private fun loop() {
        val event = X11.XEvent()
        while (!closed) {
            generateSequence { tasks.poll() }.forEach { it.run() }
            while (x11.XPending(display) > 0) {
                x11.XNextEvent(display, event)
                if (event.type == X11.KeyPress) {
                    val key = event.readField("xkey") as X11.XKeyEvent
                    grabs[key.keycode to (key.state and IGNORED_MASKS.inv() and MODIFIER_MASK)]
                        ?.let { id -> runCatching { onPressed(id) } }
                }
            }
            Thread.sleep(POLL_MS)
        }
        ungrabAll()
        x11.XCloseDisplay(display)
    }

    private fun <T> onLoop(block: () -> T): T {
        val result = CompletableFuture<T>()
        tasks.add { runCatching(block).fold(result::complete, result::completeExceptionally) }
        return result.get(2, TimeUnit.SECONDS)
    }

    override fun setBindings(bindings: Map<Int, Hotkey>): Set<Int> {
        if (closed) return bindings.keys
        return onLoop {
            ungrabAll()
            bindings.filter { (id, hotkey) -> !grab(id, hotkey) }.keys
        }
    }

    /** Grabs [hotkey] with every Caps Lock / Num Lock combination. False if another client owns it. */
    private fun grab(id: Int, hotkey: Hotkey): Boolean {
        val keysym = keysymOf(hotkey.keyCode) ?: return false
        val keycode = x11.XKeysymToKeycode(display, X11.KeySym(keysym)).toInt() and 0xFF
        if (keycode == 0) return false
        val modifiers = maskOf(hotkey.modifiers)

        // A conflicting grab fails asynchronously with BadAccess; catch it with a temporary
        // handler (the default one would terminate the process).
        var failed = false
        val handler = X11.XErrorHandler { _, error ->
            if (error.error_code.toInt() == BAD_ACCESS) failed = true
            0
        }
        val previous = x11.XSetErrorHandler(handler)
        try {
            LOCK_VARIANTS.forEach { extra -> x11.XGrabKey(display, keycode, modifiers or extra, root, 1, GRAB_ASYNC, GRAB_ASYNC) }
            x11.XSync(display, false)
        } finally {
            x11.XSetErrorHandler(previous)
        }
        if (failed) {
            LOCK_VARIANTS.forEach { extra -> x11.XUngrabKey(display, keycode, modifiers or extra, root) }
            x11.XSync(display, false)
            return false
        }
        grabs[keycode to modifiers] = id
        return true
    }

    private fun ungrabAll() {
        grabs.keys.forEach { (keycode, modifiers) ->
            LOCK_VARIANTS.forEach { extra -> x11.XUngrabKey(display, keycode, modifiers or extra, root) }
        }
        grabs.clear()
        x11.XSync(display, false)
    }

    override fun close() {
        if (closed) return
        closed = true
        thread.join(1_000)
    }

    private companion object {
        const val POLL_MS = 25L
        const val GRAB_ASYNC = 1 // GrabModeAsync
        const val BAD_ACCESS = 10

        // X modifier masks.
        const val SHIFT = 1 shl 0
        const val LOCK = 1 shl 1 // Caps Lock
        const val CONTROL = 1 shl 2
        const val MOD1 = 1 shl 3 // Alt
        const val MOD2 = 1 shl 4 // Num Lock
        const val MOD4 = 1 shl 6 // Super
        const val MODIFIER_MASK = SHIFT or CONTROL or MOD1 or MOD4
        const val IGNORED_MASKS = LOCK or MOD2
        val LOCK_VARIANTS = intArrayOf(0, LOCK, MOD2, LOCK or MOD2)

        fun maskOf(modifiers: Int): Int {
            var mask = 0
            if (modifiers and Hotkey.MOD_SHIFT != 0) mask = mask or SHIFT
            if (modifiers and Hotkey.MOD_CONTROL != 0) mask = mask or CONTROL
            if (modifiers and Hotkey.MOD_ALT != 0) mask = mask or MOD1
            if (modifiers and Hotkey.MOD_WIN != 0) mask = mask or MOD4
            return mask
        }

        /** Hotkey key codes (letters, digits, F1-F12) to X keysyms. */
        fun keysymOf(code: Int): Long? = when (code) {
            in 0x41..0x5A -> (code + 0x20).toLong() // XK_a..XK_z
            in 0x30..0x39 -> code.toLong() // XK_0..XK_9
            in 0x70..0x7B -> 0xFFBEL + (code - 0x70) // XK_F1..XK_F12
            else -> null
        }
    }
}
