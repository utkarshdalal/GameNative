package app.gamenative.data

import org.junit.Assert.assertEquals
import org.junit.Test

// — runtime field default + explicit override semantics.
class LibraryItemRuntimeTest {

    @Test
    fun runtime_defaults_to_wine_when_unspecified() {
        val item = LibraryItem(appId = "STEAM_42", name = "Test")
        assertEquals("wine", item.runtime)
    }

    @Test
    fun runtime_can_be_set_to_webview() {
        val item = LibraryItem(appId = "STEAM_2171440", name = "TERMINA", runtime = "webview")
        assertEquals("webview", item.runtime)
    }

    @Test
    fun copy_preserves_runtime_when_unspecified() {
        val item = LibraryItem(appId = "STEAM_2171440", name = "TERMINA", runtime = "webview")
        val copied = item.copy(name = "TERMINA Updated")
        assertEquals("webview", copied.runtime)
    }

    @Test
    fun copy_overrides_runtime_when_specified() {
        val item = LibraryItem(appId = "STEAM_42", name = "Test", runtime = "webview")
        val copied = item.copy(runtime = "wine")
        assertEquals("wine", copied.runtime)
    }
}
