package app.gamenative.html5.host

import androidx.test.core.app.ApplicationProvider
import app.gamenative.events.AndroidEvent
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.DownloadService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// lifecycle test — covers the pure-jvm pieces of WebViewScreen +
// WebViewScreenViewModel. deliberately does NOT render the composable: ShadowWebView
// isn't production-faithful and the DisposableEffect teardown
// order + onExit sequencing is covered by manual 07) and future
// androidTest suite. here we lock mimeFor mappings + WebViewDestroyed event drift +
// slug lookup fallback when html5-containers dir missing.

// 2 slugFromAppId survives as the html5-containers/<slug>/ JSON-dir
// reverse-lookup seam (origin + save-sync paths no longer use it). positive-path test
// added below to defend the remaining consumer (Html5Routing.isHtml5App).
@RunWith(RobolectricTestRunner::class)
class WebViewScreenLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        // DownloadService.baseExternalAppDirPath has a private setter — populate via
        // the robolectric context. sibling tests (Html5OptInServiceTest etc) use this pattern.
        DownloadService.populateDownloadService(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun mimeFor_js_returns_application_javascript() {
        assertEquals("application/javascript", mimeFor("foo.js"))
    }

    @Test
    fun mimeFor_unknown_extension_returns_octet_stream() {
        assertEquals("application/octet-stream", mimeFor("foo.unknown"))
    }

    @Test
    fun mimeFor_is_case_insensitive() {
        assertEquals("text/html", mimeFor("foo.HTML"))
    }

    @Test
    fun webViewDestroyed_event_is_named_correctly() {
        // drift lock: save-sync subscribes via AndroidEvent.WebViewDestroyed;
        // rename breaks catch at build.
        assertEquals("WebViewDestroyed", AndroidEvent.WebViewDestroyed::class.simpleName)
    }

    @Test
    fun slugFromAppId_returns_null_when_html5_containers_dir_missing() {
        // html5-containers dir does not exist under the robolectric external-files tree
        // unless seeded. expect null rather than an exception.
        val result = WebViewScreenViewModel.slugFromAppId("CUSTOM_GAME_99999")
        assertNull(result)
    }

    // 2 slugFromAppId is the LAST remaining slug consumer (JSON-dir reverse
    // lookup for Html5Routing.isHtml5App). lock the happy path so html5 routing keeps
    // working after legacy-slug-helper cleanup.
    @Test
    fun slugFromAppId_returns_dir_name_when_json_id_matches() {
        // seed html5-containers/<some-slug>/config.json with id=STEAM_123; expect slug
        // reverse-lookup to return <some-slug>.
        val root = File(DownloadService.baseExternalAppDirPath, "html5-containers")
        val dir = File(root, "test-slug-abc1").apply { mkdirs() }
        val cfg = File(dir, "config.json")
        val container = WebViewContainer(
            id = "STEAM_123",
            installPath = "/tmp/test",
            engineProfile = "pack:rmmv",
        )
        WebViewContainer.save("test-slug-abc1", container, cfg)

        try {
            val result = WebViewScreenViewModel.slugFromAppId("STEAM_123")
            assertEquals("test-slug-abc1", result)
        } finally {
            root.deleteRecursively()
        }
    }
}
