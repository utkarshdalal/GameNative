package app.gamenative.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EulaInfoTest {
    private val global = EulaInfo(id = "582010_eula_1")
    private val worldwide = EulaInfo(id = "1245620_eula_0", countries = listOf("AD", "AE", "US", "ZW"))
    private val japan = EulaInfo(id = "1245620_eula_1", countries = listOf("JP"))

    @Test
    fun entryWithoutCountriesAppliesEverywhere() {
        assertTrue(global.appliesTo("US"))
        assertTrue(global.appliesTo(null))
        assertTrue(global.appliesTo(""))
    }

    @Test
    fun entryWithCountriesMatchesCaseInsensitively() {
        assertTrue(japan.appliesTo("jp"))
        assertTrue(japan.appliesTo(" JP "))
        assertFalse(japan.appliesTo("US"))
        assertFalse(japan.appliesTo(null))
    }

    @Test
    fun knownCountryKeepsOnlyMatchingEntries() {
        assertEquals(listOf(worldwide), listOf(worldwide, japan).filterForCountry("US"))
        assertEquals(listOf(japan), listOf(worldwide, japan).filterForCountry("JP"))
        assertEquals(listOf(global), listOf(global, japan).filterForCountry("US"))
    }

    @Test
    fun unknownCountryKeepsEveryEntry() {
        assertEquals(listOf(global, japan), listOf(global, japan).filterForCountry(""))
        assertEquals(listOf(japan, worldwide), listOf(japan, worldwide).filterForCountry(null))
    }

    @Test
    fun storedEntryWithoutCountriesDecodes() {
        val decoded = Json.decodeFromString<List<EulaInfo>>("""[{"id":"582010_eula_1","name":"EULA","url":"u","version":"2"}]""")
        assertEquals(emptyList<String>(), decoded[0].countries)
    }
}
