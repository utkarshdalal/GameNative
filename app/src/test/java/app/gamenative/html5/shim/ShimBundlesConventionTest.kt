package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// guards the ShimBundles convention-fallback refactor: regular base shims + pack shims resolve by
// convention (no explicit map entry), while irregular ids (filename != id) keep explicit entries.
// pure-JVM -- ShimBundles is object-only.
class ShimBundlesConventionTest {

    @Test
    fun regularBaseShim_derivesRootPath() {
        // dropped explicit entries -- must resolve via deriveShimPath to the identical path.
        assertEquals("/_shims/gamepad.js", ShimBundles.urlFor(ShimBundles.GAMEPAD_ID))
        assertEquals("html5/shims/gamepad.js", ShimBundles.assetPathFor(ShimBundles.GAMEPAD_ID))
        assertEquals("/_shims/require-dispatcher.js", ShimBundles.urlFor(ShimBundles.REQUIRE_DISPATCHER_ID))
        assertEquals("/_shims/worker-bundle.js", ShimBundles.urlFor(ShimBundles.WORKER_BUNDLE_ID))
        assertEquals("/_shims/text-picture-cache.js", ShimBundles.urlFor(ShimBundles.TEXT_PICTURE_CACHE_ID))
    }

    @Test
    fun packShim_derivesPacksSubdir() {
        assertEquals("/_shims/packs/unity.js", ShimBundles.urlFor("pack-unity"))
        assertEquals("html5/shims/packs/unity.js", ShimBundles.assetPathFor("pack-unity"))
    }

    @Test
    fun irregularShims_keepExplicitMapping() {
        // filename differs from id -- these MUST stay in the explicit map (not convention).
        assertEquals("html5/shims/steamworks.js", ShimBundles.assetPathFor(ShimBundles.STEAMWORKS_NOOP_ID))
        assertEquals("html5/shims/nw.js", ShimBundles.assetPathFor(ShimBundles.NW_NOOP_ID))
        assertEquals("html5/shims/greenworks.js", ShimBundles.assetPathFor(ShimBundles.GREENWORKS_NOOP_ID))
        assertEquals("html5/shims/js-yaml.min.js", ShimBundles.assetPathFor(ShimBundles.JS_YAML_ID))
    }

    @Test
    fun traversalGuard_rejectsDirtyIds() {
        assertNull(ShimBundles.deriveShimPath("../evil"))
        assertNull(ShimBundles.deriveShimPath("a/b"))
        assertNull(ShimBundles.deriveShimPath("a.b"))
        assertNull(ShimBundles.deriveShimPath(""))
        assertNull(ShimBundles.deriveShimPath("pack-"))
    }
}
