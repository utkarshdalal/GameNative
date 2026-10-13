package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SteamUtilsLanguageTest {

    private fun lang(language: String, country: String = "") =
        SteamUtils.steamLanguageForAppLocale(Locale(language, country))

    @Test
    fun mapsSteamSpecificLanguages() {
        assertEquals("koreana", lang("ko"))
        assertEquals("brazilian", lang("pt", "BR"))
        assertEquals("portuguese", lang("pt", "PT"))
        assertEquals("portuguese", lang("pt"))
    }

    @Test
    fun splitsSpanishByRegion() {
        assertEquals("spanish", lang("es", "ES"))
        assertEquals("spanish", lang("es"))
        assertEquals("latam", lang("es", "MX"))
        assertEquals("latam", lang("es", "AR"))
    }

    @Test
    fun splitsChineseByRegionAndScript() {
        assertEquals("schinese", lang("zh", "CN"))
        assertEquals("schinese", lang("zh"))
        assertEquals("tchinese", lang("zh", "TW"))
        assertEquals("tchinese", lang("zh", "HK"))
        assertEquals("tchinese", lang("zh", "MO"))
        assertEquals(
            "tchinese",
            SteamUtils.steamLanguageForAppLocale(Locale.Builder().setLanguage("zh").setScript("Hant").build()),
        )
    }

    @Test
    fun fallsBackToEnglishDisplayName() {
        assertEquals("english", lang("en"))
        assertEquals("french", lang("fr"))
        assertEquals("german", lang("de"))
        assertEquals("italian", lang("it"))
        assertEquals("japanese", lang("ja"))
        assertEquals("russian", lang("ru"))
        assertEquals("polish", lang("pl"))
        assertEquals("ukrainian", lang("uk"))
    }
}
