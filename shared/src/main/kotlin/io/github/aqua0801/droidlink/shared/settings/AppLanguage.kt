package io.github.aqua0801.droidlink.shared.settings

import kotlinx.serialization.Serializable

/** UI language. [tag] is a BCP 47 tag, or null to follow the OS. Used by both apps. */
@Serializable
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    TRADITIONAL_CHINESE("zh-TW");

    companion object {
        val DEFAULT = ENGLISH

        fun parse(name: String?): AppLanguage = entries.firstOrNull { it.name == name } ?: DEFAULT

        /** Maps a locale tag (e.g. from the Android per-app language setting) to a supported language. */
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()
            return when {
                tag.isNullOrEmpty() -> SYSTEM
                language == "zh" -> TRADITIONAL_CHINESE
                else -> ENGLISH
            }
        }
    }
}
