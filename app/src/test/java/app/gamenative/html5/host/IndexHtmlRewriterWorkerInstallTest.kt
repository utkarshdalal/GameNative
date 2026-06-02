package app.gamenative.html5.host

import app.gamenative.html5.profile.EngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// — worker-install.js prepended at index 0 only when
// engine=="pack:c3" AND workerShim=true. all other paths must NOT see the URL.
// pure-jvm — resolveShimUrls is a top-level function in WebViewScreen.kt with
// no android deps (ShimBundles is object-only, EngineProfile @Serializable).
class IndexHtmlRewriterWorkerInstallTest {
    private val WORKER_INSTALL_URL = "/_shims/worker-install.js"

    @Test fun packC3_workerShimTrue_prependsWorkerInstallAtIndex0() {
        val p = EngineProfile(engine = "pack:c3", workerShim = true)
        val urls = resolveShimUrls(profile = p, resolvedMode = "html5")
        assertTrue("worker-install missing: $urls", urls.contains(WORKER_INSTALL_URL))
        assertEquals("must be at index 0: $urls", WORKER_INSTALL_URL, urls.first())
    }

    @Test fun packC3_workerShimFalse_omitsWorkerInstall() {
        val p = EngineProfile(engine = "pack:c3", workerShim = false)
        val urls = resolveShimUrls(profile = p, resolvedMode = "html5")
        assertFalse("worker-install must be absent: $urls", urls.contains(WORKER_INSTALL_URL))
    }

    @Test fun packC3_workerShimAbsent_omitsWorkerInstall() {
        // default false when field absent
        val p = EngineProfile(engine = "pack:c3")
        val urls = resolveShimUrls(profile = p, resolvedMode = "html5")
        assertFalse("worker-install must be absent: $urls", urls.contains(WORKER_INSTALL_URL))
    }

    @Test fun packRmmv_workerShimTrue_omitsWorkerInstall() {
        // engine != pack:c3 → workerShim ignored
        val p = EngineProfile(engine = "pack:rmmv", workerShim = true)
        val urls = resolveShimUrls(profile = p, resolvedMode = "html5")
        assertFalse("worker-install must be absent for rmmv: $urls", urls.contains(WORKER_INSTALL_URL))
    }

    @Test fun packElectron_workerShimTrue_omitsWorkerInstall() {
        val p = EngineProfile(engine = "pack:electron", workerShim = true)
        val urls = resolveShimUrls(profile = p, resolvedMode = "html5")
        assertFalse("worker-install must be absent for electron: $urls", urls.contains(WORKER_INSTALL_URL))
    }

    @Test fun nullProfile_omitsWorkerInstall() {
        val urls = resolveShimUrls(profile = null, resolvedMode = "html5")
        assertFalse("worker-install must be absent for null profile: $urls", urls.contains(WORKER_INSTALL_URL))
    }
}
