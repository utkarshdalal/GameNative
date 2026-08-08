package app.gamenative.html5.host

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// + errata: pin literal 109 for android webview OPFS+SAH floor.
//said 108 — that's desktop. android shipped at 109 (mdn/browser-compat-data #18365).
// stubs getMajor() via mockkObject so WebViewCompat.<clinit> never runs in pure-JVM classpath.
// SMOKE-CHECKLIST F5 covers on-device version-pickup separately.
class ChromiumVersionGateOpfsSahTest {

    @After fun cleanup() { unmockkAll() }

    private fun stubMajor(major: Int?) {
        mockkObject(ChromiumVersionGate)
        every { ChromiumVersionGate.getMajor(any()) } returns major
    }

    // ------- literal pin: any future regression to 108 fails CI -------

    @Test
    fun min_opfs_sah_major_is_109_not_108() {
        assertEquals(109, ChromiumVersionGate.MIN_OPFS_SAH_MAJOR)
    }

    @Test
    fun min_major_unchanged() {
        // boot gate stays at 100 — OPFS-SAH gate is additive, not a replacement.
        assertEquals(100, ChromiumVersionGate.MIN_MAJOR)
    }

    // ------- isOpfsSahSupported branches -------

    @Test
    fun isOpfsSahSupported_false_at_100() {
        val ctx = mockk<Context>(relaxed = true)
        stubMajor(100)
        assertFalse(ChromiumVersionGate.isOpfsSahSupported(ctx))
    }

    @Test
    fun isOpfsSahSupported_false_at_108() {
        // boundary: desktop got SAH at 108 but android shipped at 109. 108 must fail on android.
        val ctx = mockk<Context>(relaxed = true)
        stubMajor(108)
        assertFalse(ChromiumVersionGate.isOpfsSahSupported(ctx))
    }

    @Test
    fun isOpfsSahSupported_true_at_109() {
        val ctx = mockk<Context>(relaxed = true)
        stubMajor(109)
        assertTrue(ChromiumVersionGate.isOpfsSahSupported(ctx))
    }

    @Test
    fun isOpfsSahSupported_true_at_120() {
        val ctx = mockk<Context>(relaxed = true)
        stubMajor(120)
        assertTrue(ChromiumVersionGate.isOpfsSahSupported(ctx))
    }

    @Test
    fun isOpfsSahSupported_false_when_unknown() {
        val ctx = mockk<Context>(relaxed = true)
        stubMajor(null)
        assertFalse(ChromiumVersionGate.isOpfsSahSupported(ctx))
    }
}
