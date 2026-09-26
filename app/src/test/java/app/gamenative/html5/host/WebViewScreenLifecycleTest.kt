package app.gamenative.html5.host

import androidx.test.core.app.ApplicationProvider
import app.gamenative.events.AndroidEvent
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.DownloadService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// covers the pure-jvm pieces of WebViewScreen + WebViewScreenViewModel. deliberately does NOT
// render the composable: ShadowWebView isn't production-faithful, so DisposableEffect teardown
// order + onExit sequencing are left to on-device testing.
@RunWith(RobolectricTestRunner::class)
class WebViewScreenLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        // DownloadService.baseExternalAppDirPath has a private setter -- populate via the
        // robolectric context.
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
        // save-sync subscribes via AndroidEvent.WebViewDestroyed; a rename must break the build.
        assertEquals("WebViewDestroyed", AndroidEvent.WebViewDestroyed::class.simpleName)
    }

    @Test
    fun slugFromAppId_returns_null_when_html5_containers_dir_missing() {
        // html5-containers dir does not exist under the robolectric external-files tree
        // unless seeded. expect null rather than an exception.
        val result = WebViewScreenViewModel.slugFromAppId("CUSTOM_GAME_99999")
        assertNull(result)
    }

    // slugFromAppId is the last slug consumer (JSON-dir reverse lookup for
    // Html5Routing.isHtml5App). it matches on container.id, never on the dir name, which is what
    // lets it migrate a legacy name on the way past.
    @Test
    fun slugFromAppId_migratesALegacySlugAndReturnsTheCanonicalName() {
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

            assertEquals("test-steam-123", result)
            assertTrue("legacy dir must be renamed, not copied", !dir.exists())
            assertTrue("config must travel", File(root, "test-steam-123/config.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun slugFromAppId_returnsACanonicalDirUnchanged() {
        val root = File(DownloadService.baseExternalAppDirPath, "html5-containers")
        val dir = File(root, "test-steam-456").apply { mkdirs() }
        WebViewContainer.save(
            "test-steam-456",
            WebViewContainer(id = "STEAM_456", installPath = "/tmp/test", engineProfile = "pack:rmmv"),
            File(dir, "config.json"),
        )

        try {
            assertEquals("test-steam-456", WebViewScreenViewModel.slugFromAppId("STEAM_456"))
        } finally {
            root.deleteRecursively()
        }
    }
}
