package app.gamenative.html5.profile

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// kotlinx default classDiscriminator is "type" — tests self-document.
class PatchSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun audio_ext_remap_roundtrip() {
        val body = """{"type":"audio-ext-remap","fromExt":".rpgmvo","toExt":".ogg"}"""
        val parsed = json.decodeFromString<Patch>(body)
        assertTrue(parsed is Patch.AudioExtensionRemap)
        assertEquals(".rpgmvo", (parsed as Patch.AudioExtensionRemap).fromExt)
        assertEquals(".ogg", parsed.toExt)
        val re = json.encodeToString<Patch>(parsed)
        assertEquals(parsed, json.decodeFromString<Patch>(re))
    }

    @Test fun url_redirect_roundtrip() {
        val body = """{"type":"url-redirect","from":"/bgm.mp3","to":"/audio/bgm.ogg"}"""
        val parsed = json.decodeFromString<Patch>(body)
        assertTrue(parsed is Patch.UrlPathRedirect)
        assertEquals("/bgm.mp3", (parsed as Patch.UrlPathRedirect).from)
        val re = json.encodeToString<Patch>(parsed)
        assertEquals(parsed, json.decodeFromString<Patch>(re))
    }

    @Test fun response_body_replace_roundtrip() {
        val body = """{"type":"response-body-replace","pathPattern":"main\\.js$","find":"nwjs","replace":"html5"}"""
        val parsed = json.decodeFromString<Patch>(body)
        assertTrue(parsed is Patch.ResponseBodyReplace)
        val rb = parsed as Patch.ResponseBodyReplace
        assertEquals("main\\.js$", rb.pathPattern)
        assertEquals("nwjs", rb.find)
        assertEquals("html5", rb.replace)
    }

    @Test fun asset_decrypt_roundtrip() {
        val body = """{"type":"asset-decrypt","kind":"rpgmv-xor"}"""
        val parsed = json.decodeFromString<Patch>(body)
        assertTrue(parsed is Patch.AssetDecrypt)
        assertEquals("rpgmv-xor", (parsed as Patch.AssetDecrypt).kind)
    }

    @Test fun all_variants_encode_with_type_discriminator() {
        val all = listOf<Patch>(
            Patch.AudioExtensionRemap(".rpgmvo", ".ogg"),
            Patch.UrlPathRedirect("/a", "/b"),
            Patch.ResponseBodyReplace("x$", "a", "b"),
            Patch.AssetDecrypt("rpgmv-xor"),
        )
        val types = all.map { json.encodeToString<Patch>(it) }
            .map { json.parseToJsonElement(it).jsonObject["type"]!!.toString().trim('"') }
        assertEquals(
            listOf("audio-ext-remap", "url-redirect", "response-body-replace", "asset-decrypt"),
            types,
        )
    }
}
