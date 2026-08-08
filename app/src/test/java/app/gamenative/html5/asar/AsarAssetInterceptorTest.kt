package app.gamenative.html5.asar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.html5.host.mimeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// robolectric because context.assets + WebResourceResponse need Android classpath.
// mirrors ZipAssetInterceptorTest coverage shape adapted to asar.
@RunWith(RobolectricTestRunner::class)
class AsarAssetInterceptorTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun makeFixture() = AsarTestFixtures.writeFixture(
        tempFolder.newFile("app.asar"),
        linkedMapOf(
            "index.html" to """<html><script>var x=1;</script></html>""".toByteArray(),
            "main.js" to "console.log('boot')".toByteArray(),
            "resources/img.png" to ByteArray(16) { it.toByte() },
        ),
    )

    @Test
    fun openAsarEntry_returns200ForKnownPath() {
        AsarArchive.open(makeFixture()).use { a ->
            val i = AsarAssetInterceptor(context, a, shimUrls = emptyList())
            val resp = i.openAsarEntry("main.js")
            assertNotNull(resp)
            assertEquals("application/javascript", resp!!.mimeType)
        }
    }

    @Test
    fun openAsarEntry_returnsNullForMissingPath() {
        AsarArchive.open(makeFixture()).use { a ->
            val i = AsarAssetInterceptor(context, a, shimUrls = emptyList())
            assertNull(i.openAsarEntry("nope.js"))
        }
    }

    @Test
    fun openAsarEntry_rejectsDotDotTraversal() {
        AsarArchive.open(makeFixture()).use { a ->
            val i = AsarAssetInterceptor(context, a, shimUrls = emptyList())
            assertNull(i.openAsarEntry("../etc/passwd"))
        }
    }

    @Test
    fun openAsarEntry_rejectsLeadingSlash() {
        AsarArchive.open(makeFixture()).use { a ->
            val i = AsarAssetInterceptor(context, a, shimUrls = emptyList())
            assertNull(i.openAsarEntry("/main.js"))
        }
    }

    @Test
    fun openShimAsset_rejectsDotDotTraversal() {
        AsarArchive.open(makeFixture()).use { a ->
            val i = AsarAssetInterceptor(context, a, shimUrls = emptyList())
            assertNull(i.openShimAsset("../secrets.js"))
        }
    }

    // real-world entry resolution.
    @Test
    fun resolveEntry_picksIndexHtmlWhenPresent() {
        val f = AsarTestFixtures.writeFixture(
            tempFolder.newFile("w.asar"),
            linkedMapOf(
                "index.html" to "<html></html>".toByteArray(),
                "play.html" to "<html></html>".toByteArray(),
            ),
        )
        AsarArchive.open(f).use { a ->
            assertEquals("index.html", AsarAssetInterceptor.resolveEntry(a))
        }
    }

    @Test
    fun resolveEntry_picksPlayHtmlForCuriousExpeditionShape() {
        // mirrors CE's asar root: no index.html, multiple entry HTML candidates, plus debug
        // and io_ aux variants we want to filter out.
        val f = AsarTestFixtures.writeFixture(
            tempFolder.newFile("ce.asar"),
            linkedMapOf(
                "io_account.html" to "<html></html>".toByteArray(),
                "io_account_steam.html" to "<html></html>".toByteArray(),
                "log.html" to "<html></html>".toByteArray(),
                "log-electron.html" to "<html></html>".toByteArray(),
                "play-electron-debug.html" to "<html></html>".toByteArray(),
                "play-electron-io.html" to "<html></html>".toByteArray(),
                "play-electron.html" to "<html></html>".toByteArray(),
            ),
        )
        AsarArchive.open(f).use { a ->
            assertEquals("play-electron.html", AsarAssetInterceptor.resolveEntry(a))
        }
    }

    @Test
    fun resolveEntry_fallsBackToIndexWhenNoHtmlAtRoot() {
        val f = AsarTestFixtures.writeFixture(
            tempFolder.newFile("js-only.asar"),
            linkedMapOf("main.js" to "x".toByteArray()),
        )
        AsarArchive.open(f).use { a ->
            assertEquals("index.html", AsarAssetInterceptor.resolveEntry(a))
        }
    }

    @Test
    fun readIndexAndInjectFromAsar_injectsShimsBeforeFirstScript() {
        AsarArchive.open(makeFixture()).use { a ->
            val bytes = AsarAssetInterceptor.readIndexAndInjectFromAsar(
                archive = a,
                entryName = "index.html",
                shimUrls = listOf("/_shims/test-shim.js"),
                locale = null,
            )
            val html = bytes.toString(Charsets.UTF_8)
            // locator: shim script tag must appear BEFORE the first game <script>.
            val shimIdx = html.indexOf("/_shims/test-shim.js")
            val gameIdx = html.indexOf("var x=1;")
            assertTrue(
                "expected shim before game script — shimIdx=$shimIdx gameIdx=$gameIdx html=$html",
                shimIdx in 0 until gameIdx,
            )
        }
    }

    @Test
    fun mimeFor_jsAndHtmlMappingsMatchWebViewScreen() {
        assertEquals("application/javascript", mimeFor("foo.js"))
        assertEquals("text/html", mimeFor("foo.html"))
        assertEquals("application/octet-stream", mimeFor("foo.unknownext"))
    }

    // /_asar_listdir is how fs.readdirSync reaches asar directories.
    @Test
    fun openAsarListing_returnsJsonArrayForDirectory() {
        val f = AsarTestFixtures.writeFixture(
            tempFolder.newFile("listdir.asar"),
            linkedMapOf(
                "conf/mod1.json" to "{}".toByteArray(),
                "conf/mod2.json" to "{}".toByteArray(),
                "other.js" to "x".toByteArray(),
            ),
        )
        AsarArchive.open(f).use { a ->
            val i = AsarAssetInterceptor(context, a, shimUrls = emptyList())
            val resp = i.openAsarListing("conf")
            assertNotNull(resp)
            assertEquals("application/json", resp!!.mimeType)
            val body = resp.data.bufferedReader(Charsets.UTF_8).readText()
            assertTrue("listing must include mod1.json: $body", body.contains("\"mod1.json\""))
            assertTrue("listing must include mod2.json: $body", body.contains("\"mod2.json\""))
        }
    }

    @Test
    fun openAsarListing_returnsEmptyArrayForMissingPath() {
        AsarArchive.open(makeFixture()).use { a ->
            val i = AsarAssetInterceptor(context, a, shimUrls = emptyList())
            val resp = i.openAsarListing("nope")
            assertNotNull(resp)
            val body = resp!!.data.bufferedReader(Charsets.UTF_8).readText()
            assertEquals("[]", body)
        }
    }

    @Test
    fun openAsarListing_rejectsDotDotTraversal() {
        AsarArchive.open(makeFixture()).use { a ->
            val i = AsarAssetInterceptor(context, a, shimUrls = emptyList())
            assertNull(i.openAsarListing("../secrets"))
        }
    }
}
