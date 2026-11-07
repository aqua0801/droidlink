package io.github.aqua0801.droidlink.battery

import android.content.Intent
import android.os.BatteryManager
import io.github.aqua0801.droidlink.shared.protocol.BatteryStatus
import io.github.aqua0801.droidlink.shared.protocol.PowerSource

/** BatteryManager.BATTERY_PLUGGED_DOCK, added in API 33. */
private const val PLUGGED_DOCK = 8

/** Parses a sticky ACTION_BATTERY_CHANGED intent. */
fun Intent.toBatteryStatus(): BatteryStatus {
    val rawLevel = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val level = if (rawLevel >= 0 && scale > 0) rawLevel * 100 / scale else -1

    val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val plugged = when (getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        0 -> PowerSource.NONE
        BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
        BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.WIRELESS
        PLUGGED_DOCK -> PowerSource.DOCK
        else -> PowerSource.UNKNOWN
    }
    val temperature = getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        .takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
    val voltage = getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }

    return BatteryStatus(
        level = level,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING,
        plugged = plugged,
        full = status == BatteryManager.BATTERY_STATUS_FULL || (level >= 100 && plugged != PowerSource.NONE),
        temperatureC = temperature,
        voltageMv = voltage,
    )
}
