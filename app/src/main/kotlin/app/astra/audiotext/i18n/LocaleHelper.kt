package app.astra.audiotext.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import app.astra.audiotext.settings.UiLanguage
import java.util.Locale

/**
 * In-app language choice (System / English / हिन्दी) that works on every supported Android version.
 * Android 13+ also lists the app in system "App languages" via res/xml/locales_config.xml.
 */
object LocaleHelper {

    fun wrap(base: Context, language: UiLanguage): Context {
        val tag = language.tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= 24) config.setLocales(LocaleList(locale)) else @Suppress("DEPRECATION") { config.locale = locale }
        return base.createConfigurationContext(config)
    }

    /** Reads the stored choice before SettingsStore exists (attachBaseContext runs first). */
    fun stored(context: Context): UiLanguage {
        val name = context.getSharedPreferences("astra_settings", Context.MODE_PRIVATE).getString("uiLanguage", null)
        return UiLanguage.values().firstOrNull { it.name == name } ?: UiLanguage.SYSTEM
    }
}
