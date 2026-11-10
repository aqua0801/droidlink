package io.github.aqua0801.droidlink.shared.settings

import kotlinx.serialization.Serializable

/** App colours: follow the OS light/dark setting, or force one. Used by both apps. */
@Serializable
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun parse(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
