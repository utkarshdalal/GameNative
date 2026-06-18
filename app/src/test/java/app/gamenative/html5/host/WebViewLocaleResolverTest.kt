package app.gamenative.html5.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

// pure-jvm — no android deps. covers resolution-order branches + full goldberg table.
class WebViewLocaleResolverTest {

    // locked goldberg→BCP-47 table (see quick yo frontmatter must_haves).
    // english intentionally missing — it's the "unset" sentinel and must return null.
    @Test fun goldberg_schinese() = assertEquals("zh-CN", WebViewLocaleResolver.goldbergToBcp47("schinese"))
    @Test fun goldberg_tchinese() = assertEquals("zh-TW", WebViewLocaleResolver.goldbergToBcp47("tchinese"))
    @Test fun goldberg_koreana() = assertEquals("ko-KR", WebViewLocaleResolver.goldbergToBcp47("koreana"))
    @Test fun goldberg_japanese() = assertEquals("ja-JP", WebViewLocaleResolver.goldbergToBcp47("japanese"))
    @Test fun goldberg_spanish() = assertEquals("es-ES", WebViewLocaleResolver.goldbergToBcp47("spanish"))
    @Test fun goldberg_latam() = assertEquals("es-419", WebViewLocaleResolver.goldbergToBcp47("latam"))
    @Test fun goldberg_french() = assertEquals("fr-FR", WebViewLocaleResolver.goldbergToBcp47("french"))
    @Test fun goldberg_german() = assertEquals("de-DE", WebViewLocaleResolver.goldbergToBcp47("german"))
    @Test fun goldberg_italian() = assertEquals("it-IT", WebViewLocaleResolver.goldbergToBcp47("italian"))
    @Test fun goldberg_portuguese() = assertEquals("pt-PT", WebViewLocaleResolver.goldbergToBcp47("portuguese"))
    @Test fun goldberg_brazilian() = assertEquals("pt-BR", WebViewLocaleResolver.goldbergToBcp47("brazilian"))
    @Test fun goldberg_polish() = assertEquals("pl-PL", WebViewLocaleResolver.goldbergToBcp47("polish"))
    @Test fun goldberg_russian() = assertEquals("ru-RU", WebViewLocaleResolver.goldbergToBcp47("russian"))
    @Test fun goldberg_ukrainian() = assertEquals("uk-UA", WebViewLocaleResolver.goldbergToBcp47("ukrainian"))
    @Test fun goldberg_dutch() = assertEquals("nl-NL", WebViewLocaleResolver.goldbergToBcp47("dutch"))
    @Test fun goldberg_turkish() = assertEquals("tr-TR", WebViewLocaleResolver.goldbergToBcp47("turkish"))
    @Test fun goldberg_czech() = assertEquals("cs-CZ", WebViewLocaleResolver.goldbergToBcp47("czech"))
    @Test fun goldberg_hungarian() = assertEquals("hu-HU", WebViewLocaleResolver.goldbergToBcp47("hungarian"))
    @Test fun goldberg_romanian() = assertEquals("ro-RO", WebViewLocaleResolver.goldbergToBcp47("romanian"))
    @Test fun goldberg_danish() = assertEquals("da-DK", WebViewLocaleResolver.goldbergToBcp47("danish"))

    // english is the goldberg "unset" sentinel — map miss on purpose.
    @Test
    fun goldberg_english_returns_null() {
        assertNull(WebViewLocaleResolver.goldbergToBcp47("english"))
    }

    @Test
    fun goldberg_unknown_returns_null() {
        assertNull(WebViewLocaleResolver.goldbergToBcp47("klingon"))
    }

    @Test
    fun goldberg_lookup_is_case_insensitive() {
        assertEquals("zh-CN", WebViewLocaleResolver.goldbergToBcp47("SCHINESE"))
        assertEquals("fr-FR", WebViewLocaleResolver.goldbergToBcp47("French"))
    }

    // resolve() branch (a): container goldberg wins.
    @Test
    fun resolve_container_goldberg_wins() {
        val got = WebViewLocaleResolver.resolve("schinese", "en", Locale.forLanguageTag("ja-JP"))
        assertEquals("zh-CN", got)
    }

    // branch (a) falls through for "english" (sentinel).
    @Test
    fun resolve_english_sentinel_falls_through_to_appLanguage() {
        val got = WebViewLocaleResolver.resolve("english", "es", Locale.forLanguageTag("ja-JP"))
        assertEquals("es", got)
    }

    // branch (a) falls through for unknown goldberg — MUST NOT fail launch.
    @Test
    fun resolve_unknown_goldberg_falls_through_to_appLanguage() {
        val got = WebViewLocaleResolver.resolve("klingon", "fr", Locale.forLanguageTag("ja-JP"))
        assertEquals("fr", got)
    }

    @Test
    fun resolve_blank_container_falls_through() {
        val got = WebViewLocaleResolver.resolve("   ", "en", Locale.forLanguageTag("de-DE"))
        assertEquals("en", got)
    }

