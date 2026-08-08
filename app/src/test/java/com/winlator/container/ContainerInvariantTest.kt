package com.winlator.container

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// robolectric needed — Container's static init reads Environment.getExternalStoragePublicDirectory
// at class load (L60 DEFAULT_DRIVES). pure-jvm junit fails in clinit before our tests run.

// invariant: containerVariant == "html5" ⇔ runtime == "webview". enforced via normalize-on-set
// in setContainerVariant + setRuntime (mirrors normalizeRuntime / normalizeSuspendPolicy style).
// loadData runs setRuntime before setContainerVariant, so for mismatched JSON the variant
// setter is last-write-wins.
@RunWith(RobolectricTestRunner::class)
class ContainerInvariantTest {

    // --- cross-setter: variant drives runtime ---

    @Test
    fun setContainerVariant_html5_flips_runtime_to_webview() {
        val container = Container("test-id")
        container.setContainerVariant(Container.CONTAINER_VARIANT_HTML5)
        assertEquals(Container.RUNTIME_WEBVIEW, container.runtime)
        assertEquals(Container.CONTAINER_VARIANT_HTML5, container.containerVariant)
    }

    @Test
    fun setContainerVariant_glibc_after_html5_flips_runtime_back_to_wine() {
        val container = Container("test-id")
        // arrange: put container into html5/webview state first
        container.setContainerVariant(Container.CONTAINER_VARIANT_HTML5)
        assertEquals(Container.RUNTIME_WEBVIEW, container.runtime)
        // act: user flips variant back to glibc → runtime MUST revert to wine
        container.setContainerVariant(Container.GLIBC)
        assertEquals(Container.RUNTIME_WINE, container.runtime)
        assertEquals(Container.GLIBC, container.containerVariant)
    }

    @Test
    fun setContainerVariant_bionic_after_html5_flips_runtime_back_to_wine() {
        val container = Container("test-id")
        container.setContainerVariant(Container.CONTAINER_VARIANT_HTML5)
        container.setContainerVariant(Container.BIONIC)
        assertEquals(Container.RUNTIME_WINE, container.runtime)
        assertEquals(Container.BIONIC, container.containerVariant)
    }

    @Test
    fun setContainerVariant_bionic_does_not_touch_runtime() {
        val container = Container("test-id")
        // new container — runtime defaults to wine. flipping to bionic must NOT mutate runtime.
        container.setContainerVariant(Container.BIONIC)
        assertEquals(Container.RUNTIME_WINE, container.runtime)
        assertEquals(Container.BIONIC, container.containerVariant)
    }

    // --- cross-setter: runtime drives variant ---

    @Test
    fun setRuntime_webview_flips_variant_to_html5() {
        val container = Container("test-id")
        container.setRuntime(Container.RUNTIME_WEBVIEW)
        assertEquals(Container.CONTAINER_VARIANT_HTML5, container.containerVariant)
        assertEquals(Container.RUNTIME_WEBVIEW, container.runtime)
    }

    @Test
    fun setRuntime_wine_from_webview_flips_variant_to_default() {
        val container = Container("test-id")
        // arrange: put container into html5/webview state
        container.setRuntime(Container.RUNTIME_WEBVIEW)
        assertEquals(Container.CONTAINER_VARIANT_HTML5, container.containerVariant)
        // act: flipping runtime away from webview while variant was html5 → variant reverts
        container.setRuntime(Container.RUNTIME_WINE)
        assertEquals(Container.DEFAULT_VARIANT, container.containerVariant)
        assertEquals(Container.RUNTIME_WINE, container.runtime)
    }

    @Test
    fun setRuntime_wine_on_non_html5_does_not_mutate_variant() {
        val container = Container("test-id")
        // arrange: variant=glibc (not html5), runtime=wine (default). setting runtime=wine is a no-op.
        container.setContainerVariant(Container.GLIBC)
        container.setRuntime(Container.RUNTIME_WINE)
        assertEquals(Container.GLIBC, container.containerVariant)
        assertEquals(Container.RUNTIME_WINE, container.runtime)
    }

    // --- normalize helper ---

    @Test
    fun normalizeContainerVariant_unknown_returns_default() {
        assertEquals(Container.DEFAULT_VARIANT, Container.normalizeContainerVariant("garbage"))
    }

    @Test
    fun normalizeContainerVariant_case_insensitive() {
        // hand-edited .container JSON with "HTML5" or " html5 " must still canonicalize.
        assertEquals(Container.CONTAINER_VARIANT_HTML5, Container.normalizeContainerVariant("HTML5"))
        assertEquals(Container.CONTAINER_VARIANT_HTML5, Container.normalizeContainerVariant("  html5 "))
    }

    // --- loadData: mismatched JSON normalizes last-write-wins ---
    // loadData runs setRuntime at L744, then iterates and hits setContainerVariant later. the variant
    // setter fires LAST, so its invariant clause dictates the final state. tests lock that semantics.

    @Test
    fun loadData_mismatched_html5_wine_json_normalizes_to_html5_webview() {
        val container = Container("test-id")
        val json = JSONObject().apply {
            put("runtime", Container.RUNTIME_WINE)
            put("containerVariant", Container.CONTAINER_VARIANT_HTML5)
        }
        container.loadData(json)
        // variant=html5 wins → runtime flipped forward to webview
        assertEquals(Container.RUNTIME_WEBVIEW, container.runtime)
        assertEquals(Container.CONTAINER_VARIANT_HTML5, container.containerVariant)
    }

    @Test
    fun loadData_mismatched_webview_glibc_json_normalizes_to_glibc_wine() {
        val container = Container("test-id")
        val json = JSONObject().apply {
            put("runtime", Container.RUNTIME_WEBVIEW)
            put("containerVariant", Container.GLIBC)
        }
        container.loadData(json)
        // variant=glibc setter runs last, invariant sees prior runtime=webview → runtime reverts to wine
        assertEquals(Container.RUNTIME_WINE, container.runtime)
        assertEquals(Container.GLIBC, container.containerVariant)
    }
}
