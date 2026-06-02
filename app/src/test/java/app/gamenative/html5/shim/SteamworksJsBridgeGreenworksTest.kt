package app.gamenative.html5.shim

import app.gamenative.html5.host.WebViewScreenViewModel
import app.gamenative.html5.savesync.GreenworksCloudClient
import app.gamenative.runtime.WebViewContainer
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// / Plan-06 controller-seam tests for SteamworksJsBridge greenworks methods.
@RunWith(RobolectricTestRunner::class)
class SteamworksJsBridgeGreenworksTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun bridge(containerId: String = "STEAM_1454400", appId: Int = 1454400): SteamworksJsBridge {
        val gseDir = tempFolder.newFolder("gse-${System.nanoTime()}")
        return SteamworksJsBridge(
            containerId = containerId,
            appId = appId,
            gseDir = gseDir,
        )
    }

    @Before
    fun setUp() {
        mockkObject(WebViewScreenViewModel.Companion)
        mockkObject(WebViewContainer.Companion)
        mockkObject(GreenworksCloudClient)
        every { GreenworksCloudClient.getQuotaJson(any()) } returns
            """{"total":104857600,"available":104857600}"""
    }

    @After
    fun tearDown() { unmockkAll() }

    // helper: stub configFile + load + save in their fully-resolved 3-arg/2-arg forms.
    // mockk records calls AT the resolved overload after Kotlin's $default trampoline runs,
    // so verifying the bridge's `save(slug, container)` requires matching `save(slug, any(), any())`.
    private fun stubContainerIO(
        slug: String,
        loaded: WebViewContainer,
        configFileStub: File = tempFolder.newFile("config-${System.nanoTime()}.json"),
    ) {
        every { WebViewContainer.configFile(slug) } returns configFileStub
        every { WebViewContainer.load(slug, any()) } returns loaded
        every { WebViewContainer.save(slug, any(), any()) } returns Unit
    }

    @Test
    fun markGreenworksCloudObserved_firstCall_persistsFlag() {
        val slug = "cookie-clicker-XXXX"
        every { WebViewScreenViewModel.slugFromAppId("STEAM_1454400") } returns slug
        val initial = WebViewContainer(
            id = "STEAM_1454400",
            installPath = "/tmp",
            engineProfile = "pack:electron",
            greenworksCloudObserved = false,
        )
        stubContainerIO(slug, initial)

        bridge().markGreenworksCloudObserved()

        verify(exactly = 1) {
            WebViewContainer.save(
                slug,
                match { it.greenworksCloudObserved },
                any(),
            )
        }
    }

    @Test
    fun markGreenworksCloudObserved_subsequentCalls_noOp() {
        val slug = "cookie-clicker-XXXX"
        every { WebViewScreenViewModel.slugFromAppId(any()) } returns slug
        stubContainerIO(
            slug,
            WebViewContainer(
                id = "STEAM_1454400",
                installPath = "/tmp",
                engineProfile = "pack:electron",
                greenworksCloudObserved = false,
            ),
        )

        val b = bridge()
        b.markGreenworksCloudObserved()
        b.markGreenworksCloudObserved()
        b.markGreenworksCloudObserved()

        // session debounce: persist runs at most once.
        verify(exactly = 1) { WebViewContainer.save(any(), any(), any()) }
    }

    @Test
    fun markGreenworksCloudObserved_noSlug_skipsPersist() {
        every { WebViewScreenViewModel.slugFromAppId(any()) } returns null

        bridge().markGreenworksCloudObserved()

        verify(exactly = 0) { WebViewContainer.save(any(), any(), any()) }
    }

    @Test
    fun getCloudQuota_returnsParseableJson() {
        val raw = bridge().getCloudQuota()
        assertNotNull(raw)
        val parsed = JSONObject(raw)
        // both keys present and numeric.
        assertTrue("total field missing", parsed.has("total"))
        assertTrue("available field missing", parsed.has("available"))
        assertTrue("total non-negative", parsed.getLong("total") >= 0)
        assertTrue("available non-negative", parsed.getLong("available") >= 0)
    }

    @Test
    fun getCloudQuota_secondCallReturnsCache() {
        val b = bridge()
        val first = b.getCloudQuota()
        val second = b.getCloudQuota()
        // session cache returns the same string.
        assertEquals(first, second)
        // underlying source called at most once.
        verify(exactly = 1) { GreenworksCloudClient.getQuotaJson(any()) }
    }

    @Test
    fun captureGreenworksOutboundSnapshot_signalsAwait() {
        val b = bridge()
        val snapshot = """{"cookieClickerSave.txt":"YWJjMTIzCg=="}"""
        b.captureGreenworksOutboundSnapshot(snapshot)
        // post-capture await returns true within the budget.
        assertTrue("latch did not fire after capture", b.awaitGreenworksSnapshot(1_000L))
        // captured JSON readable verbatim.
        assertEquals(snapshot, b.consumeGreenworksOutboundSnapshot())
    }

    @Test
    fun awaitGreenworksSnapshot_timeoutWithoutCapture() {
        val b = bridge()
        // no capture call → latch never fires → await returns false on timeout.
        assertFalse("latch fired without capture call", b.awaitGreenworksSnapshot(50L))
        // consume returns null when no capture happened.
        assertEquals(null, b.consumeGreenworksOutboundSnapshot())
    }
}
