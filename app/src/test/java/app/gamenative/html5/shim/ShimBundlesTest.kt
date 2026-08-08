package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// pure-jvm — ShimBundles is an object with const vals only, no android deps.
class ShimBundlesTest {
    @Test fun gamepad_bundle_url() {
        assertEquals("/_shims/gamepad.js", ShimBundles.urlFor(ShimBundles.GAMEPAD_ID))
    }

    @Test fun gamepad_bundle_asset_path() {
        assertEquals("html5/shims/gamepad.js", ShimBundles.assetPathFor(ShimBundles.GAMEPAD_ID))
    }

    @Test fun gamepad_id_literal_is_gamepad() {
        assertEquals("gamepad", ShimBundles.GAMEPAD_ID)
    }

    // unified touch shim. POINTER_TAP/TOUCH_TOUCHPAD/PASSTHROUGH/GESTURES IDs
    // deleted alongside their assets — TOUCH_ID is the only touch bundle.
    @Test fun touch_bundle_url() {
        assertEquals("/_shims/touch.js", ShimBundles.urlFor(ShimBundles.TOUCH_ID))
    }

    @Test fun touch_bundle_asset_path() {
        assertEquals("html5/shims/touch.js", ShimBundles.assetPathFor(ShimBundles.TOUCH_ID))
    }

    @Test fun touch_id_literal_is_touch() {
        assertEquals("touch", ShimBundles.TOUCH_ID)
    }

    // post convention-fallback refactor: a clean unknown id derives a conventional path (the shim
    // either exists at that path or 404s, same as a typo'd asset filename). null is reserved for
    // ids that can't be a safe filename (traversal/dots/slashes/empty) -- see deriveShimPath guard.
    @Test fun unknown_clean_bundle_derives_conventional_path() {
        assertEquals("/_shims/unknown-shim-xyz.js", ShimBundles.urlFor("unknown-shim-xyz"))
        assertEquals("html5/shims/unknown-shim-xyz.js", ShimBundles.assetPathFor("unknown-shim-xyz"))
    }

    @Test fun dirty_bundle_id_returns_null() {
        assertNull(ShimBundles.urlFor("../escape"))
        assertNull(ShimBundles.assetPathFor("a/b"))
        assertNull(ShimBundles.urlFor(""))
    }

    // per asset files themselves land in /05; these tests lock
    // the id + url + asset-path wiring so downstream code references stabilize on wave-2.
    @Test fun pack_rmmv_bundle_url() {
        assertEquals("/_shims/packs/rmmv.js", ShimBundles.urlFor(ShimBundles.PACK_RMMV_ID))
    }

    @Test fun pack_rmmv_bundle_asset_path() {
        assertEquals("html5/shims/packs/rmmv.js", ShimBundles.assetPathFor(ShimBundles.PACK_RMMV_ID))
    }

    @Test fun pack_c3_bundle_url() {
        assertEquals("/_shims/packs/c3.js", ShimBundles.urlFor(ShimBundles.PACK_C3_ID))
    }

    @Test fun pack_c3_bundle_asset_path() {
        assertEquals("html5/shims/packs/c3.js", ShimBundles.assetPathFor(ShimBundles.PACK_C3_ID))
    }

    @Test fun desktop_spoof_id_literal() {
        assertEquals("desktop-spoof", ShimBundles.DESKTOP_SPOOF_ID)
    }

    @Test fun desktop_spoof_bundle_url() {
        assertEquals("/_shims/desktop-spoof.js", ShimBundles.urlFor(ShimBundles.DESKTOP_SPOOF_ID))
    }

    @Test fun desktop_spoof_bundle_asset_path() {
        assertEquals("html5/shims/desktop-spoof.js", ShimBundles.assetPathFor(ShimBundles.DESKTOP_SPOOF_ID))
    }

}
