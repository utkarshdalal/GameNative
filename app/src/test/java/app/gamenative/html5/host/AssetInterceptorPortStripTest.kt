package app.gamenative.html5.host

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.WebViewAssetLoader
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// drift-lock for the URI port-strip in AssetInterceptor.serve(). WebViewAssetLoader's
// PathMatcher does a strict `uri.getAuthority() == mAuthority` check; setDomain stores
// hostname only (no port). under the v2.1 loopback origin (http://<safeId>.localhost:<port>/),
// every URL carries the explicit port. without the strip, AssetLoader returns null and every
// disk-backed asset (RMMV/RMMZ js/, data/, audio/) 404s.
//
// regression scenario: on first manifesting in pack:rmmv (OMORI) under v2.1, this test would
// have caught the bug pre-on-device. keeping it ensures any future refactor of the delegation
// path that loses the strip surfaces noisily here.
@RunWith(RobolectricTestRunner::class)
class AssetInterceptorPortStripTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test fun serve_stripsPortFromAuthority_beforeDelegatingToAssetLoader() {
        val installDir = tempFolder.newFolder("install")
        val assetLoader = mockk<WebViewAssetLoader>()

        // capture the URI that gets handed to AssetLoader
        val capturedUri = slot<Uri>()
        every { assetLoader.shouldInterceptRequest(capture(capturedUri)) } returns null

        val interceptor = AssetInterceptor(
            context = ApplicationProvider.getApplicationContext(),
            assetLoader = assetLoader,
            installDirectory = installDir,
            shimUrls = emptyList(),
        )

        val incomingUri = Uri.parse("http://steam-2738490.localhost:59099/js/libs/pixi-tilemap.js")
        // serve returns null because we stubbed AssetLoader to null — that's fine, we only care
        // about what URI got passed.
        val result = interceptor.serve(incomingUri)
        assertNull("with mocked-null AssetLoader, serve must return null", result)

        // the captured URI must have authority WITHOUT the port — that's the whole fix.
        val delegated = capturedUri.captured
        assertEquals(
            "delegated URI authority must match setDomain's stored hostname-only authority",
            "steam-2738490.localhost",
            delegated.authority,
        )
        // host should still be there
        assertEquals("steam-2738490.localhost", delegated.host)
        // path preserved
        assertEquals("/js/libs/pixi-tilemap.js", delegated.path)
        // scheme preserved
        assertEquals("http", delegated.scheme)
    }

    @Test fun serve_doesNotDelegateShimPaths_toAssetLoader() {
        // sanity-check: /_shims/* paths are intercepted directly by AssetInterceptor and must
        // NOT hit AssetLoader. ensures the test above reflects real delegation flow rather than
        // an over-permissive intercept.
        val installDir = tempFolder.newFolder("install-shim")
        val assetLoader = mockk<WebViewAssetLoader>()
        val captured = slot<Uri>()
        every { assetLoader.shouldInterceptRequest(capture(captured)) } returns null

        val interceptor = AssetInterceptor(
            context = ApplicationProvider.getApplicationContext(),
            assetLoader = assetLoader,
            installDirectory = installDir,
            shimUrls = emptyList(),
        )

        // request a shim path. AssetInterceptor reads from app assets (handled by Robolectric);
        // even if the asset doesn't exist, the AssetLoader path must not be invoked.
        val incomingUri = Uri.parse("http://steam-2738490.localhost:59099/_shims/path.js")
        interceptor.serve(incomingUri)

        // AssetLoader was NOT called — slot stays uncaptured. mockk's slot.isCaptured tracks this.
        org.junit.Assert.assertFalse(
            "/_shims/* must be served directly, not delegated to AssetLoader",
            captured.isCaptured,
        )
    }
}
