package app.gamenative.utils

import android.content.Context
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// fix-A regression: persistHtml5ContainerOverrides round-trips ONLY inputMap into
// WebViewContainer.json. suspendPolicy is intentionally NOT in WebViewContainer — it lives
// on the wine Container as a SINGLE per-container preference shared between wine and html5
// runtimes. WebViewScreen reads suspendPolicy via ContainerUtils.getContainer(...).suspendPolicy.
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
        // build a minimal RMMV layout so optIn.fingerprint matches → writes baseline
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
        // inputMap path: GeneralTab edit must propagate into WebViewContainer.json.
        val appId = seedHtml5Container("TerminaA", 101)
        val root = Html5OptInService.resolveFingerprintPath(appId)!!
        val slug = Html5SlugUtil.slug(root.name, 101)

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
        // INVARIANT: suspendPolicy is a single per-container pref owned by wine Container.
        // WebViewContainer.kt has no field for it. persistHtml5ContainerOverrides must not
        // resurrect a parallel copy — both runtimes read from the wine Container.
        val appId = seedHtml5Container("TerminaB", 102)
        val root = Html5OptInService.resolveFingerprintPath(appId)!!
        val slug = Html5SlugUtil.slug(root.name, 102)
        val baselineJson = WebViewContainer.configFile(slug).readText()

        // dialog edit with a non-default suspendPolicy + matching default inputMap → only
        // suspendPolicy "would" change; since we don't track it, the JSON must be unchanged.
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
        // and WebViewContainer must not have a suspendPolicy serialized field
        assertEquals(
            "WebViewContainer JSON must not contain a suspendPolicy key",
            false,
            afterJson.contains("\"suspendPolicy\""),
        )
    }

    @Test
    fun reapplying_same_inputMap_is_noop_safe() {
        // covers the early-return branch — no exception, just second load returns same data.
        val appId = seedHtml5Container("TerminaC", 103)
        val root = Html5OptInService.resolveFingerprintPath(appId)!!
        val slug = Html5SlugUtil.slug(root.name, 103)
        val baseline = WebViewContainer.load(slug)!!

        ContainerUtils.persistHtml5ContainerOverrides(
            appId,
            ContainerData(inputMap = baseline.inputMap),
        )
        val again = WebViewContainer.load(slug)!!
        assertEquals(baseline.inputMap, again.inputMap)
    }
}
