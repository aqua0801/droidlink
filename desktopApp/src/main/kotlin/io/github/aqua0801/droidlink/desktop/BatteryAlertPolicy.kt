package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.shared.protocol.PowerSource

/**
 * Turns a stream of battery updates into one-shot alerts.
 * Each alert fires once and re-arms only when the condition clears
 * (unplugged for FULL, plugged in for LOW/CRITICAL).
 */
class BatteryAlertPolicy(
    private val lowThreshold: Int = 20,
    private val criticalThreshold: Int = 10,
) {
    enum class Alert { FULL, LOW, CRITICAL }

    private var fullNotified = false
    private var lowNotified = false
    private var criticalNotified = false

    @Synchronized
    fun evaluate(status: BatteryStatus): List<Alert> {
        if (status.level < 0) return emptyList()
        val alerts = mutableListOf<Alert>()
        val onPower = status.plugged != PowerSource.NONE

        if (onPower) {
            lowNotified = false
            criticalNotified = false
            if ((status.full || status.level >= 100) && !fullNotified) {
                fullNotified = true
                alerts += Alert.FULL
            }
        } else {
            fullNotified = false
            if (status.level <= criticalThreshold) {
                if (!criticalNotified) {
                    criticalNotified = true
                    lowNotified = true
                    alerts += Alert.CRITICAL
                }
            } else if (status.level <= lowThreshold && !lowNotified) {
                lowNotified = true
                alerts += Alert.LOW
            }
        }
        return alerts
    }
}
