package com.jbselfcompany.tyr.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.jbselfcompany.tyr.data.ConfigRepository
import java.util.Locale

/**
 * Helper class for managing application language and theme.
 */
object LocaleHelper {

    /**
     * Apply saved language preference to context.
     * Returns updated context with the configured locale.
     */
    fun applyLanguage(context: Context): Context {
        val configRepository = ConfigRepository(context)
        val languageCode = configRepository.getLanguage()

        return when (languageCode) {
            ConfigRepository.LANGUAGE_SYSTEM -> context
            else -> updateLocale(context, languageCode)
        }
    }

    /**
     * Update context locale.
     */
    private fun updateLocale(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    /**
     * Apply saved theme preference.
     */
    fun applyTheme(context: Context) {
        val configRepository = ConfigRepository(context)
        val themeMode = configRepository.getTheme()

        val mode = when (themeMode) {
            ConfigRepository.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ConfigRepository.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /**
     * Get current system language code.
     * Returns "en" or "ru" based on system locale.
     */
    fun getSystemLanguage(): String {
        val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            android.content.res.Resources.getSystem().configuration.locale
        }

        return when (systemLocale.language) {
            "ru" -> ConfigRepository.LANGUAGE_RUSSIAN
            else -> ConfigRepository.LANGUAGE_ENGLISH
        }
    }

    /**
     * Get display name for language code.
     */
    fun getLanguageDisplayName(context: Context, languageCode: String): String {
        return when (languageCode) {
            ConfigRepository.LANGUAGE_SYSTEM -> {
                val systemLang = getSystemLanguage()
                val systemName = when (systemLang) {
                    ConfigRepository.LANGUAGE_RUSSIAN -> "Русский"
                    else -> "English"
                }
                "${getSystemText(context)} ($systemName)"
            }
            ConfigRepository.LANGUAGE_ENGLISH -> "English"
            ConfigRepository.LANGUAGE_RUSSIAN -> "Русский"
            else -> languageCode
        }
    }

    /**
     * Get display name for theme.
     */
    fun getThemeDisplayName(context: Context, themeCode: String): String {
        return when (themeCode) {
            ConfigRepository.THEME_SYSTEM -> getSystemText(context)
            ConfigRepository.THEME_LIGHT -> getLightText(context)
            ConfigRepository.THEME_DARK -> getDarkText(context)
            else -> themeCode
        }
    }

    private fun getSystemText(context: Context): String {
        return when (getSystemLanguage()) {
            ConfigRepository.LANGUAGE_RUSSIAN -> "Системный"
            else -> "System"
        }
    }

    private fun getLightText(context: Context): String {
        return when (getSystemLanguage()) {
            ConfigRepository.LANGUAGE_RUSSIAN -> "Светлая"
            else -> "Light"
        }
    }

    private fun getDarkText(context: Context): String {
        return when (getSystemLanguage()) {
            ConfigRepository.LANGUAGE_RUSSIAN -> "Темная"
            else -> "Dark"
        }
    }
}
