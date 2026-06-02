package app.gamenative.html5.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// pure path-math seam for buildElectronCtx. only the keys that aren't derivable from
// process.env live here (productName, appPath, version) — userData/appData/temp/etc. are
// derived JS-side in packs/electron.js from the process.env Windows-NWjs posture set up by
// IndexHtmlRewriter. those derivations are too thin to warrant their own test file.
class WebViewScreenElectronResolveTest {

    @Test
    fun buildElectronCtx_populatesProductNameAppPathVersion() {
        val ctx = buildElectronCtx(productName = "Wayward", asarVersion = "1.2.3")
        assertEquals("Wayward", ctx["productName"])
        assertNotNull(ctx["appPath"])
        assertEquals("1.2.3", ctx["version"])
    }

    @Test
    fun buildElectronCtx_versionDefaultsWhenAsarLacksVersion() {
        val ctx = buildElectronCtx(productName = "Wayward", asarVersion = null)
        assertEquals("0.0.0", ctx["version"])
    }

    @Test
    fun buildElectronCtx_appPathStripsToSandboxRelative() {
        // Tyrano-on-Electron's getExePath() strips `\resources\app` from appPath. value must
        // contain that literal substring so the strip succeeds; what's left ("." after strip)
        // is what Tyrano composes save paths from.
        val ctx = buildElectronCtx(productName = "Wayward", asarVersion = null)
        assertTrue(
            "appPath=${ctx["appPath"]} — expected to contain \\resources\\app",
            ctx["appPath"]!!.contains("\\resources\\app"),
        )
    }
}