    @Test
    fun resolve_null_container_falls_through_to_appLanguage() {
        val got = WebViewLocaleResolver.resolve(null, "pt-BR", Locale.forLanguageTag("de-DE"))
        assertEquals("pt-BR", got)
    }

    // branch (b) empty → (c) system locale.
    @Test
    fun resolve_empty_appLanguage_falls_through_to_system_locale() {
        val got = WebViewLocaleResolver.resolve(null, "", Locale.forLanguageTag("de-DE"))
        assertEquals("de-DE", got)
    }

    // branch (c) "und" skipped → (d) final fallback.
    @Test
    fun resolve_und_system_locale_falls_through_to_en_us() {
        val got = WebViewLocaleResolver.resolve(null, "", Locale.forLanguageTag("und"))
        assertEquals("en-US", got)
    }

    // branch (a) case-insensitive goldberg + empty app lang.
    @Test
    fun resolve_uppercase_goldberg_wins() {
        val got = WebViewLocaleResolver.resolve("SCHINESE", "", Locale.forLanguageTag("ja-JP"))
        assertEquals("zh-CN", got)
    }

    // ---- bcp47ToGoldberg (inverse map) ----

    @Test fun bcp47_exact_de_de() = assertEquals("german", WebViewLocaleResolver.bcp47ToGoldberg("de-DE"))
    @Test fun bcp47_exact_pt_br() = assertEquals("brazilian", WebViewLocaleResolver.bcp47ToGoldberg("pt-BR"))
    @Test fun bcp47_exact_es_419() = assertEquals("latam", WebViewLocaleResolver.bcp47ToGoldberg("es-419"))
    @Test fun bcp47_exact_zh_tw() = assertEquals("tchinese", WebViewLocaleResolver.bcp47ToGoldberg("zh-TW"))
    @Test fun bcp47_case_insensitive() = assertEquals("french", WebViewLocaleResolver.bcp47ToGoldberg("FR-fr"))

    // region-less primary subtag (appLanguage can be "de") falls back to the unsuffixed default.
    @Test fun bcp47_primary_de() = assertEquals("german", WebViewLocaleResolver.bcp47ToGoldberg("de"))
    @Test fun bcp47_primary_en() = assertEquals("english", WebViewLocaleResolver.bcp47ToGoldberg("en"))
    @Test fun bcp47_en_us_to_english() = assertEquals("english", WebViewLocaleResolver.bcp47ToGoldberg("en-US"))
    // ambiguous primaries pick the unsuffixed Steam default, not the regional variant.
    @Test fun bcp47_es_primary_is_spanish() = assertEquals("spanish", WebViewLocaleResolver.bcp47ToGoldberg("es"))
    @Test fun bcp47_pt_primary_is_portuguese() = assertEquals("portuguese", WebViewLocaleResolver.bcp47ToGoldberg("pt"))
    @Test fun bcp47_zh_primary_is_schinese() = assertEquals("schinese", WebViewLocaleResolver.bcp47ToGoldberg("zh"))
    // unsupported language → null (caller falls back to english).
    @Test fun bcp47_unsupported_returns_null() = assertEquals(null, WebViewLocaleResolver.bcp47ToGoldberg("th-TH"))
    @Test fun bcp47_blank_returns_null() = assertEquals(null, WebViewLocaleResolver.bcp47ToGoldberg("  "))

    // ---- resolveSteamLanguage (Goldberg name via shared precedence) ----

    // container goldberg wins → round-trips back to the same Steam name.
    @Test
    fun steamlang_container_goldberg_wins() {
        val got = WebViewLocaleResolver.resolveSteamLanguage("german", "en", Locale.forLanguageTag("ja-JP"))
        assertEquals("german", got)
    }

    // english sentinel falls through to appLanguage, mapped to a Steam name.
    @Test
    fun steamlang_falls_through_to_appLanguage() {
        val got = WebViewLocaleResolver.resolveSteamLanguage("english", "fr-FR", Locale.forLanguageTag("ja-JP"))
        assertEquals("french", got)
    }

    // appLanguage as region-less primary still maps.
    @Test
    fun steamlang_appLanguage_primary_subtag() {
        val got = WebViewLocaleResolver.resolveSteamLanguage(null, "de", Locale.forLanguageTag("ja-JP"))
        assertEquals("german", got)
    }

    // empty appLanguage → system locale, mapped.
    @Test
    fun steamlang_falls_through_to_system_locale() {
        val got = WebViewLocaleResolver.resolveSteamLanguage(null, "", Locale.forLanguageTag("pt-BR"))
        assertEquals("brazilian", got)
    }

    // unsupported resolved tag → english (never fails launch).
    @Test
    fun steamlang_unsupported_falls_back_to_english() {
        val got = WebViewLocaleResolver.resolveSteamLanguage(null, "th-TH", Locale.forLanguageTag("th-TH"))
        assertEquals("english", got)
    }

    // full chain exhausted → en-US → english.
    @Test
    fun steamlang_final_fallback_english() {
        val got = WebViewLocaleResolver.resolveSteamLanguage(null, "", Locale.forLanguageTag("und"))
        assertEquals("english", got)
    }
}
