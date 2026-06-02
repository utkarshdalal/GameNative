package app.gamenative.html5.host

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// real WebResourceRequest flow verified by manual SMOKE-CHECKLIST + future instrumentation
// test (not this plan). pure-JVM tests below cover readIndexAndInject core rewrite logic.
class AssetInterceptorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeIndex(dir: File, body: String): File {
        val f = File(dir, "index.html")
        f.writeText(body, Charsets.UTF_8)
        return f
    }

    @Test
    fun readIndexAndInject_injectsShimBeforeFirstScript() {
        val dir = tempFolder.newFolder()
        writeIndex(
            dir,
            """<!DOCTYPE html><html><head><title>x</title></head>
<body><script src="js/main.js"></script></body></html>""",
        )
        val out = AssetInterceptor.readIndexAndInject(dir, listOf("/_shims/steamworks.js"))
        val text = String(out, Charsets.UTF_8)
        val shimIdx = text.indexOf("""<script src="/_shims/steamworks.js"></script>""")
        val gameIdx = text.indexOf("""<script src="js/main.js">""")
        assertTrue("shim not found in output: $text", shimIdx >= 0)
        assertTrue("game script not found in output: $text", gameIdx >= 0)
        assertTrue("shim must precede game script (shim=$shimIdx game=$gameIdx)", shimIdx < gameIdx)
    }

    @Test
    fun readIndexAndInject_missingIndex_throws() {
        val dir = tempFolder.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            AssetInterceptor.readIndexAndInject(dir, listOf("/_shims/steamworks.js"))
        }
    }

    @Test
    fun readIndexAndInject_canonicalPath_works() {
        // legitimate install dir (canonical File) — no path-traversal rejection.
        val dir = tempFolder.newFolder().canonicalFile
        writeIndex(
            dir,
            """<html><body><script src="main.js"></script></body></html>""",
        )
        val out = AssetInterceptor.readIndexAndInject(dir, listOf("/_shims/a.js"))
        val text = String(out, Charsets.UTF_8)
        assertTrue(text.contains("""<script src="/_shims/a.js"></script>"""))
    }

    @Test
    fun readIndexAndInject_emptyShimList_preservesFirstScript() {
        val dir = tempFolder.newFolder()
        writeIndex(
            dir,
            """<html><body><script src="g.js"></script></body></html>""",
        )
        val out = AssetInterceptor.readIndexAndInject(dir, emptyList())
        val text = String(out, Charsets.UTF_8)
        // empty list -> no shim lines but first game script still present.
        assertTrue("game script preserved", text.contains("""<script src="g.js">"""))
    }

    @Test
    fun readIndexAndInject_passes_locale_through() {
        val dir = tempFolder.newFolder()
        writeIndex(
            dir,
            """<html><body><script src="g.js"></script></body></html>""",
        )
        val out = AssetInterceptor.readIndexAndInject(
            dir,
            listOf("/_shims/s.js"),
            locale = "fr-FR",
        )
        val text = String(out, Charsets.UTF_8)
        val localeIdx = text.indexOf("navigator,'language'")
        val shimIdx = text.indexOf("/_shims/s.js")
        val gameIdx = text.indexOf("g.js")
        assertTrue("locale injected", localeIdx >= 0)
        assertTrue("fr-FR present", text.contains("\"fr-FR\""))
        assertTrue("order: locale < shim < game", localeIdx < shimIdx && shimIdx < gameIdx)
    }

    @Test
    fun readIndexAndInject_multipleShims_orderPreservedBeforeFirstScript() {
        val dir = tempFolder.newFolder()
        writeIndex(
            dir,
            """<html><body><script src="g.js"></script></body></html>""",
        )
        val out = AssetInterceptor.readIndexAndInject(
            dir,
            listOf("/_shims/a.js", "/_shims/b.js"),
        )
        val text = String(out, Charsets.UTF_8)
        val a = text.indexOf("""<script src="/_shims/a.js">""")
        val b = text.indexOf("""<script src="/_shims/b.js">""")
        val g = text.indexOf("""<script src="g.js">""")
        assertTrue("a present", a >= 0)
        assertTrue("b present", b >= 0)
        assertTrue("g present", g >= 0)
        assertTrue("order: a < b < g (a=$a b=$b g=$g)", a < b && b < g)
    }
}
