package app.gamenative.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShareDisplayNameTest {

    private fun roundTrip(state: Map<String, JsonElement>): JsonObject =
        Json.parseToJsonElement(JsonObject(state).toString()) as JsonObject

    @Test
    fun savedNameSurvivesTheStateFile() {
        val state = linkedMapOf<String, JsonElement>("order" to JsonPrimitive("x"))
        ShareDisplayName.write(state, "  Sam the Painter ")
        val read = roundTrip(state)
        assertEquals("Sam the Painter", ShareDisplayName.read(read))
        assertEquals("x", (read["order"] as JsonPrimitive).content)
    }

    @Test
    fun blankNameIsNotStoredAndClearsAnOldOne() {
        val state = linkedMapOf<String, JsonElement>()
        ShareDisplayName.write(state, "Sam")
        ShareDisplayName.write(state, "   ")
        assertFalse(state.containsKey(ShareDisplayName.STATE_KEY))
        assertEquals("", ShareDisplayName.read(roundTrip(state)))
    }

    @Test
    fun missingOrMalformedNameReadsEmpty() {
        assertEquals("", ShareDisplayName.read(null))
        assertEquals("", ShareDisplayName.read(JsonObject(emptyMap())))
        assertEquals("", ShareDisplayName.read(JsonObject(mapOf(ShareDisplayName.STATE_KEY to JsonPrimitive(42)))))
        assertEquals("", ShareDisplayName.read(JsonObject(mapOf(ShareDisplayName.STATE_KEY to JsonObject(emptyMap())))))
    }
}
