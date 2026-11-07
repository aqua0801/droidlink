package io.github.aqua0801.droidlink.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DesktopSettings(
    val clipboardSendToPhone: Boolean = true,
    val clipboardReceiveFromPhone: Boolean = true,
)

class SettingsStore(private val file: File) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (DesktopSettings) -> DesktopSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        file.writeTextAtomically(json.encodeToString(DesktopSettings.serializer(), next))
    }

    private fun load(): DesktopSettings =
        runCatching { json.decodeFromString(DesktopSettings.serializer(), file.readText()) }
            .getOrDefault(DesktopSettings())
}
