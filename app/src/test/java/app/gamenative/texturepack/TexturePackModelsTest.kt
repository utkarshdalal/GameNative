package app.gamenative.texturepack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TexturePackModelsTest {
    private val clientJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val files = listOf(PrepareFileEntry("Content/GUI.pkg", 10L))

    private fun encode(json: Json, request: PackRegisterRequest) =
        json.parseToJsonElement(json.encodeToString(PackRegisterRequest.serializer(), request)).jsonObject

    @Test
    fun registerRequestCarriesTitleWhenSet() {
        val body = encode(clientJson, PackRegisterRequest("steam", "1145350", files, title = "Hades II"))
        assertEquals(JsonPrimitive("Hades II"), body["title"])
    }

    @Test
    fun registerRequestSendsNullTitleWhenUnset() {
        val body = encode(clientJson, PackRegisterRequest("steam", "1145350", files))
        assertEquals(JsonNull, body["title"])
    }

    @Test
    fun registerRequestOmitsTitleWithoutDefaults() {
        val body = encode(Json, PackRegisterRequest("steam", "1145350", files))
        assertFalse(body.containsKey("title"))
    }
}
