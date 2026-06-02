package app.gamenative.html5.host

import java.util.Locale

// resolves the BCP-47 locale string surfaced to html5 game JS via
// navigator.language / navigator.languages. pure kotlin, zero android deps -- unit-testable.

// resolution order (first non-empty wins):
// (a) goldbergToBcp47(containerLanguage) -- per-container setting from Goldberg UI
// (b) PrefManager.appLanguage -- app-wide override
// (c) system Locale.getDefault().toLanguageTag()
// (d) "en-US" final fallback

// containerLanguage == "english" is the Goldberg UNSET sentinel (Container.java default)
// -- deliberately absent from the map so it falls through. unknown goldberg strings also
// fall through (do NOT fail the launch -- must-have).
object WebViewLocaleResolver {

    // locked table mapping locale to Goldberg language. keys lowercased for
    // case-insensitive lookup. english intentionally missing (UNSET sentinel).
    private val goldbergMap: Map<String, String> = mapOf(
        "schinese" to "zh-CN",
        "tchinese" to "zh-TW",
        "koreana" to "ko-KR",
        "japanese" to "ja-JP",
        "spanish" to "es-ES",
        "latam" to "es-419",
        "french" to "fr-FR",
        "german" to "de-DE",
        "italian" to "it-IT",
        "portuguese" to "pt-PT",
        "brazilian" to "pt-BR",
        "polish" to "pl-PL",
        "russian" to "ru-RU",
        "ukrainian" to "uk-UA",
        "dutch" to "nl-NL",
        "turkish" to "tr-TR",
        "czech" to "cs-CZ",
        "hungarian" to "hu-HU",
        "romanian" to "ro-RO",
        "danish" to "da-DK",
    )

    fun goldbergToBcp47(goldberg: String): String? =
        goldbergMap[goldberg.trim().lowercase(Locale.ROOT)]

    fun resolve(
        containerLanguage: String?,
        appLanguage: String,
        systemLocale: Locale = Locale.getDefault(),
    ): String {
        // (a) container goldberg -- only consult map when caller actually set something.
        // blank / "english" / unknown all fall through via null from goldbergToBcp47.
        if (!containerLanguage.isNullOrBlank()) {
            goldbergToBcp47(containerLanguage)?.let { return it }
        }
        // (b) app-wide pref -- "" means "system default".
        if (appLanguage.isNotBlank()) return appLanguage
        // (c) system locale. Locale.ROOT.toLanguageTag() returns "und" -- treat as unset.
        val systemTag = systemLocale.toLanguageTag()
        if (systemTag.isNotBlank() && systemTag != "und") return systemTag
        // (d) final fallback.
        return "en-US"
    }
}
