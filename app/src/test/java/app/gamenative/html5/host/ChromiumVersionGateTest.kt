package app.gamenative.html5.host

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// getMajor(context) touches WebViewCompat (framework class unavailable pure-JVM).
// isSupported tests stub getMajor() via mockkObject so the version-floor branch runs without
// Robolectric. SMOKE-CHECKLIST F5 covers on-device version-pickup verification.
class ChromiumVersionGateTest {

    @After fun cleanup() { unmockkAll() }

    // ------- parseMajor (pure, unchanged from existing coverage) -------

    @Test
    fun parseMajor_fullVersion_returns108() {
        assertEquals(108, ChromiumVersionGate.parseMajor("108.0.5359.79"))
    }

    @Test
    fun parseMajor_majorOnly_returns100() {
        assertEquals(100, ChromiumVersionGate.parseMajor("100"))
    }

    @Test
    fun parseMajor_below100_returns99() {
        assertEquals(99, ChromiumVersionGate.parseMajor("99.0.4844.88"))
    }

    @Test
    fun parseMajor_empty_returnsNull() {
        assertNull(ChromiumVersionGate.parseMajor(""))
    }

    @Test
    fun parseMajor_null_returnsNull() {
        assertNull(ChromiumVersionGate.parseMajor(null))
    }

    @Test
    fun parseMajor_garbage_returnsNull() {
        assertNull(ChromiumVersionGate.parseMajor("garbage"))
    }

    @Test
    fun parseMajor_twoDigitMajor_returns13() {
        assertEquals(13, ChromiumVersionGate.parseMajor("13"))
    }

    @Test
    fun parseMajor_extraSegments_takesFirst() {
        assertEquals(1, ChromiumVersionGate.parseMajor("1.2.3.4.5"))
    }

    @Test
    fun parseMajor_blank_returnsNull() {
        assertNull(ChromiumVersionGate.parseMajor("   "))
    }

    // ------- MIN_MAJOR drift lock -------

    @Test
    fun minMajor_locks100() {
        assertEquals(100, ChromiumVersionGate.MIN_MAJOR)
    }

    // ------- isSupported branches -------

    // stubs getMajor() on the gate object so WebViewCompat.<clinit> never runs —
    // android-framework class unavailable in pure-JVM classpath. getMajor is its own public fn
    // with SMOKE-CHECKLIST F5 covering on-device version-pickup separately.
    private fun stubMajor(major: Int?) {
        mockkObject(ChromiumVersionGate)
        every { ChromiumVersionGate.getMajor(any()) } returns major
    }

    @Test
    fun isSupported_true_whenMajorOk() {
        val ctx = mockk<Context>(relaxed = true)
        stubMajor(120)
        assertTrue(ChromiumVersionGate.isSupported(ctx))
    }

    @Test
    fun isSupported_false_whenMajorBelowFloor() {
        val ctx = mockk<Context>(relaxed = true)
        stubMajor(99)
        assertFalse(ChromiumVersionGate.isSupported(ctx))
    }

    @Test
    fun isSupported_false_whenMajorNull() {
        val ctx = mockk<Context>(relaxed = true)
        stubMajor(null)
        assertFalse(ChromiumVersionGate.isSupported(ctx))
    }

    @Test
    fun isSupported_false_whenMajorExactly99() {
        val ctx = mockk<Context>(relaxed = true)
        stubMajor(99)
        assertFalse(ChromiumVersionGate.isSupported(ctx))
    }

    @Test
    fun isSupported_true_whenMajorExactly100() {
        val ctx = mockk<Context>(relaxed = true)
        stubMajor(100)
        assertTrue(ChromiumVersionGate.isSupported(ctx))
    }
}
