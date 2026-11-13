package app.gamenative.theme

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteFontsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var themes: File
    private lateinit var remote: File

    private val ttf = byteArrayOf(0, 1, 0, 0) + ByteArray(60)
    private val otf = "OTTO".toByteArray() + ByteArray(60)

    private class FakeFetcher(val bodies: Map<String, ByteArray>) : RemoteFonts.Fetcher {
        val calls = ArrayList<Pair<String, Long>>()
        override fun get(url: String, maxBytes: Long): ByteArray {
            calls.add(url to maxBytes)
            return bodies[url] ?: throw IOException("404 $url")
        }
    }

    private fun folder(name: String, vararg files: Pair<String, String>): File {
        val dir = File(themes, name)
        for ((path, body) in files) File(dir, path).apply { parentFile?.mkdirs() }.writeText(body)
        return dir
    }

    private fun unit(dir: File, values: ThemeValues = ThemeValues()) = ThemeUnit(dir, ThemeLoader.loadManifest(dir), values)

    private fun assets() = ThemeAssets(themes, File(themes, ".fonts"), remoteDir = { File(remote, it.name) })

    private fun sheets(u: ThemeUnit) = ThemeLoader.sheets(u, assets())

    private fun spec(u: ThemeUnit) = ThemeTranslator.translate(sheets(u), SteamClassMap.EMPTY).spec

    private fun sync(u: ThemeUnit, fetcher: RemoteFonts.Fetcher) = RemoteFonts(fetcher).sync(sheets(u).map { it.sheet }, File(remote, u.folder.name))

    @Before
    fun setUp() {
        themes = tmp.newFolder("themes")
        remote = File(themes, ".remote")
        ThemeLoader.clearCache()
    }

    private val google = "https://fonts.googleapis.com/css2?family=Roboto"
    private val googleTtf = "https://fonts.gstatic.com/s/roboto/v51/a.ttf"

    private fun googleTheme() = folder(
        "Mano",
        "theme.css" to """
            @import url('$google');
            @import url("https://fonts.googleapis.com/css2?family=Inter");
            * { font-family: 'Roboto' !important; }
        """.trimIndent(),
    )

    private val googleCss = """
        @font-face { font-family: 'Roboto'; font-weight: 400; src: url($googleTtf) format('truetype'); }
        @font-face { font-family: 'Roboto'; font-weight: 700; src: url(https://fonts.gstatic.com/s/roboto/v51/bold.ttf) format('truetype'); }
        .x { color: red; }
        @import url(https://evil.example/next.css);
        :root { --gn-primary: #ff0000; }
    """.trimIndent().toByteArray()

    @Test
    fun importedStylesheetFontIsDownloadedOnceAndUsed() {
        val u = unit(googleTheme())
        assertNull("nothing is fetched while translating", spec(u).fontFile)
        val fetcher = FakeFetcher(mapOf(google to googleCss, googleTtf to ttf))
        assertTrue(sync(u, fetcher))
        assertEquals(
            listOf(google to RemoteFonts.MAX_CSS_BYTES, "https://fonts.googleapis.com/css2?family=Inter" to RemoteFonts.MAX_CSS_BYTES, googleTtf to RemoteFonts.MAX_FONT_BYTES),
            fetcher.calls,
        )
        val font = spec(u).fontFile
        assertNotNull(font)
        assertEquals(File(remote, "Mano").canonicalPath, File(font!!).parentFile.canonicalPath)
        assertTrue(font.endsWith(".ttf"))

        val kept = RemoteFonts.cachedImport(File(remote, "Mano"), google)!!.readText()
        assertFalse("only @font-face is kept from a remote stylesheet", kept.contains("color") || kept.contains("gn-primary") || kept.contains("evil"))
        assertNull("rules other than @font-face in remote css are ignored", spec(u).primary)

        val again = FakeFetcher(mapOf(google to googleCss, googleTtf to ttf))
        assertFalse(sync(u, again))
        assertEquals("the failed import is retried, kept files are reused", listOf("https://fonts.googleapis.com/css2?family=Inter"), again.calls.map { it.first })
    }

    @Test
    fun onlyHttpsIsFetched() {
        val dir = folder(
            "Plain",
            "theme.css" to """
                @import url(http://fonts.example/a.css);
                @font-face { font-family: A; src: url(http://fonts.example/a.ttf); }
                @font-face { font-family: B; src: url(//cdn.example/b.ttf); }
                * { font-family: A; }
            """.trimIndent(),
        )
        val fetcher = FakeFetcher(emptyMap())
        assertFalse(sync(unit(dir), fetcher))
        assertTrue(fetcher.calls.isEmpty())
        assertNull(spec(unit(dir)).fontFile)
    }

    @Test
    fun remoteStylesheetUrlsThatResolveOffHttpsAreDropped() {
        val css = "@font-face { font-family: A; src: url(http://x.example/a.ttf), url(fonts/a.ttf) format('truetype'); }"
        val out = RemoteFonts.fontFacesCss(CssParser.parse(css), "https://cdn.example/css/a.css")
        assertTrue(out.contains("url(\"https://cdn.example/css/fonts/a.ttf\") format('truetype')"))
        assertFalse(out.contains("http://"))
    }

    @Test
    fun oversizedAndInvalidFilesFallBackToTheDefaultFont() {
        val url = "https://cdn.example/a.ttf"
        val dir = folder("Big", "theme.css" to "@font-face { font-family: A; src: url($url); } * { font-family: A; }")
        val big = object : RemoteFonts.Fetcher {
            override fun get(url: String, maxBytes: Long) = ByteArray((maxBytes + 1).toInt())
        }
        assertFalse(sync(unit(dir), big))
        assertNull(spec(unit(dir)).fontFile)
        for (body in listOf("<html>".toByteArray() + ByteArray(20), "wOF2".toByteArray() + ByteArray(40), "wOFF".toByteArray() + ByteArray(40))) {
            assertFalse(sync(unit(dir), FakeFetcher(mapOf(url to body))))
            assertNull(spec(unit(dir)).fontFile)
        }
        assertFalse(sync(unit(dir), FakeFetcher(emptyMap())))
        assertNull(spec(unit(dir)).fontFile)
        assertTrue(sync(unit(dir), FakeFetcher(mapOf(url to otf))))
        assertTrue(spec(unit(dir)).fontFile!!.endsWith(".otf"))
    }

    @Test
    fun woffIsDecodedLikeALocalFont() {
        val url = "https://cdn.example/a.woff"
        val dir = folder("Woff", "theme.css" to "@font-face { font-family: A; src: url($url) format('woff'); } * { font-family: A; }")
        assertTrue(sync(unit(dir), FakeFetcher(mapOf(url to TestWoff.woff()))))
        val font = spec(unit(dir)).fontFile!!
        assertEquals(File(themes, ".fonts").absolutePath, File(font).parent)
    }

    @Test
    fun woff2SourcesAreSkippedAndRequestsAreCapped() {
        val faces = (1..20).joinToString("\n") { "@font-face { font-family: F$it; src: url(https://cdn.example/$it.woff2) format('woff2'), url(https://cdn.example/$it.ttf); }" }
        val dir = folder("Many", "theme.css" to faces)
        val fetcher = FakeFetcher(emptyMap())
        sync(unit(dir), fetcher)
        assertEquals(RemoteFonts.MAX_REQUESTS, fetcher.calls.size)
        assertTrue(fetcher.calls.none { it.first.endsWith(".woff2") })
    }

    @Test
    fun gnFontFromTheInternetMakesANativeThemeThatPackagesTheFile() {
        val url = "https://cdn.example/brand.ttf"
        val dir = folder("Native", "theme.css" to ":root { --gn-font: url($url); --gn-primary: #123456; }")
        assertTrue(sync(unit(dir), FakeFetcher(mapOf(url to ttf))))
        val sheets = sheets(unit(dir))
        assertTrue(sheets.all { CommunityThemePackage.isNative(it.sheet) })
        val spec = ThemeTranslator.translate(sheets, SteamClassMap.EMPTY).spec
        val zip = tmp.newFile("share.zip")
        CommunityThemePackage.write(spec, "Native", "me", "v1", "", zip)
        java.util.zip.ZipFile(zip).use { z ->
            assertNotNull(z.getEntry("font.ttf"))
            val css = z.getInputStream(z.getEntry("theme.css")).readBytes().toString(Charsets.UTF_8)
            assertTrue(css.contains("--gn-font: url(\"font.ttf\")"))
            assertFalse(css.contains("https:"))
        }
    }

    @Test
    fun unusedDownloadsArePruned() {
        val u = unit(googleTheme())
        sync(u, FakeFetcher(mapOf(google to googleCss, googleTtf to ttf)))
        val dir = File(remote, "Mano")
        val stale = File(dir, "0000.ttf").apply { writeBytes(ttf) }
        RemoteFonts.prune(dir, RemoteFonts.referenced(sheets(u).map { it.sheet }, dir))
        assertFalse(stale.exists())
        assertNotNull(RemoteFonts.localFile(dir, googleTtf))
        assertNotNull(RemoteFonts.cachedImport(dir, google))
        RemoteFonts.prune(dir, emptySet())
        assertEquals(0, dir.listFiles()!!.size)
        assertNull(spec(u).fontFile)
    }
}
