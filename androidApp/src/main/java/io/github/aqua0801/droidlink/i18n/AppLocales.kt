package io.github.aqua0801.droidlink.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import io.github.aqua0801.droidlink.data.PhoneSettings
import io.github.aqua0801.droidlink.shared.settings.AppLanguage

/**
 * In-app language.
 *
 * Android 13+: the platform per-app language (LocaleManager) does the work. It also appears
 * in the system's app settings, so a choice made there is read back on startup.
 * Android 10–12: every component wraps its base context with [wrap], whose resources use the
 * chosen locale; activities are recreated after a change.
 */
object AppLocales {
    /** True when the platform applies the language itself. */
    val isPlatformManaged: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @Volatile
    private var current: AppLanguage? = null

    /** Called once from Application.onCreate. */
    fun init(context: Context, settings: PhoneSettings) {
        if (!isPlatformManaged) {
            current = settings.language.value
            return
        }
        val manager = context.getSystemService(LocaleManager::class.java)
        if (!settings.hasLanguage) {
            // First run: use the default language, not the phone's.
            set(context, settings, AppLanguage.DEFAULT)
        } else {
            val platform = AppLanguage.fromTag(manager.applicationLocales.toLanguageTags())
            if (platform != settings.language.value) settings.setLanguage(platform)
        }
    }

    /** Stores and applies [language]. Returns true when the caller must recreate its activity. */
    fun set(context: Context, settings: PhoneSettings, language: AppLanguage): Boolean {
        settings.setLanguage(language)
        current = language
        if (!isPlatformManaged) return true
        val tags = language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        context.getSystemService(LocaleManager::class.java).applicationLocales = tags
        return false
    }

    /** Use in attachBaseContext of the application, activities and services. */
    fun wrap(base: Context): Context = if (isPlatformManaged) base else LocalizedContext(base)

    private fun language(context: Context): AppLanguage =
        current ?: PhoneSettings.readLanguage(context).also { current = it }

    /** Resources follow the current language (checked on every access, so services pick up changes). */
    private class LocalizedContext(base: Context) : ContextWrapper(base) {
        private var cachedFor: Pair<String, Configuration>? = null
        private var cached: Resources? = null

        override fun getResources(): Resources {
            val base = super.getResources()
            val tag = language(baseContext).tag ?: return base
            val key = tag to base.configuration
            cached?.takeIf { cachedFor == key }?.let { return it }
            val config = Configuration(base.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
            return createConfigurationContext(config).resources.also {
                cached = it
                cachedFor = key
            }
        }
    }
}
