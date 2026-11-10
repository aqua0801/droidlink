package io.github.aqua0801.droidlink.desktop

import io.github.aqua0801.droidlink.desktop.hotkey.Hotkey
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
 * MANUAL accepts what the phone sends explicitly ("傳到電腦", tile, share) and enables the
 * pull hotkey. OFF ignores the phone's clipboard entirely.
 */
@Serializable
enum class PhoneToPcMode { AUTO, MANUAL, OFF }

@Serializable
data class DesktopSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val pcToPhone: PcToPhoneMode = PcToPhoneMode.AUTO,
    val phoneToPc: PhoneToPcMode = PhoneToPcMode.MANUAL,
    val pushHotkey: Hotkey = Hotkey.DEFAULT_PUSH,
    val pullHotkey: Hotkey = Hotkey.DEFAULT_PULL,
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
