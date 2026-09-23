package app.gamenative.texturepack

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TexturePackPolicyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val fullBlocks = mapOf(
        "bc6h" to "4x4",
        "bc7" to "4x4",
        "bc5" to "4x4",
        "bc4" to "4x4",
        "bc3" to "4x4",
        "bc2" to "4x4",
        "bc1" to "6x6",
    )

    @Test
    fun `policy env lists every format in a stable order with the size cap`() {
        assertEquals(
            "bc1=6x6,bc2=4x4,bc3=4x4,bc4=4x4,bc5=4x4,bc7=4x4,bc6h=4x4,maxdim=1024",
            TexturePackGate.policyEnv(PackPolicy(true, fullBlocks, 1024)),
        )
    }

    @Test
    fun `policy env omits the size cap when the server sends none`() {
        assertEquals(
            "bc1=6x6,bc2=4x4,bc3=4x4,bc4=4x4,bc5=4x4,bc7=4x4,bc6h=4x4",
            TexturePackGate.policyEnv(PackPolicy(true, fullBlocks, null)),
        )
    }

    @Test
    fun `policy env is absent when the policy is disabled or missing`() {
        assertNull(TexturePackGate.policyEnv(PackPolicy(false, fullBlocks, 1024)))
        assertNull(TexturePackGate.policyEnv(null))
        assertNull(TexturePackGate.policyEnv(PackPolicy(true, emptyMap(), null)))
    }

    @Test
    fun `policy env drops malformed pairs and sorts unknown formats last`() {
        val policy = PackPolicy(true, mapOf("zz9" to "8x8", "bc1" to "6x6,maxdim=1", "bc7" to "4x4", "b=c" to "4x4"), 0)
        assertEquals("bc7=4x4,zz9=8x8", TexturePackGate.policyEnv(policy))
    }

    @Test
    fun `policy survives the container extra round trip`() {
        val policy = PackPolicy(true, fullBlocks, 2048)
        assertEquals(policy, TexturePackGate.decodePolicy(TexturePackGate.encodePolicy(policy)))
        assertEquals("", TexturePackGate.encodePolicy(null))
        assertNull(TexturePackGate.decodePolicy(""))
        assertNull(TexturePackGate.decodePolicy("{not json"))
    }

    @Test
    fun `pack and register responses carry the policy`() {
        val body = """{"entries":[],"policy":{"enabled":true,"blocks":{"bc1":"6x6"},"maxDim":1024}}"""
        assertEquals(PackPolicy(true, mapOf("bc1" to "6x6"), 1024), json.decodeFromString(PackResponse.serializer(), body).policy)
        val register = """{"fingerprint":"abc","policy":{"enabled":false,"blocks":{}}}"""
        assertEquals(PackPolicy(false, emptyMap(), null), json.decodeFromString(PackRegisterResponse.serializer(), register).policy)
        assertNull(json.decodeFromString(PackResponse.serializer(), """{"entries":[]}""").policy)
    }

    @Test
    fun `register request carries the full resolution flag`() {
        val files = listOf(PrepareFileEntry("a.pak", 1L))
        val on = json.parseToJsonElement(
            json.encodeToString(PackRegisterRequest.serializer(), PackRegisterRequest("steam", "1", files, needsFullRes = true)),
        ).jsonObject
        val off = json.parseToJsonElement(
            json.encodeToString(PackRegisterRequest.serializer(), PackRegisterRequest("steam", "1", files)),
        ).jsonObject
        assertEquals(JsonPrimitive(true), on["needsFullRes"])
        assertEquals(JsonPrimitive(false), off["needsFullRes"])
    }

    @Test
    fun `full resolution marker is detected and left in place`() {
        val dir = temp.newFolder("texcache")
        assertFalse(TexturePackGate.needsFullRes(dir))
        val marker = File(dir, TexturePackGate.NEEDS_FULL_RES_MARKER).also { it.createNewFile() }
        assertTrue(TexturePackGate.needsFullRes(dir))
        assertTrue(marker.exists())
        assertFalse(TexturePackKeys.isKey(marker.name))
        assertEquals(emptyList<String>(), TexturePackSync.packKeys(dir))
    }

    @Test
    fun `out tags fall back to the defaults without a policy`() {
        assertEquals("a6", TexturePackKeys.outTagFor("bc1"))
        assertEquals("a4", TexturePackKeys.outTagFor("bc7"))
        assertEquals("a4", TexturePackKeys.outTagFor("bc3"))
        assertEquals("h4", TexturePackKeys.outTagFor("bc6hu"))
        assertEquals("h4", TexturePackKeys.outTagFor("bc6hs"))
    }

    @Test
    fun `out tags follow the policy blocks`() {
        val policy = PackPolicy(true, mapOf("bc1" to "4x4", "bc7" to "6x6", "bc3" to "8x8", "bc6h" to "4x4"), null)
        assertEquals("a4", TexturePackKeys.outTagFor("bc1", policy))
        assertEquals("a6", TexturePackKeys.outTagFor("bc7", policy))
        assertEquals("a8", TexturePackKeys.outTagFor("bc3", policy))
        assertEquals("h4", TexturePackKeys.outTagFor("bc6hu", policy))
        assertEquals("a4", TexturePackKeys.outTagFor("bc5", policy))
    }

    @Test
    fun `out tags ignore disabled policies and unknown blocks`() {
        val disabled = PackPolicy(false, mapOf("bc1" to "4x4"), null)
        assertEquals("a6", TexturePackKeys.outTagFor("bc1", disabled))
        val odd = PackPolicy(true, mapOf("bc1" to "5x5", "bc6h" to "6x6"), null)
        assertEquals("a6", TexturePackKeys.outTagFor("bc1", odd))
        assertEquals("h4", TexturePackKeys.outTagFor("bc6hs", odd))
    }

    @Test
    fun `keys follow the policy passed in`() {
        val policy = PackPolicy(true, mapOf("bc1" to "4x4"), null)
        val dir = temp.newFolder("keys")
        val source = TexturePackSync.sourceFile(dir, "bc1_64x64_0000000000000001.a6")
        assertEquals("bc1_64x64_0000000000000001.a4", TexturePackSync.keyOf(source, policy))
        assertEquals("bc1_64x64_0000000000000001.a6", TexturePackSync.keyOf(source))
        assertEquals("bc1_4x4_0000000000000002.a4", TexturePackKeys.canonicalKey("bc1_4x4_0000000000000002.a6", policy))
        assertEquals("bc1_4x4_0000000000000002.a4", TextureCacheStore.contentKey("bc1", 4, 4, 2L, policy))
    }

    @Test
    fun `h4 is a valid out tag`() {
        val key = "bc6hu_8x8_0000000000000003.h4"
        assertTrue(TexturePackKeys.isKey(key))
        assertTrue("h4" in TexturePackKeys.OUT_TAGS)
        assertEquals(4, TexturePackKeys.blockSizeOf(key))
        assertEquals("bc6hu_8x8_0000000000000003", TexturePackKeys.stem(key))
        assertEquals(key, TexturePackKeys.canonicalKey("bc6hu_8x8_0000000000000003.a4"))
        assertTrue(TexturePackKeys.isCanonical(key))
        assertEquals(64L, TextureCacheStore.expectedAstcSizeForKey(key))
    }

    @Test
    fun `prune removes entries superseded by an h4 entry`() {
        val dir = temp.newFolder("prune-h4")
        val key = "bc6hs_4x4_0000000000000004.h4"
        val entry = File(dir, key).also { it.writeBytes(ByteArray(16)) }
        val a4 = File(dir, "bc6hs_4x4_0000000000000004.a4").also { it.writeBytes(ByteArray(16)) }
        val src = File(dir, "bc6hs_4x4_0000000000000004.src").also { it.writeBytes(ByteArray(8)) }

        assertEquals(2, TextureCacheStore.pruneSuperseded(dir, key))
        assertTrue(entry.isFile)
        assertFalse(a4.exists())
        assertFalse(src.exists())
    }

    @Test
    fun `prune removes an h4 entry superseded by another tag`() {
        val dir = temp.newFolder("prune-from-h4")
        val key = "bc6hu_4x4_0000000000000005.a4"
        File(dir, key).writeBytes(ByteArray(16))
        val h4 = File(dir, "bc6hu_4x4_0000000000000005.h4").also { it.writeBytes(ByteArray(16)) }

        assertEquals(1, TextureCacheStore.pruneSuperseded(dir, key))
        assertFalse(h4.exists())
    }

    @Test
    fun `gpu transcode follows the preference on the texture pack path`() {
        assertEquals("1", TexturePackGate.gpuTranscodeEnv(sync = true, prefOn = true, containerTranscoderIsGpu = false))
        assertEquals("0", TexturePackGate.gpuTranscodeEnv(sync = true, prefOn = false, containerTranscoderIsGpu = true))
    }

    @Test
    fun `gpu transcode follows the container setting without texture packs`() {
        assertEquals("1", TexturePackGate.gpuTranscodeEnv(sync = false, prefOn = false, containerTranscoderIsGpu = true))
        assertEquals("0", TexturePackGate.gpuTranscodeEnv(sync = false, prefOn = true, containerTranscoderIsGpu = false))
    }
}
