package app.gamenative.html5.host

import java.util.Locale

// navigator.language precedence: container goldberg language -> app language pref -> system locale -> en-US.
// "english" is the container default, i.e. UNSET, so it's deliberately absent from goldbergMap and falls
// through; unknown values fall through too rather than failing the launch.
object WebViewLocaleResolver {

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

    private val bcp47ToGoldbergExact: Map<String, String> =
        goldbergMap.entries.associate { (g, b) -> b.lowercase(Locale.ROOT) to g }

    // region-less tags ("de") can come straight from appLanguage. ambiguous primaries pick the
    // unsuffixed Steam default (spanish, portuguese, schinese).
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

    // Steam API language name for steamworks/greenworks; same precedence as navigator.language so they agree.
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
        if (!containerLanguage.isNullOrBlank()) {
            goldbergToBcp47(containerLanguage)?.let { return it }
        }
        if (appLanguage.isNotBlank()) return appLanguage
        // Locale.ROOT yields "und".
        val systemTag = systemLocale.toLanguageTag()
        if (systemTag.isNotBlank() && systemTag != "und") return systemTag
        return "en-US"
    }
}
