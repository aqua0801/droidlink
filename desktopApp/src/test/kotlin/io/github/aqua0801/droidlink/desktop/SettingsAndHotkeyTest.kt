package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.hotkey.Hotkey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsAndHotkeyTest {
    @Test
    fun migratesOldSwitches() {
        val off = SettingsStore.decode("""{"clipboardSendToPhone": false, "clipboardReceiveFromPhone": false}""")
        assertEquals(PcToPhoneMode.OFF, off.pcToPhone)
        assertEquals(PhoneToPcMode.OFF, off.phoneToPc)

        // Old "on" values land on the new defaults: PC follows copies, phone is manual.
        val on = SettingsStore.decode("""{"clipboardSendToPhone": true, "clipboardReceiveFromPhone": true}""")
        assertEquals(DesktopSettings(), on)

        // Once the new keys exist, leftovers of the old ones are ignored.
        val mixed = SettingsStore.decode("""{"pcToPhone": "HOTKEY", "clipboardSendToPhone": false}""")
        assertEquals(PcToPhoneMode.HOTKEY, mixed.pcToPhone)
    }

    @Test
    fun roundTripsAndSurvivesGarbage() {
        val file = Files.createTempFile("droidlink-settings", ".json").toFile().apply { deleteOnExit() }
        val custom = Hotkey(Hotkey.MOD_CONTROL or Hotkey.MOD_SHIFT, 0x70)
        SettingsStore(file).update { it.copy(pcToPhone = PcToPhoneMode.HOTKEY, pushHotkey = custom) }
        val reloaded = SettingsStore(file).settings.value
        assertEquals(PcToPhoneMode.HOTKEY, reloaded.pcToPhone)
        assertEquals(custom, reloaded.pushHotkey)

        file.writeText("{ not json")
        assertEquals(DesktopSettings(), SettingsStore(file).settings.value)
    }

    @Test
    fun themeDefaultsAndUnknownValues() {
        assertEquals(ThemeMode.SYSTEM, SettingsStore.decode("{}").theme)
        assertEquals(ThemeMode.DARK, SettingsStore.decode("""{"theme": "DARK"}""").theme)
        // An unknown theme keeps the other fields instead of resetting everything.
        val future = SettingsStore.decode("""{"theme": "SEPIA", "pcToPhone": "OFF"}""")
        assertEquals(ThemeMode.SYSTEM, future.theme)
        assertEquals(PcToPhoneMode.OFF, future.pcToPhone)
    }

    @Test
    fun hotkeyRules() {
        assertEquals("Ctrl+Alt+C", Hotkey.DEFAULT_PUSH.label)
        assertEquals("Ctrl+Alt+V", Hotkey.DEFAULT_PULL.label)
        assertEquals("Ctrl+Shift+F5", Hotkey.of(ctrl = true, alt = false, shift = true, win = false, keyCode = 0x74)?.label)
        assertEquals("Alt+7", Hotkey.of(ctrl = false, alt = true, shift = false, win = false, keyCode = '7'.code)?.label)
        // Shift alone, no modifier, or an unsupported key are rejected.
        assertNull(Hotkey.of(ctrl = false, alt = false, shift = true, win = false, keyCode = 'A'.code))
        assertNull(Hotkey.of(ctrl = false, alt = false, shift = false, win = false, keyCode = 'A'.code))
        assertNull(Hotkey.of(ctrl = true, alt = false, shift = false, win = false, keyCode = 0x20)) // space
        assertTrue(Hotkey.DEFAULT_PUSH.isValid)
        assertFalse(Hotkey(Hotkey.MOD_SHIFT, 'A'.code).isValid)
    }
}
