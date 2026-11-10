package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.hotkey.Hotkey
import io.github.aqua0801.droidlink.desktop.platform.GlobalHotkeys
import java.awt.Robot
import java.awt.event.KeyEvent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Registers a real global hotkey and presses it with java.awt.Robot. Skipped unless run with
 * `-PrealHotkeys=true` on Windows, because it sends keystrokes to the current desktop session.
 */
class GlobalHotkeysRealTest {
    @Test
    fun registersDetectsConflictsAndFires() {
        if (System.getProperty("droidlink.realHotkeys") != "true") return

        val pressed = LinkedBlockingQueue<Int>()
        // Ctrl+Alt+Shift+F12: unlikely to be taken by anything else.
        val combo = Hotkey(Hotkey.MOD_CONTROL or Hotkey.MOD_ALT or Hotkey.MOD_SHIFT, 0x7B)

        GlobalHotkeys.create { pressed.put(it) }.use { hotkeys ->
            assertTrue(hotkeys.isSupported)
            assertEquals(emptySet(), hotkeys.setBindings(mapOf(7 to combo)))

            // The same combination can't be registered twice, just as with another program.
            GlobalHotkeys.create { }.use { other ->
                assertEquals(setOf(9), other.setBindings(mapOf(9 to combo)))
            }

            Robot().apply {
                autoDelay = 20
                listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_SHIFT, KeyEvent.VK_F12).forEach(::keyPress)
                listOf(KeyEvent.VK_F12, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT, KeyEvent.VK_CONTROL).forEach(::keyRelease)
            }
            assertEquals(7, pressed.poll(3, TimeUnit.SECONDS))

            // After clearing, the combination is free again.
            assertEquals(emptySet(), hotkeys.setBindings(emptyMap()))
            GlobalHotkeys.create { }.use { other ->
                assertEquals(emptySet(), other.setBindings(mapOf(9 to combo)))
            }
        }
    }
}
