package app.gamenative.html5.profile

import app.gamenative.runtime.WebViewContainer
import app.gamenative.runtime.WebViewRuntime
import com.winlator.container.Container
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// robolectric needed — WebViewRuntime.id -> Container.RUNTIME_WEBVIEW, and Container's
// <clinit> reads Environment.getExternalStoragePublicDirectory. same reason as
// ContainerRuntimeJsonTest. the tests themselves use tempFolder overrides so no
// DownloadService.baseExternalAppDirPath init is required.
@RunWith(RobolectricTestRunner::class)
class WebViewContainerJsonTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun fullContainer(id: String = "x") = WebViewContainer(
        id = id,
        installPath = "/tmp/$id",
        entryPoint = "index.html",
        engineProfile = "pack:rmmv",
        inputMap = "pointer-with-tap-detection",
    )

    @Test
    fun full_container_roundtrips_via_json() {
        val c = fullContainer("termina")
        val encoded = json.encodeToString(c)
        val decoded = json.decodeFromString<WebViewContainer>(encoded)
        assertEquals(c, decoded)
    }

    @Test
    fun minimal_json_decodes_with_defaults() {
        // id + installPath + engineProfile required; rest defaulted.
        val body = """
            {"id":"y","installPath":"/tmp/y","engineProfile":"pack:c3"}
        """.trimIndent()
        val decoded = json.decodeFromString<WebViewContainer>(body)
        assertEquals("y", decoded.id)
        assertEquals("index.html", decoded.entryPoint)
        // default: "" (unset → pack default wins via resolveInputMode at launch).
        // pre-phase-3 saved containers keep their literal value because kotlinx.serialization
        // only fills the default when the key is ABSENT from JSON.
        assertEquals("", decoded.inputMap)
        assertEquals("webview", decoded.runtime)
    }

    @Test
    fun save_then_load_via_tempdir_roundtrips() {
        val slug = "termina-abcd"
        val dir = tempFolder.newFolder(slug)
        val file = File(dir, "config.json")
        val c = fullContainer(slug)

        WebViewContainer.save(slug, c, file = file)
        assertTrue("config.json not written", file.exists())

        val loaded = WebViewContainer.load(slug, file = file)
        assertNotNull(loaded)
        assertEquals(c, loaded)
    }

    @Test
    fun load_nonexistent_file_returns_null() {
        val file = File(tempFolder.newFolder("empty"), "nope.json")
        val loaded = WebViewContainer.load("missing", file = file)
        assertNull(loaded)
    }

    @Test
    fun load_malformed_json_returns_null() {
        val file = File(tempFolder.newFolder("bad"), "config.json")
        file.writeText("{not json")
        val loaded = WebViewContainer.load("bad", file = file)
        assertNull(loaded)
    }

    @Test
    fun configFile_path_ends_with_html5_containers_slug_config_json() {
        // configFile uses DownloadService.baseExternalAppDirPath at module level —
        // in robolectric, the object's backing is an empty string but path shape is
        // deterministic.
        val file = WebViewContainer.configFile("termina-abcd")
        val path = file.absolutePath
        assertTrue(
            "expected path to end with html5-containers/termina-abcd/config.json, got: $path",
            path.endsWith("html5-containers/termina-abcd/config.json"),
        )
    }

    @Test
    fun runtime_string_matches_webviewruntime_id() {
        // drift test — if future rename changes Container.RUNTIME_WEBVIEW, the literal
        // default in WebViewContainer.runtime must be updated too.
        assertEquals("webview", WebViewRuntime.id)
        assertEquals("webview", Container.RUNTIME_WEBVIEW)
    }

    @Test
    fun existing_persisted_json_preserves_explicit_inputMap_literal() {
        // "existing containers keep literal value on load" — when the JSON key is
        // PRESENT with "pointer-with-tap-detection", it wins over the new "" default.
        val body = """
            {"id":"pre-phase3","installPath":"/tmp/pre","engineProfile":"pack:c3","inputMap":"pointer-with-tap-detection"}
        """.trimIndent()
        val decoded = json.decodeFromString<WebViewContainer>(body)
        assertEquals("pointer-with-tap-detection", decoded.inputMap)
    }

    @Test
    fun new_container_json_without_inputMap_key_decodes_as_empty() {
        // new container created post-phase-3 without the key still picks up "" default
        // → resolveInputMode handles it at launch time.
        val body = """
            {"id":"post-phase3","installPath":"/tmp/post","engineProfile":"pack:rmmv"}
        """.trimIndent()
        val decoded = json.decodeFromString<WebViewContainer>(body)
        assertEquals("", decoded.inputMap)
    }

    // per-container ControlsProfile id field. 0L = unset sentinel.
    @Test
    fun controlsProfileId_default_is_zero() {
        val c = WebViewContainer(
            id = "STEAM_379210",
            installPath = "/tmp",
            engineProfile = "pack:electron",
        )
        assertEquals(0L, c.controlsProfileId)
    }

    @Test
    fun controlsProfileId_roundtrip_preserves_value() {
        val slug = "x"
        val dir = tempFolder.newFolder(slug)
        val file = File(dir, "config.json")
        val c = WebViewContainer(
            id = "STEAM_379210",
            installPath = "/tmp",
            engineProfile = "pack:electron",
            controlsProfileId = 42L,
        )
        WebViewContainer.save(slug = slug, container = c, file = file)
        val loaded = WebViewContainer.load(slug = slug, file = file)
        assertEquals(42L, loaded?.controlsProfileId)
    }

    // overlay opacity + visible toggle persistence.
    @Test
    fun overlayOpacity_default_matches_icv_default() {
        val c = WebViewContainer(
            id = "x",
            installPath = "/tmp",
            engineProfile = "pack:rmmv",
        )
        assertEquals(0.4f, c.overlayOpacity, 0.0001f)
        // default false (controller-first ux; users opt in via QuickMenu).
        assertEquals(false, c.overlayVisible)
    }

    @Test
    fun overlay_fields_roundtrip_via_save_load() {
        val slug = "x"
        val dir = tempFolder.newFolder(slug)
        val file = File(dir, "config.json")
        val c = WebViewContainer(
            id = "x",
            installPath = "/tmp",
            engineProfile = "pack:rmmv",
            overlayOpacity = 0.75f,
            overlayVisible = false,
        )
        WebViewContainer.save(slug = slug, container = c, file = file)
        val loaded = WebViewContainer.load(slug = slug, file = file)
        assertEquals(0.75f, loaded?.overlayOpacity ?: 0f, 0.0001f)
        assertEquals(false, loaded?.overlayVisible)
    }

    @Test
    fun overlay_fields_missing_default_to_icv_defaults() {
        // pre-overlay-rework persisted JSONs lack the keys → kotlinx.serialization fills declared defaults.
        val dir = tempFolder.newFolder("old")
        val file = File(dir, "config.json")
        file.writeText(
            """
            {
              "id": "x",
              "installPath": "/tmp",
              "engineProfile": "pack:rmmv"
            }
            """.trimIndent(),
        )
        val loaded = WebViewContainer.load(slug = "x", file = file)
        assertEquals(0.4f, loaded?.overlayOpacity ?: 0f, 0.0001f)
        // default flipped to false.
        assertEquals(false, loaded?.overlayVisible)
    }

    @Test
    fun controlsProfileId_missing_field_defaults_to_zero() {
        // simulate an old persisted container JSON (pre- 6) — no controlsProfileId key.
        // ignoreUnknownKeys=true (WebViewContainer.json config) fills missing field with declared default.
        val dir = tempFolder.newFolder("old")
        val file = File(dir, "config.json")
        file.writeText(
            """
            {
              "id": "STEAM_379210",
              "installPath": "/tmp",
              "engineProfile": "pack:electron",
              "schemaVersion": 1
            }
            """.trimIndent(),
        )
        val loaded = WebViewContainer.load(slug = "x", file = file)
        assertEquals(0L, loaded?.controlsProfileId)
    }

    // greenworksCloudObserved drift-lock trio.
    @Test
    fun greenworksCloudObserved_default_is_false() {
        val c = fullContainer("gw-default")
        assertEquals(false, c.greenworksCloudObserved)
    }

    @Test
    fun greenworksCloudObserved_roundtrip_preserves_true() {
        val slug = "gw-trip"
        val dir = tempFolder.newFolder(slug)
        val file = File(dir, "config.json")
        val c = fullContainer(slug).copy(greenworksCloudObserved = true)
        WebViewContainer.save(slug = slug, container = c, file = file)
        val loaded = WebViewContainer.load(slug = slug, file = file)
        assertNotNull(loaded)
        assertEquals(true, loaded?.greenworksCloudObserved)
    }

    @Test
    fun greenworksCloudObserved_missing_field_defaults_to_false() {
        val slug = "gw-legacy"
        val dir = tempFolder.newFolder(slug)
        val file = File(dir, "config.json")
        // pre-phase-9 JSON shape: no greenworksCloudObserved key. ignoreUnknownKeys=true
        // is what allows forward-compat reads; default-fill is what allows the new key
        // missing on legacy disk to read as false.
        file.writeText(
            """{"id":"gw-legacy","installPath":"/tmp/gw-legacy","engineProfile":"pack:electron","schemaVersion":1}"""
        )
        val loaded = WebViewContainer.load(slug = slug, file = file)
        assertNotNull(loaded)
        assertEquals(false, loaded?.greenworksCloudObserved)
    }
}
