package io.github.aqua0801.droidlink.shared

import io.github.aqua0801.droidlink.shared.settings.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test
    fun defaultsToEnglish() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.parse(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.parse("KLINGON"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.parse("TRADITIONAL_CHINESE"))
    }

    @Test
    fun mapsTags() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromTag("zh-TW"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromTag("zh_Hant_HK"))
    }
}
