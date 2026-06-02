package app.gamenative.html5.profile

import android.content.Context
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import app.gamenative.html5.host.resolveShimUrls
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.ByteArrayInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.serialization.decodeFromString

@RunWith(RobolectricTestRunner::class)
class ProfileSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun minimal_json_parses_with_defaults() {
        val body = """{"engine":"pack:rmmv"}"""
        val parsed = json.decodeFromString<EngineProfile>(body)
        assertEquals("pack:rmmv", parsed.engine)
        assertEquals("index.html", parsed.entryPoint)
        assertEquals(emptyList<Patch>(), parsed.patches)
        // input + saves default to non-null host-level defaults — pack JSONs no longer
        // declare these when they match the default (pointer-with-tap-detection input,
        // leveldb-origin-rewrite saves with pcOrigin "file://").
        assertEquals("pointer-with-tap-detection", parsed.input?.mode)
        assertEquals("leveldb-origin-rewrite", parsed.saves?.sync?.mechanism)
        assertEquals("file://", parsed.saves?.sync?.pcOrigin)
        assertEquals(emptyList<String>(), parsed.shims)
    }

    @Test
    fun full_json_roundtrips() {
        val full = EngineProfile(
            engine = "pack:c3",
            entryPoint = "entry.html",
            patches = listOf(
                Patch.AudioExtensionRemap(fromExt = ".rpgmvo", toExt = ".ogg"),
                Patch.UrlPathRedirect(from = "/a", to = "/b"),
            ),
            input = InputSpec(mode = "native-controller"),
            saves = SaveSpec(
                sync = SaveSyncSpec(pcOrigin = "file://", mechanism = "leveldb-origin-rewrite"),
            ),
            shims = listOf("/_shims/steamworks.js"),
        )
        val encoded = json.encodeToString(full)
        val reparsed = json.decodeFromString<EngineProfile>(encoded)
        assertEquals(full, reparsed)
    }

    @Test
    fun unknown_keys_tolerated() {
        val body = """{"engine":"pack:c3","futureField":"x","nested":{"y":1}}"""
        val parsed = json.decodeFromString<EngineProfile>(body)
        assertEquals("pack:c3", parsed.engine)
    }

    @Test
    fun patches_parse_structurally() {
        val body = """
            {"engine":"pack:rmmv",
             "patches":[
               {"type":"url-redirect","from":"/a","to":"/b"},
               {"type":"audio-ext-remap","fromExt":".rpgmvo","toExt":".ogg"}
             ]}
        """.trimIndent()
        val parsed = json.decodeFromString<EngineProfile>(body)
        assertEquals(2, parsed.patches.size)
        assertTrue(parsed.patches[0] is Patch.UrlPathRedirect)
        assertEquals("/a", (parsed.patches[0] as Patch.UrlPathRedirect).from)
        assertEquals("/b", (parsed.patches[0] as Patch.UrlPathRedirect).to)
        assertTrue(parsed.patches[1] is Patch.AudioExtensionRemap)
    }

    @Test
    fun saves_spec_parses() {
        val body = """
            {"engine":"pack:c3",
             "saves":{
               "sync":{
                 "pcOrigin":"file://",
                 "mechanism":"leveldb-origin-rewrite"
               }
             }}
        """.trimIndent()
        val parsed = json.decodeFromString<EngineProfile>(body)
        assertNotNull(parsed.saves)
        assertEquals("file://", parsed.saves!!.sync?.pcOrigin)
        assertEquals("leveldb-origin-rewrite", parsed.saves!!.sync?.mechanism)
    }

    @Test
    fun input_spec_parses() {
        val body = """{"engine":"pack:rmmv","input":{"mode":"native-controller"}}"""
        val parsed = json.decodeFromString<EngineProfile>(body)
        assertEquals("native-controller", parsed.input?.mode)
    }

    // ---- ProfileRegistry asset loader ----

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun loadPackDefaults_unknown_pack_returns_null() {
        assertNull(ProfileRegistry.loadPackDefaults(context, "pack:nonexistent-pack-xyz"))
    }

    @Test
    fun loadPackDefaults_blank_pack_returns_null() {
        assertNull(ProfileRegistry.loadPackDefaults(context, ""))
    }

    @Test
    fun loadPackDefaults_rmmv_loads_from_assets() {
        val result = ProfileRegistry.loadPackDefaults(context, "pack:rmmv")
        assertNotNull("expected rmmv pack to load", result)
        assertEquals("pack:rmmv", result!!.engine)
        assertEquals("index.html", result.entryPoint)
    }

    @Test
    fun loadPackDefaults_c3_loads_from_assets() {
        val result = ProfileRegistry.loadPackDefaults(context, "pack:c3")
        assertNotNull("expected c3 pack to load", result)
        assertEquals("pack:c3", result!!.engine)
    }

    @Test
    fun loadPackDefaults_electron_hasLevelDbOriginRewriteSavesBlock() {
        val result = ProfileRegistry.loadPackDefaults(context, "pack:electron")
        assertNotNull("electron pack must load", result)
        val saves = result!!.saves
        assertNotNull("electron.json must have saves block", saves)
        assertEquals("leveldb-origin-rewrite", saves!!.sync?.mechanism)
        assertEquals("file://", saves.sync?.pcOrigin)
    }

    @Test
    fun loadPackDefaults_c3_hasPcOrigin() {
        val result = ProfileRegistry.loadPackDefaults(context, "pack:c3")
        assertNotNull("c3 pack must load", result)
        val saves = result!!.saves
        assertNotNull("c3.json must have saves block", saves)
        assertEquals("leveldb-origin-rewrite", saves!!.sync?.mechanism)
        assertEquals("file://", saves.sync?.pcOrigin)
    }

    // integration guard for the pack-shim auto-injection refactor: load each SHIPPED pack JSON
    // from assets and run the production resolveShimUrls. catches a wrong/missing packShimPlacement
    // value in the JSON AND a resolver regression -- the WebViewScreenShimResolutionTest unit tests
    // use hand-built profiles; this exercises the real data path end-to-end. pack shim id + url are
    // derived by convention (pack:foo → pack-foo → /_shims/packs/foo.js).
    private fun realPackShimUrls(engineId: String): List<String> {
        val profile = ProfileRegistry.loadPackDefaults(context, engineId)
        assertNotNull("$engineId must load from assets", profile)
        return resolveShimUrls(profile, resolvedMode = "native-controller")
    }

    @Test
    fun realC3Json_prependsPackC3ShimBeforeTouch() {
        val urls = realPackShimUrls("pack:c3")
        val c3 = urls.indexOf("/_shims/packs/c3.js")
        val touch = urls.indexOf("/_shims/touch.js")
        assertTrue("c3 pack shim must be injected: $urls", c3 >= 0)
        assertTrue("c3 pack shim must precede touch.js: c3=$c3 touch=$touch", c3 < touch)
    }

    @Test
    fun realAppendPackJsons_injectTheirPackShim() {
        assertTrue("rmmv", realPackShimUrls("pack:rmmv").contains("/_shims/packs/rmmv.js"))
        assertTrue("gms", realPackShimUrls("pack:gms").contains("/_shims/packs/gms.js"))
        assertTrue("nwjs", realPackShimUrls("pack:nwjs").contains("/_shims/packs/nwjs.js"))
        assertTrue("tyrano", realPackShimUrls("pack:tyrano").contains("/_shims/packs/tyrano.js"))
    }

    @Test
    fun realNonePackJsons_doNotAutoInjectAPackShim() {
        // godot + unity ship no pack shim of their own (placement defaults NONE).
        assertTrue("godot", !realPackShimUrls("pack:godot").contains("/_shims/packs/godot.js"))
        assertTrue("unity", !realPackShimUrls("pack:unity").contains("/_shims/packs/unity.js"))
    }

    @Test
    fun resolveProfile_falls_back_to_pack_defaults_when_no_overrides() {
        val result = ProfileRegistry.resolveProfile(context, appId = null, engineId = "pack:rmmv")
        assertNotNull(result)
        assertEquals("pack:rmmv", result!!.engine)
    }

    @Test
    fun resolveProfile_unknown_engineId_returns_null() {
        assertNull(ProfileRegistry.resolveProfile(context, appId = "STEAM_1", engineId = "pack:does-not-exist"))
    }

    // a deliberately-malformed pack JSON must swallow + log + return null. callers
    // (Html5OptInService → Result.PackLoadFailure) depend on this — throw here = crash there.
    @Test
    fun loadPackDefaults_malformedJson_returnsNullAndDoesNotThrow() {
        val mockContext = mockk<Context>()
        val mockAssets = mockk<AssetManager>()
        every { mockContext.assets } returns mockAssets
        every { mockAssets.open("html5/packs/badpack.json") } answers {
            ByteArrayInputStream("{ this is not valid json".toByteArray())
        }
        assertNull(ProfileRegistry.loadPackDefaults(mockContext, "pack:badpack"))
    }

    // patches.json sibling — same swallow contract.
    @Test
    fun resolveProfile_malformedPatchesJson_fallsBackToPackDefaults() {
        // real rmmv pack loads from APK assets via the real context; only the patches asset is
        // mock-injected as malformed. resolveProfile must still return the pack defaults.
        val realAssets = context.assets
        val mockContext = mockk<Context>()
        val mockAssets = mockk<AssetManager>()
        every { mockContext.assets } returns mockAssets
        every { mockAssets.open("html5/packs/rmmv.json") } answers {
            realAssets.open("html5/packs/rmmv.json")
        }
        every { mockAssets.open("html5/packs/rmmv-patches.json") } answers {
            ByteArrayInputStream("{ this is not valid json".toByteArray())
        }
        val result = ProfileRegistry.resolveProfile(mockContext, appId = "STEAM_12345", engineId = "pack:rmmv")
        assertNotNull("malformed patches.json must not crash resolveProfile", result)
        assertEquals("pack:rmmv", result!!.engine)
    }
}
