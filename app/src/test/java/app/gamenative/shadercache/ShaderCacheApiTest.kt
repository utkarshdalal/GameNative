package app.gamenative.shadercache

import app.gamenative.api.ShaderCacheApi
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ShaderCacheApiTest {
    private val dx12 = ShaderCacheKey("steam:1", "dxvk-2.6.1", "vkd3d-3.0.0")
    private val dx11 = ShaderCacheKey("gog:2", "dxvk-1.10.3", null)

    @Test fun checkRequestSendsEachGamesLayerVersions() {
        val games = ShaderCacheApi.checkRequest(listOf(dx12, dx11)).getJSONArray("games")

        assertEquals(2, games.length())
        val first = games.getJSONObject(0)
        assertEquals("steam:1", first.getString("game"))
        assertEquals("dxvk-2.6.1", first.getString("dxvk"))
        assertEquals("vkd3d-3.0.0", first.getString("vkd3d"))
        val second = games.getJSONObject(1)
        assertEquals("dxvk-1.10.3", second.getString("dxvk"))
        assertFalse(second.has("vkd3d"))
    }

    @Test fun checkResponseIsKeyedByGameAndLayerVersion() {
        val body = JSONObject()
            .put("pools", JSONObject())
            .put(
                "subpools",
                JSONObject()
                    .put(
                        "steam:1",
                        org.json.JSONArray()
                            .put(JSONObject().put("engine", "dxvk").put("engineVersion", "2.6.1").put("version", 5).put("size", 10).put("entries", 3))
                            .put(JSONObject().put("engine", "vkd3d").put("engineVersion", "2.14.1").put("version", 9)),
                    )
                    .put("gog:2", org.json.JSONArray()),
            )
            .toString()

        assertEquals(
            mapOf(ShaderPool("steam:1", "dxvk-2.6.1") to ShaderCacheApi.PoolVersion(5, 10, 3)),
            ShaderCacheApi.parseCheck(body, listOf(dx12, dx11)),
        )
    }

    @Test fun responsesWithoutTheExpectedFieldAreFailures() {
        assertThrows(IOException::class.java) { ShaderCacheApi.parseCheck("""{"pools":{}}""", listOf(dx12)) }
        assertThrows(IOException::class.java) { ShaderCacheApi.parsePools("""{"ok":true}""") }
    }

    @Test fun poolsAreKeyedByLayer() {
        val body = """{"pools":[{"engine":"vkd3d","engineVersion":"3.0.0","version":4,"url":"u","sha256":"AB","size":7,"entries":2}]}"""

        assertEquals(
            mapOf("vkd3d-3.0.0" to ShaderCacheApi.Pool("vkd3d-3.0.0", 4, "u", "ab", 7, 2)),
            ShaderCacheApi.parsePools(body),
        )
        assertEquals(emptyMap<String, ShaderCacheApi.Pool>(), ShaderCacheApi.parsePools("""{"pools":[]}"""))
    }

    @Test fun poolsRequestSendsTheKeyInTheBody() {
        val dx12Body = ShaderCacheApi.poolsRequest(dx12)
        assertEquals("steam:1", dx12Body.getString("game"))
        assertEquals("dxvk-2.6.1", dx12Body.getString("dxvk"))
        assertEquals("vkd3d-3.0.0", dx12Body.getString("vkd3d"))
        assertFalse(ShaderCacheApi.poolsRequest(dx11).has("vkd3d"))
    }

    @Test fun integrityTokenIsForTheExactBytesSentAndSkipsAttestation() = runBlocking {
        var hashed: ByteArray? = null
        var attested = false
        val signed = ShaderCacheApi.signBody(
            ShaderCacheApi.poolsRequest(dx11),
            "abcd",
            { bytes -> hashed = bytes; "token" },
            { attested = true; "nonce" to listOf("cert") },
        )

        assertEquals("token", signed.integrityToken)
        assertTrue(hashed!!.contentEquals(signed.bytes))
        assertFalse(attested)
        val sent = JSONObject(String(signed.bytes))
        assertEquals("abcd", sent.getString("signingCertSha256"))
        assertFalse(sent.has("nonce"))
        assertFalse(sent.has("attestationChain"))
    }

    @Test fun attestationIsSentWhenNoIntegrityTokenIsAvailable() = runBlocking {
        val signed = ShaderCacheApi.signBody(
            ShaderCacheApi.poolsRequest(dx11),
            null,
            { null },
            { "nonce" to listOf("c1", "c2") },
        )

        assertNull(signed.integrityToken)
        val sent = JSONObject(String(signed.bytes))
        assertFalse(sent.has("signingCertSha256"))
        assertEquals("nonce", sent.getString("nonce"))
        assertEquals(2, sent.getJSONArray("attestationChain").length())
        assertEquals("gog:2", sent.getString("game"))
    }
}
