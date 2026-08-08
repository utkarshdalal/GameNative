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

    // exact BCP-47 (lowercased) -> Goldberg name, inverted from goldbergMap (values are
    // distinct, so the inverse is well-defined). covers full tags like "de-de", "pt-br".
    private val bcp47ToGoldbergExact: Map<String, String> =
        goldbergMap.entries.associate { (g, b) -> b.lowercase(Locale.ROOT) to g }

    // primary-subtag fallback for region-less tags ("de", "en") that resolve() can return
    // straight from appLanguage. ambiguous primaries (es/pt/zh) pick the unsuffixed Steam
    // default (spanish, portuguese, schinese).
    private val primaryToGoldberg: Map<String, String> = mapOf(
        "en" to "english", "zh" to "schinese", "ko" to "koreana", "ja" to "japanese",
        "es" to "spanish", "fr" to "french", "de" to "german", "it" to "italian",
        "pt" to "portuguese", "pl" to "polish", "ru" to "russian", "uk" to "ukrainian",
        "nl" to "dutch", "tr" to "turkish", "cs" to "czech", "hu" to "hungarian",
        "ro" to "romanian", "da" to "danish",
    )

    // BCP-47 -> Steam/Goldberg language name. null = not a Steam-supported language.
    fun bcp47ToGoldberg(tag: String): String? {
        val t = tag.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return null
        bcp47ToGoldbergExact[t]?.let { return it }
        return primaryToGoldberg[t.substringBefore('-')]
    }

    // Steam API language NAME ("german") for steamworks.js/greenworks getCurrentGameLanguage /
    // getCurrentUILanguage. reuses resolve() so it follows the SAME precedence as
    // navigator.language (container goldberg -> appLanguage -> system -> en-US) -- the two
    // channels stay consistent. unmappable resolved tag -> "english".
    fun resolveSteamLanguage(
        containerLanguage: String?,
        appLanguage: String,
        systemLocale: Locale = Locale.getDefault(),
    ): String = bcp47ToGoldberg(resolve(containerLanguage, appLanguage, systemLocale)) ?: "english"

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
