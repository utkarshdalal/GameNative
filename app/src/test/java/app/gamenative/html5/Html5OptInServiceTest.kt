package app.gamenative.html5

import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import com.winlator.container.ContainerData
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import app.gamenative.utils.CustomGameScanner

// robolectric required — WebViewContainer.configFile reads DownloadService.baseExternalAppDirPath,
// Container clinit reads Environment.getExternalStoragePublicDirectory, and context.getString
// needs an Application context. MockK mockkObject used for CustomGameScanner folder lookup
// (same approach used for CustomGameScannerFingerprintTest).
@RunWith(RobolectricTestRunner::class)
class Html5OptInServiceTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        mockkObject(CustomGameScanner)
        mockkObject(SteamService.Companion)
        // default miss — individual tests override
        every { CustomGameScanner.getFolderPathFromAppId(any()) } returns null
        // default STEAM resolver returns nonexistent path → null File
        every { SteamService.getAppDirPath(any()) } returns "/nonexistent/steam/path"
    }

    @After
    fun tearDown() {
        unmockkObject(CustomGameScanner)
        unmockkObject(SteamService.Companion)
    }

    // zip helper — mirrors EngineFingerprinterTest (inline, sharedTest extraction deferred).
    private fun writeZip(target: File, entries: Map<String, ByteArray>): File {
        java.util.zip.ZipOutputStream(target.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return target
    }

    private fun emptyContainerData() = ContainerData()

    // html5-containers config file resolves via WebViewContainer.configFile(slug) in production.
    // robolectric's externalFilesDir is deterministic, so the test can assert file presence
    // at the resolved path.
    private fun expectedConfigFileFor(slug: String): File = WebViewContainer.configFile(slug)

    @Test
    fun opt_in_with_rmmv_folder_writes_webview_container_and_returns_matched() {
        val folder = tempFolder.newFolder("TERMINA")
        File(folder, "www/js").mkdirs()
        File(folder, "www/data").mkdirs()
        File(folder, "www/js/rpg_core.js").writeText("")
        File(folder, "www/data/System.json").writeText("{}")

        every { CustomGameScanner.getFolderPathFromAppId("CUSTOM_GAME_42") } returns folder.absolutePath

        val result = kotlinx.coroutines.runBlocking {
            Html5OptInService.optIn(context, "CUSTOM_GAME_42", emptyContainerData())
        }
        assertEquals(Html5OptInService.Result.Matched, result)

        // slug derivation is deterministic from folder-name + id.
        val slug = Html5SlugUtil.slug(folder.name, 42)
        val configFile = expectedConfigFileFor(slug)
        assertTrue("config.json not written at $configFile", configFile.exists())
        val json = configFile.readText()
        assertTrue("engineProfile pack:rmmv not found: $json", json.contains("\"engineProfile\": \"pack:rmmv\""))
        assertTrue("webRoot www not found: $json", json.contains("\"webRoot\": \"www\""))
    }

    @Test
    fun opt_in_with_c3_in_zip_writes_webview_container_with_zip_prefix_webRoot() {
        val folder = tempFolder.newFolder("SolCesto")
        writeZip(
            File(folder, "package.nw"),
            mapOf(
                "scripts/c3runtime.js" to ByteArray(0),
                "index.html" to "<html></html>".toByteArray(),
            ),
        )

        every { CustomGameScanner.getFolderPathFromAppId("CUSTOM_GAME_7") } returns folder.absolutePath

        val result = kotlinx.coroutines.runBlocking {
            Html5OptInService.optIn(context, "CUSTOM_GAME_7", emptyContainerData())
        }
        assertEquals(Html5OptInService.Result.Matched, result)

        val slug = Html5SlugUtil.slug(folder.name, 7)
        val configFile = expectedConfigFileFor(slug)
        assertTrue("config.json not written", configFile.exists())
        val json = configFile.readText()
        assertTrue("engineProfile pack:c3 not found: $json", json.contains("\"engineProfile\": \"pack:c3\""))
        assertTrue("webRoot zip:package.nw not found: $json", json.contains("\"webRoot\": \"zip:package.nw\""))
    }

    @Test
    fun opt_in_with_empty_folder_returns_no_match_and_writes_nothing() {
        val folder = tempFolder.newFolder("Empty")
        every { CustomGameScanner.getFolderPathFromAppId("CUSTOM_GAME_99") } returns folder.absolutePath

        val result = kotlinx.coroutines.runBlocking {
            Html5OptInService.optIn(context, "CUSTOM_GAME_99", emptyContainerData())
        }
        assertTrue("expected NoMatch, got $result", result is Html5OptInService.Result.NoMatch)
        val noMatch = result as Html5OptInService.Result.NoMatch
        assertTrue(
            "message should contain install path: ${noMatch.message}",
            noMatch.message.contains(folder.absolutePath),
        )

        val slug = Html5SlugUtil.slug(folder.name, 99)
        assertFalse(
            "config.json should not be written on miss",
            expectedConfigFileFor(slug).exists(),
        )
    }

    @Test
    fun opt_in_with_non_custom_game_appId_returns_cannot_resolve() {
        val result = kotlinx.coroutines.runBlocking {
            Html5OptInService.optIn(context, "STEAM_12345", emptyContainerData())
        }
        assertTrue(
            "expected CannotResolveInstallPath, got $result",
            result is Html5OptInService.Result.CannotResolveInstallPath,
        )
        val cannot = result as Html5OptInService.Result.CannotResolveInstallPath
        assertNotNull(cannot.message)
        assertTrue("message should be non-empty", cannot.message.isNotEmpty())
    }

    @Test
    fun opt_in_with_custom_game_appId_but_missing_folder_returns_cannot_resolve() {
        // default mock returns null; no override needed.
        val result = kotlinx.coroutines.runBlocking {
            Html5OptInService.optIn(context, "CUSTOM_GAME_42", emptyContainerData())
        }
        assertTrue(
            "expected CannotResolveInstallPath, got $result",
            result is Html5OptInService.Result.CannotResolveInstallPath,
        )
    }

    @Test
    fun result_matched_is_singleton_object() {
        // data object invariant — single instance across the JVM.
        assertSame(Html5OptInService.Result.Matched, Html5OptInService.Result.Matched)
    }

    @Test
    fun resolveFingerprintPath_steam_returns_dir_when_path_exists() {
        val folder = tempFolder.newFolder("Termina")
        every { SteamService.getAppDirPath(2171440) } returns folder.absolutePath

        val resolved = Html5OptInService.resolveFingerprintPath("STEAM_2171440")
        assertNotNull("resolveFingerprintPath returned null for valid STEAM appId", resolved)
        assertEquals(folder.absolutePath, resolved!!.absolutePath)
    }

    @Test
    fun resolveFingerprintPath_steam_returns_null_when_path_missing() {
        every { SteamService.getAppDirPath(99999) } returns "/nonexistent/path/that/should/not/exist"

        val resolved = Html5OptInService.resolveFingerprintPath("STEAM_99999")
        assertNull("expected null for STEAM appId pointing at nonexistent dir", resolved)
    }

    @Test
    fun resolveFingerprintPath_steam_returns_null_for_non_int_suffix() {
        val resolved = Html5OptInService.resolveFingerprintPath("STEAM_notanumber")
        assertNull("expected null for malformed STEAM_ suffix", resolved)
    }

    @Test
    fun resolveFingerprintPath_returns_null_for_unsupported_store_prefixes() {
        assertNull(Html5OptInService.resolveFingerprintPath("GOG_12345"))
        assertNull(Html5OptInService.resolveFingerprintPath("EPIC_67890"))
        assertNull(Html5OptInService.resolveFingerprintPath("AMAZON_55555"))
        assertNull(Html5OptInService.resolveFingerprintPath("UNKNOWN_1"))
    }
}
