package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.hotkey.Hotkey
import io.github.aqua0801.droidlink.shared.settings.AppLanguage
import io.github.aqua0801.droidlink.shared.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** PC -> phone clipboard: follow every copy, only on the push hotkey, or never. */
@Serializable
enum class PcToPhoneMode { AUTO, HOTKEY, OFF }

/**
 * Phone -> PC clipboard. AUTO needs enhanced mode (the helper reports every phone copy).
 * MANUAL accepts what the phone sends explicitly ("Send to PC", tile, share) and enables the
 * pull hotkey. OFF ignores the phone's clipboard entirely.
 */
@Serializable
enum class PhoneToPcMode { AUTO, MANUAL, OFF }

/** Phone battery alerts shown on this PC. */
@Serializable
data class BatteryAlertSettings(
    val lowEnabled: Boolean = true,
    val lowThreshold: Int = DEFAULT_LOW_THRESHOLD,
    val fullEnabled: Boolean = true,
) {
    /** The threshold in effect, or null when low-battery alerts are off. */
    val effectiveLowThreshold: Int?
        get() = if (lowEnabled) lowThreshold.coerceIn(MIN_LOW_THRESHOLD, MAX_LOW_THRESHOLD) else null

    companion object {
        const val DEFAULT_LOW_THRESHOLD = 20
        const val MIN_LOW_THRESHOLD = 5
        const val MAX_LOW_THRESHOLD = 50
    }
}

/**
 * Confirmation notifications that can be muted. Failures and prompts that need the user
 * (no phone connected, enhanced mode required, ...) are always shown.
 */
@Serializable
data class NotificationSettings(
    val clipboard: Boolean = true,
    val fileReceived: Boolean = true,
    /** Preview popup for screenshots sent by the phone. */
    val screenshot: Boolean = true,
)

/** The PC folder pushed to phones; [folder] null means the default location. */
@Serializable
data class SyncSettings(
    val folder: String? = null,
    /** Also delete phone files that are no longer in the PC folder. */
    val mirror: Boolean = false,
)

/** Mirroring over ADB (scrcpy). */
@Serializable
data class MirrorSettings(
    /** Forward the phone's audio (Android 11+). */
    val audio: Boolean = true,
    /** Keep the sound on the phone as well (Android 13+); otherwise it only plays on the PC. */
    val audioKeepOnPhone: Boolean = false,
)

@Serializable
data class DesktopSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.DEFAULT,
    val pcToPhone: PcToPhoneMode = PcToPhoneMode.AUTO,
    val phoneToPc: PhoneToPcMode = PhoneToPcMode.MANUAL,
    val pushHotkey: Hotkey = Hotkey.DEFAULT_PUSH,
    val pullHotkey: Hotkey = Hotkey.DEFAULT_PULL,
    val battery: BatteryAlertSettings = BatteryAlertSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val sync: SyncSettings = SyncSettings(),
    val mirror: MirrorSettings = MirrorSettings(),
)

class SettingsStore(private val file: File) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (DesktopSettings) -> DesktopSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        file.writeTextAtomically(json.encodeToString(DesktopSettings.serializer(), next))
    }

    private fun load(): DesktopSettings =
        runCatching { if (file.isFile) decode(file.readText()) else DesktopSettings() }.getOrDefault(DesktopSettings())

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            // An unknown enum value (e.g. written by a newer version) resets that field only.
            coerceInputValues = true
        }

        /**
         * Reads settings, carrying over the 0.x on/off switches (`clipboardSendToPhone`,
         * `clipboardReceiveFromPhone`) when the new mode keys aren't present yet.
         */
        fun decode(text: String): DesktopSettings {
            val raw = json.parseToJsonElement(text).jsonObject
            var settings = json.decodeFromJsonElement(DesktopSettings.serializer(), raw)
            fun legacy(key: String) = raw[key]?.jsonPrimitive?.booleanOrNull
            if ("pcToPhone" !in raw && legacy("clipboardSendToPhone") == false) {
                settings = settings.copy(pcToPhone = PcToPhoneMode.OFF)
            }
            if ("phoneToPc" !in raw && legacy("clipboardReceiveFromPhone") == false) {
                settings = settings.copy(phoneToPc = PhoneToPcMode.OFF)
            }
            return settings
        }
    }
}
