package app.gamenative.shadercache

import app.gamenative.api.ShaderCacheApi
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
