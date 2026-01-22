package app.gamenative.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Helper class for managing app locale/language settings.
 */
object LocaleHelper {

    /**
     * Supported languages in the app.
     * Key is the language code, value is the display name.
     * Only includes languages that have actual string resource files.
     */
    val SUPPORTED_LANGUAGES = linkedMapOf(
        "" to "System Default",
        "da" to "Dansk (Danish)",
        "en" to "English",
        "es" to "Español",
        "it" to "Italiano",
        "pt-BR" to "Português Brasileiro (Brazilian Portuguese)",
        "uk" to "Українська",
        "zh-TW" to "正體中文",
        "zh-CN" to "简体中文",
        "fr" to "Français",
        "de" to "Deutsch"
    )

    /**
     * Apply the saved language preference to the context.
     * @param context The context to update
     * @param languageCode The language code to apply (empty string for system default)
     * @return Updated context with the new locale
     */
    fun applyLanguage(context: Context, languageCode: String): Context {
        if (languageCode.isEmpty()) {
            // Use system default - no need to override
            return context
        }

        val locale = getLocaleFromCode(languageCode)
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
     * Convert a language code string to a Locale object.
     * Handles language codes with region (e.g., "pt-BR", "zh-CN").
     */
    private fun getLocaleFromCode(languageCode: String): Locale {
        return when {
            languageCode.contains("-") -> {
                val parts = languageCode.split("-")
                if (parts.size == 2) {
                    Locale(parts[0], parts[1])
                } else {
                    Locale(parts[0])
                }
            }
            else -> Locale(languageCode)
        }
    }

    /**
     * Get the display name for a language code.
     * @param languageCode The language code
     * @return The display name, or the code itself if not found
     */
    fun getLanguageDisplayName(languageCode: String): String {
        return SUPPORTED_LANGUAGES[languageCode] ?: languageCode
    }

    /**
     * Get the list of supported language codes.
     */
    fun getSupportedLanguageCodes(): List<String> {
        return SUPPORTED_LANGUAGES.keys.toList()
    }

    /**
     * Get the list of supported language display names.
     */
    fun getSupportedLanguageNames(): List<String> {
        return SUPPORTED_LANGUAGES.values.toList()
    }
}
