package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.BatteryAlertPolicy.Alert
import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.shared.protocol.PowerSource
import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryAlertPolicyTest {
    private fun onBattery(level: Int) = BatteryStatus(level, charging = false, plugged = PowerSource.NONE, full = false)
    private fun charging(level: Int, full: Boolean = false) =
        BatteryStatus(level, charging = !full, plugged = PowerSource.USB, full = full)

    @Test
    fun lowAndCriticalFireOnceUntilPluggedIn() {
        val policy = BatteryAlertPolicy()
        assertEquals(emptyList(), policy.evaluate(onBattery(50)))
        assertEquals(listOf(Alert.LOW), policy.evaluate(onBattery(20)))
        assertEquals(emptyList(), policy.evaluate(onBattery(15)))
        assertEquals(listOf(Alert.CRITICAL), policy.evaluate(onBattery(10)))
        assertEquals(emptyList(), policy.evaluate(onBattery(5)))

        // Plugging in re-arms both.
        assertEquals(emptyList(), policy.evaluate(charging(6)))
        assertEquals(listOf(Alert.LOW), policy.evaluate(onBattery(19)))
    }

    @Test
    fun fullFiresOnceUntilUnplugged() {
        val policy = BatteryAlertPolicy()
        assertEquals(emptyList(), policy.evaluate(charging(99)))
        assertEquals(listOf(Alert.FULL), policy.evaluate(charging(100, full = true)))
        assertEquals(emptyList(), policy.evaluate(charging(100, full = true)))
        assertEquals(emptyList(), policy.evaluate(onBattery(100)))
        assertEquals(listOf(Alert.FULL), policy.evaluate(charging(100, full = true)))
    }

    @Test
    fun startingAlreadyCriticalSkipsTheLowAlert() {
        val policy = BatteryAlertPolicy()
        assertEquals(listOf(Alert.CRITICAL), policy.evaluate(onBattery(8)))
        assertEquals(emptyList(), policy.evaluate(onBattery(12)))
    }
}
