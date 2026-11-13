package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.shared.protocol.PowerSource

/**
 * Turns a stream of battery updates into one-shot alerts.
 * Each alert fires once and re-arms only when the condition clears
 * (unplugged for FULL, plugged in for LOW).
 */
class BatteryAlertPolicy {
    enum class Alert { FULL, LOW }

    private var fullNotified = false
    private var lowNotified = false

    /** [lowThreshold] null disables the low alert; the threshold is read on every update. */
    @Synchronized
    fun evaluate(status: BatteryStatus, lowThreshold: Int?): List<Alert> {
        if (status.level < 0) return emptyList()
        val onPower = status.plugged != PowerSource.NONE

        if (onPower) {
            lowNotified = false
            if ((status.full || status.level >= 100) && !fullNotified) {
                fullNotified = true
                return listOf(Alert.FULL)
            }
        } else {
            fullNotified = false
            if (lowThreshold != null && status.level <= lowThreshold && !lowNotified) {
                lowNotified = true
                return listOf(Alert.LOW)
            }
        }
        return emptyList()
    }
}
