package app.gamenative.html5.asar

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.gamenative.html5.host.IndexInjectionConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// the page runs at its real https origin: the prefix comes from disk (or main's inline entry), runtime paths
// from us, everything else from the network.
@RunWith(RobolectricTestRunner::class)
class AsarAssetInterceptorProtocolPageTest {

    @get:Rule val tempFolder = TemporaryFolder()
    private lateinit var context: Context

    private val page = ProtocolServedPage(
        host = "example.test",
        aliasHosts = setOf("www.example.test"),
        prefix = "/__game__/",
        entryName = "index.html",
        entryHtml = """<!DOCTYPE html><body><script src="./js/main.js"></script></body>""",
        resourceDir = "site",
        userAgentSuffix = null,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun interceptor(archive: ElectronArchive, root: File) = AsarAssetInterceptor(
        context,
        archive,
        shimUrls = listOf("/_shims/fs.js"),
        injection = IndexInjectionConfig(electronPreloadUrl = "/out/preload.js"),
        protocolPage = page,
        protocolRoot = root,
    )

    private fun fixture(): Pair<File, File> {
        val asar = AsarTestFixtures.writeFixture(
            tempFolder.newFile("app.asar"),
            linkedMapOf(
                "package.json" to """{"name":"g","main":"out/main.js"}""".toByteArray(),
                "out/preload.js" to "//preload".toByteArray(),
            ),
        )
        val root = tempFolder.newFolder("site")
        File(root, "js").mkdirs()
        File(root, "js/main.js").writeText("boot()")
        File(root, "Sounds").mkdirs()
        File(root, "Sounds/theme.ogg").writeBytes(ByteArray(8))
        return asar to root
    }

    private fun body(r: android.webkit.WebResourceResponse) = r.data.readBytes().toString(Charsets.UTF_8)

    @Test
    fun entryIsMainsPageWithShimsAndPreloadInjected() {
        val (asar, root) = fixture()
        AsarArchive.open(asar).use { a ->
            val r = interceptor(a, root).serveProtocolPage(Uri.parse("https://example.test/__game__/index.html"))!!
            assertEquals("text/html", r.mimeType)
            val html = body(r)
            assertTrue(html.contains("""<script src="/_shims/fs.js"></script>"""))
            assertTrue(html.contains("""<script src="/out/preload.js"></script>"""))
            assertTrue(html.indexOf("/_shims/fs.js") < html.indexOf("./js/main.js"))
        }
    }

    @Test
    fun prefixFilesComeFromTheResourceDirCaseInsensitively() {
        val (asar, root) = fixture()
        AsarArchive.open(asar).use { a ->
            val i = interceptor(a, root)
            assertEquals("boot()", body(i.serveProtocolPage(Uri.parse("https://example.test/__game__/js/main.js"))!!))
            assertNotNull("media is answered here, not left to the live site", i.serveProtocolPage(Uri.parse("https://www.example.test/__game__/sounds/THEME.ogg")))
        }
    }

    @Test
    fun missingPrefixFileIs404NotNetwork() {
        val (asar, root) = fixture()
        AsarArchive.open(asar).use { a ->
            val r = interceptor(a, root).serveProtocolPage(Uri.parse("https://example.test/__game__/nope.js"))
            assertEquals(404, r!!.statusCode)
        }
    }

    @Test
    fun traversalOutOfTheRootIsRefused() {
        val (asar, root) = fixture()
        File(root.parentFile, "secret.txt").writeText("x")
        AsarArchive.open(asar).use { a ->
            val r = interceptor(a, root).serveProtocolPage(Uri.parse("https://example.test/__game__/..%2Fsecret.txt"))
            assertEquals(404, r!!.statusCode)
        }
    }

    @Test
    fun runtimePathsAndPreloadAreServedOnThePagesHost() {
        val (asar, root) = fixture()
        AsarArchive.open(asar).use { a ->
            val r = interceptor(a, root).serveProtocolPage(Uri.parse("https://example.test/out/preload.js"))
            assertEquals("//preload", body(r!!))
        }
    }

    @Test
    fun everythingElseGoesToTheNetwork() {
        val (asar, root) = fixture()
        AsarArchive.open(asar).use { a ->
            val i = interceptor(a, root)
            assertNull(i.serveProtocolPage(Uri.parse("https://example.test/api/levels")))
            assertNull(i.serveProtocolPage(Uri.parse("https://example.test/package.json")))
            assertNull(i.serveProtocolPage(Uri.parse("https://cdn.other.test/__game__/js/main.js")))
            assertNull(i.serveProtocolPage(Uri.parse("http://example.test/__game__/js/main.js")))
        }
    }

    @Test
    fun theEntryBlocksServiceWorkersBeforeAnyOtherScript() {
        val (asar, root) = fixture()
        AsarArchive.open(asar).use { a ->
            val html = body(interceptor(a, root).serveProtocolPage(Uri.parse("https://example.test/__game__/index.html"), mainFrame = true)!!)
            val block = html.indexOf(AsarAssetInterceptor.NO_SERVICE_WORKER_SHIM_URL)
            assertTrue(block >= 0)
            assertTrue(block < html.indexOf("/_shims/fs.js"))
        }
    }

    // reloads and POSTs never reach shouldOverrideUrlLoading; a live-site document must not load with the bridges.
    @Test
    fun aTopLevelDocumentOutsideThePrefixIsNeverTheLiveSite() {
        val (asar, root) = fixture()
        AsarArchive.open(asar).use { a ->
            val i = interceptor(a, root)
            listOf(
                "https://example.test/account/settings",
                "https://www.example.test/__game__/index.html",
                "https://other.test/",
            ).forEach { url ->
                val r = i.serveProtocolPage(Uri.parse(url), mainFrame = true)!!
                assertEquals(url, 403, r.statusCode)
                assertTrue(body(r).contains("url=https://example.test/__game__/index.html"))
            }
            assertNull("a subresource there still goes to the network", i.serveProtocolPage(Uri.parse("https://example.test/account/settings")))
        }
    }

    @Test
    fun documentsUnderThePrefixOnThePrimaryHostAreTheGame() {
        val (asar, root) = fixture()
        AsarArchive.open(asar).use { a ->
            val i = interceptor(a, root)
            assertTrue(i.isGameDocument(Uri.parse("https://example.test/__game__/index.html")))
            assertTrue(i.isGameDocument(Uri.parse("https://example.test/__game__/sub/page.html?x=1")))
            assertFalse(i.isGameDocument(Uri.parse("https://example.test/account")))
            assertFalse(i.isGameDocument(Uri.parse("https://www.example.test/__game__/index.html")))
            assertFalse(i.isGameDocument(Uri.parse("http://example.test/__game__/index.html")))
        }
    }

    @Test
    fun theDocumentPolicyKeepsFramesAndFormsInsideThePrefix() {
        val (asar, root) = fixture()
        AsarArchive.open(asar).use { a ->
            val policy = interceptor(a, root).documentPolicy
            assertTrue(policy.contains("frame-src https://example.test/__game__/ "))
            assertTrue(policy.contains("form-action https://example.test/__game__/"))
            assertFalse(policy.contains("'self'"))
        }
    }
}
