package app.gamenative.service.epic

import org.junit.Assert.assertEquals
import org.junit.Test

class EpicConstantsTest {

    @Test
    fun `every container language has an Epic locale`() {
        assertEquals(
            EpicConstants.CONTAINER_LANGUAGE_TO_EPIC_INSTALL_TAGS.keys,
            EpicConstants.CONTAINER_LANGUAGE_TO_EPIC_LOCALE.keys,
        )
    }

    @Test
    fun `container language maps to the Epic launcher locale`() {
        assertEquals("fr", EpicConstants.containerLanguageToEpicLocale("french"))
        assertEquals("fr", EpicConstants.containerLanguageToEpicLocale("French"))
        assertEquals("zh-Hans", EpicConstants.containerLanguageToEpicLocale("schinese"))
        assertEquals("zh-Hant", EpicConstants.containerLanguageToEpicLocale("tchinese"))
        assertEquals("pt-BR", EpicConstants.containerLanguageToEpicLocale("brazilian"))
        assertEquals("es-MX", EpicConstants.containerLanguageToEpicLocale("latam"))
    }

    @Test
    fun `unknown container language falls back to the default locale`() {
        assertEquals("en-US", EpicConstants.containerLanguageToEpicLocale("klingon"))
        assertEquals("en-US", EpicConstants.containerLanguageToEpicLocale(""))
    }
}
