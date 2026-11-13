package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.BatteryAlertPolicy.Alert
import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.shared.protocol.PowerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatteryAlertPolicyTest {
    private fun onBattery(level: Int) = BatteryStatus(level, charging = false, plugged = PowerSource.NONE, full = false)
    private fun charging(level: Int, full: Boolean = false) =
        BatteryStatus(level, charging = !full, plugged = PowerSource.USB, full = full)

    @Test
    fun lowFiresOnceUntilPluggedIn() {
        val policy = BatteryAlertPolicy()
        assertEquals(emptyList(), policy.evaluate(onBattery(50), 20))
        assertEquals(listOf(Alert.LOW), policy.evaluate(onBattery(20), 20))
        assertEquals(emptyList(), policy.evaluate(onBattery(10), 20))

        // Plugging in re-arms it.
        assertEquals(emptyList(), policy.evaluate(charging(11), 20))
        assertEquals(listOf(Alert.LOW), policy.evaluate(onBattery(19), 20))
    }

    @Test
    fun followsTheConfiguredThreshold() {
        val policy = BatteryAlertPolicy()
        assertEquals(emptyList(), policy.evaluate(onBattery(35), 30))
        assertEquals(listOf(Alert.LOW), policy.evaluate(onBattery(30), 30))

        // Disabled: nothing fires, and enabling later below the threshold alerts once.
        val off = BatteryAlertPolicy()
        assertEquals(emptyList(), off.evaluate(onBattery(5), null))
        assertEquals(listOf(Alert.LOW), off.evaluate(onBattery(5), 20))
        assertEquals(emptyList(), off.evaluate(onBattery(4), 20))
    }

    @Test
    fun fullFiresOnceUntilUnplugged() {
        val policy = BatteryAlertPolicy()
        assertEquals(emptyList(), policy.evaluate(charging(99), 20))
        assertEquals(listOf(Alert.FULL), policy.evaluate(charging(100, full = true), 20))
        assertEquals(emptyList(), policy.evaluate(charging(100, full = true), 20))
        assertEquals(emptyList(), policy.evaluate(onBattery(100), 20))
        assertEquals(listOf(Alert.FULL), policy.evaluate(charging(100, full = true), 20))
    }

    @Test
    fun settingsClampAndDisable() {
        assertEquals(20, BatteryAlertSettings().effectiveLowThreshold)
        assertEquals(50, BatteryAlertSettings(lowThreshold = 90).effectiveLowThreshold)
        assertEquals(5, BatteryAlertSettings(lowThreshold = 0).effectiveLowThreshold)
        assertNull(BatteryAlertSettings(lowEnabled = false).effectiveLowThreshold)
        // Older settings files without the new sections get the defaults.
        val old = SettingsStore.decode("""{"pcToPhone": "AUTO"}""")
        assertEquals(BatteryAlertSettings(), old.battery)
        assertEquals(NotificationSettings(), old.notifications)
    }
}
