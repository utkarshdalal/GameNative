package app.gamenative.html5.shim

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// EXECUTES path.js (via require-dispatcher) under Rhino and asserts the real posix path math.
// path.js underpins save-path mapping (the C:/ -> wine drive_c translation + RMMV/RMMZ
// fileDirectoryPath concat), so logic drift here silently mis-routes saves. previously locked
// only by source-string assertions; these run the actual functions.
class ShimPathExecTest {
    private lateinit var js: ShimJsRuntime

    @Before
    fun setUp() {
        js = ShimJsRuntime().load("require-dispatcher.js").load("path.js")
    }

    @After
    fun tearDown() {
        js.close()
    }

    private fun path(expr: String): String = js.evalString("window.require('path').$expr")

    @Test
    fun registers_as_path_module_with_posix_sep() {
        assertEquals("object", js.evalString("typeof window.require('path')"))
        assertEquals("/", path("sep"))
    }

    @Test
    fun join_basic() {
        assertEquals("a/b/c", path("join('a','b','c')"))
    }

    @Test
    fun join_preserves_trailing_slash_for_save_dir() {
        // the cloud-sync fix: join('.', 'save/') MUST keep the trailing slash so RMMZ's
        // fileDirectoryPath()+name concat lands in <install>/save/, not at install root.
        assertEquals("save/", path("join('.', 'save/')"))
    }

    @Test
    fun normalize_collapses_dotdot() {
        assertEquals("b", path("normalize('a/../b')"))
    }

    @Test
    fun normalize_preserves_trailing_slash() {
        assertEquals("save/", path("normalize('./save/')"))
    }

    @Test
    fun dirname_nested_and_flat() {
        assertEquals("www", path("dirname('www/index.html')"))
        assertEquals(".", path("dirname('index.html')"))
    }

    @Test
    fun basename_strips_extension() {
        assertEquals("c.txt", path("basename('a/b/c.txt')"))
        assertEquals("c", path("basename('a/b/c.txt', '.txt')"))
    }

    @Test
    fun extname_multi_dot_and_leading_dot() {
        assertEquals(".gz", path("extname('a.tar.gz')"))
        assertEquals("", path("extname('.rc')")) // leading dot is not an extension (node parity)
    }

    @Test
    fun isAbsolute_posix_plus_backslash_tolerance() {
        assertTrue(js.evalBoolean("window.require('path').isAbsolute('/x')"))
        assertFalse(js.evalBoolean("window.require('path').isAbsolute('x')"))
        assertTrue(js.evalBoolean("window.require('path').isAbsolute('\\\\x')")) // windows-input tolerance
    }

    @Test
    fun backslash_input_normalized_in_dirname() {
        // games occasionally ship windows-style string literals; shim swaps \\ -> /.
        assertEquals("www/save", path("dirname('www\\\\save\\\\f.json')"))
    }

    @Test
    fun posix_and_win32_namespaces_alias_self() {
        assertTrue(js.evalBoolean("window.require('path').posix === window.require('path')"))
        assertTrue(js.evalBoolean("window.require('path').win32 === window.require('path')"))
    }
}
