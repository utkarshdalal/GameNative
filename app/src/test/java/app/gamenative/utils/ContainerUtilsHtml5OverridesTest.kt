package app.gamenative.utils

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.events.AndroidEvent
import app.gamenative.html5.Html5OptInService
import app.gamenative.html5.Html5SlugUtil
import app.gamenative.runtime.WebViewContainer
import com.winlator.container.Container
import com.winlator.container.ContainerData
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// persistHtml5ContainerOverrides round-trips ONLY inputMap into WebViewContainer.json.
// suspendPolicy is intentionally NOT in WebViewContainer -- it lives on the wine Container as a
// SINGLE per-container preference shared by both runtimes (WebViewScreen reads it via
// ContainerUtils.getContainer(...).suspendPolicy).
@RunWith(RobolectricTestRunner::class)
class ContainerUtilsHtml5OverridesTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        mockkObject(CustomGameScanner)
        every { CustomGameScanner.getFolderPathFromAppId(any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun seedHtml5Container(folderName: String, idPart: Int): String {
        // minimal RMMV layout so optIn.fingerprint matches and writes a baseline
        // WebViewContainer.json.
        val folder = tempFolder.newFolder(folderName)
        File(folder, "www/js").mkdirs()
        File(folder, "www/data").mkdirs()
        File(folder, "www/js/rpg_core.js").writeText("")
        File(folder, "www/data/System.json").writeText("{}")

        val appId = "CUSTOM_GAME_$idPart"
        every { CustomGameScanner.getFolderPathFromAppId(appId) } returns folder.absolutePath

        val result = kotlinx.coroutines.runBlocking {
            Html5OptInService.optIn(context, appId, ContainerData())
        }
        assertEquals("optIn must succeed for fixture", Html5OptInService.Result.Matched, result)
        return appId
    }

    @Test
    fun inputMap_round_trips_through_persistHtml5ContainerOverrides() {
        // a GeneralTab inputMap edit must propagate into WebViewContainer.json.
        val appId = seedHtml5Container("TerminaA", 101)
        val root = Html5OptInService.resolveFingerprintPath(appId)!!
        val slug = Html5SlugUtil.slug(root.name, "CUSTOM_GAME_101")

        ContainerUtils.persistHtml5ContainerOverrides(
            appId,
            ContainerData(inputMap = "native-controller"),
        )
        val updated = WebViewContainer.load(slug)
        assertNotNull(updated)
        assertEquals("native-controller", updated!!.inputMap)
    }

    @Test
    fun suspendPolicy_is_NOT_persisted_to_WebViewContainer() {
        // persistHtml5ContainerOverrides must not resurrect a parallel suspendPolicy copy -- both
        // runtimes read it from the wine Container.
        val appId = seedHtml5Container("TerminaB", 102)
        val root = Html5OptInService.resolveFingerprintPath(appId)!!
        val slug = Html5SlugUtil.slug(root.name, "CUSTOM_GAME_102")
        val baselineJson = WebViewContainer.configFile(slug).readText()

        // non-default suspendPolicy + default inputMap: only the untracked suspendPolicy differs,
        // so the JSON must be unchanged.
        ContainerUtils.persistHtml5ContainerOverrides(
            appId,
            ContainerData(suspendPolicy = Container.SUSPEND_POLICY_AUTO),
        )

        val afterJson = WebViewContainer.configFile(slug).readText()
        assertEquals(
            "WebViewContainer.json must be byte-identical — suspendPolicy belongs on wine Container",
            baselineJson,
            afterJson,
        )
        assertEquals(
            "WebViewContainer JSON must not contain a suspendPolicy key",
            false,
            afterJson.contains("\"suspendPolicy\""),
        )
    }

    @Test
    fun reapplying_same_inputMap_is_noop_safe() {
        // early-return branch -- no exception, second load returns the same data.
        val appId = seedHtml5Container("TerminaC", 103)
        val root = Html5OptInService.resolveFingerprintPath(appId)!!
        val slug = Html5SlugUtil.slug(root.name, "CUSTOM_GAME_103")
        val baseline = WebViewContainer.load(slug)!!

        ContainerUtils.persistHtml5ContainerOverrides(
            appId,
            ContainerData(inputMap = baseline.inputMap),
        )
        val again = WebViewContainer.load(slug)!!
        assertEquals(baseline.inputMap, again.inputMap)
    }

    // GOG/Epic html5 ids must resolve the same sidecar optIn wrote. a Custom+Steam-only id check made
    // every GOG/Epic Config save look like a first opt-in (sidecar reset to defaults) and dropped dialog edits.
    @Test
    fun gog_sidecar_is_found_and_overrides_persist() {
        val folder = tempFolder.newFolder("MoonstoneIsland")
        File(folder, "www/js").mkdirs()
        File(folder, "www/data").mkdirs()
        File(folder, "www/js/rpg_core.js").writeText("")
        File(folder, "www/data/System.json").writeText("{}")
        val appId = "GOG_1516178466"
        mockkObject(Html5OptInService)
        every { Html5OptInService.resolveFingerprintPath(appId) } returns folder
        val result = kotlinx.coroutines.runBlocking { Html5OptInService.optIn(context, appId, ContainerData()) }
        assertEquals("optIn must succeed for fixture", Html5OptInService.Result.Matched, result)

        assertNotNull("sidecar written by optIn must be found for a GOG id", ContainerUtils.loadWebViewContainerForAppId(appId))

        ContainerUtils.persistHtml5ContainerOverrides(appId, ContainerData(inputMap = "native-controller", renderScale = 0.75f))
        val updated = ContainerUtils.loadWebViewContainerForAppId(appId)!!
        assertEquals("native-controller", updated.inputMap)
        assertEquals(0.75f, updated.renderScale, 0f)
    }

    // the runtime-flip library event carries the store-local int id, not the prefixed one.
    @Test
    fun runtimeFlipEvent_parses_store_prefixed_ids() {
        assertEquals(
            AndroidEvent.LibraryInstallStatusChanged(2171440, GameSource.STEAM),
            ContainerUtils.runtimeFlipEvent("STEAM_2171440"),
        )
        assertEquals(
            AndroidEvent.LibraryInstallStatusChanged(1516178466, GameSource.GOG),
            ContainerUtils.runtimeFlipEvent("GOG_1516178466"),
        )
        assertEquals(
            AndroidEvent.LibraryInstallStatusChanged(5, GameSource.CUSTOM_GAME),
            ContainerUtils.runtimeFlipEvent("CUSTOM_GAME_5"),
        )
        assertNull(ContainerUtils.runtimeFlipEvent("2171440"))
    }
}
