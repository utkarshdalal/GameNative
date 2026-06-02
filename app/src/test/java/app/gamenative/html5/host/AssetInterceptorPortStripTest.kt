package app.gamenative.html5.host

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.WebViewAssetLoader
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// WebViewAssetLoader's PathMatcher does a strict `uri.getAuthority() == mAuthority` check and
// setDomain stores the hostname only (no port). the loopback origin
// (http://<safeId>.localhost:<port>/) puts the port on every URL, so without the strip in
// AssetInterceptor.serve() AssetLoader returns null and every disk-backed asset 404s.
@RunWith(RobolectricTestRunner::class)
class AssetInterceptorPortStripTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test fun serve_stripsPortFromAuthority_beforeDelegatingToAssetLoader() {
        val installDir = tempFolder.newFolder("install")
        val assetLoader = mockk<WebViewAssetLoader>()

        val capturedUri = slot<Uri>()
        every { assetLoader.shouldInterceptRequest(capture(capturedUri)) } returns null

        val interceptor = AssetInterceptor(
            context = ApplicationProvider.getApplicationContext(),
            assetLoader = assetLoader,
            installDirectory = installDir,
            shimUrls = emptyList(),
        )

        val incomingUri = Uri.parse("http://steam-2738490.localhost:59099/js/libs/pixi-tilemap.js")
        // AssetLoader is stubbed to null -- only the delegated URI matters.
        val result = interceptor.serve(incomingUri)
        assertNull("with mocked-null AssetLoader, serve must return null", result)

        // the captured URI must have authority WITHOUT the port.
        val delegated = capturedUri.captured
        assertEquals(
            "delegated URI authority must match setDomain's stored hostname-only authority",
            "steam-2738490.localhost",
            delegated.authority,
        )
        assertEquals("steam-2738490.localhost", delegated.host)
        assertEquals("/js/libs/pixi-tilemap.js", delegated.path)
        assertEquals("http", delegated.scheme)
    }

    @Test fun serve_doesNotDelegateShimPaths_toAssetLoader() {
        // /_shims/* paths are intercepted directly and must NOT hit AssetLoader -- proves the test
        // above reflects real delegation rather than an over-permissive intercept.
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

        // even if the shim asset doesn't exist, the AssetLoader path must not be invoked.
        val incomingUri = Uri.parse("http://steam-2738490.localhost:59099/_shims/path.js")
        interceptor.serve(incomingUri)

        org.junit.Assert.assertFalse(
            "/_shims/* must be served directly, not delegated to AssetLoader",
            captured.isCaptured,
        )
    }
}
