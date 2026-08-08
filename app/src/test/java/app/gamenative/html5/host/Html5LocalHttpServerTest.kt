package app.gamenative.html5.host

import app.gamenative.html5.host.Html5LocalHttpServer.BodyMode
import app.gamenative.html5.host.Html5LocalHttpServer.Companion.STREAM_THRESHOLD_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// pure-jvm tests for the body-delivery decision + Range parsing. these are companion functions
// precisely so they're testable without constructing the server (its ctor binds 127.0.0.1).
class Html5LocalHttpServerTest {

    // --- decideBodyMode ---

    private fun mode(mime: String, hasRange: Boolean, hintedLength: Long?): BodyMode =
        Html5LocalHttpServer.decideBodyMode(mime, hasRange, hintedLength)

    @Test
    fun decideBodyMode_nonMediaNoRange_streamsPlain() {
        assertEquals(BodyMode.STREAM_PLAIN, mode("application/javascript", hasRange = false, hintedLength = 10L))
        assertEquals(BodyMode.STREAM_PLAIN, mode("image/png", hasRange = false, hintedLength = null))
        // a huge non-media body still streams plainly — the large path is only for media/range.
        assertEquals(BodyMode.STREAM_PLAIN, mode("application/octet-stream", hasRange = false, hintedLength = STREAM_THRESHOLD_BYTES * 4))
    }

    @Test
    fun decideBodyMode_smallMedia_buffers() {
        assertEquals(BodyMode.BUFFER, mode("video/webm", hasRange = false, hintedLength = 1_000L))
        assertEquals(BodyMode.BUFFER, mode("audio/ogg", hasRange = false, hintedLength = STREAM_THRESHOLD_BYTES))
        // VIDEO/WEBM — media check is case-insensitive.
        assertEquals(BodyMode.BUFFER, mode("VIDEO/WEBM", hasRange = false, hintedLength = 1L))
    }

    @Test
    fun decideBodyMode_mediaNoHint_buffers() {
        // unknown size (no withContentLength) → buffer to preserve Range support; only a hinted
        // size can take the large-stream path.
        assertEquals(BodyMode.BUFFER, mode("video/mp4", hasRange = false, hintedLength = null))
    }

    @Test
    fun decideBodyMode_largeMedia_streamsLarge() {
        assertEquals(BodyMode.STREAM_LARGE, mode("video/webm", hasRange = false, hintedLength = STREAM_THRESHOLD_BYTES + 1))
    }

    @Test
    fun decideBodyMode_rangeRequest_nonMedia_dependsOnSize() {
        // a Range request on a non-media body still gets Range-capable handling.
        assertEquals(BodyMode.BUFFER, mode("application/octet-stream", hasRange = true, hintedLength = 500L))
        assertEquals(BodyMode.STREAM_LARGE, mode("application/octet-stream", hasRange = true, hintedLength = STREAM_THRESHOLD_BYTES + 1))
        // range request, size unknown → buffer.
        assertEquals(BodyMode.BUFFER, mode("application/octet-stream", hasRange = true, hintedLength = null))
    }

    // --- parseRange ---

    @Test
    fun parseRange_closedRange() {
        assertEquals(10L to 19L, Html5LocalHttpServer.parseRange("bytes=10-19", total = 100L))
    }

    @Test
    fun parseRange_openEndedRange_clampsToLastByte() {
        assertEquals(10L to 99L, Html5LocalHttpServer.parseRange("bytes=10-", total = 100L))
    }

    @Test
    fun parseRange_suffixRange_lastNBytes() {
        assertEquals(80L to 99L, Html5LocalHttpServer.parseRange("bytes=-20", total = 100L))
    }

    @Test
    fun parseRange_suffixLongerThanTotal_clampsToZero() {
        assertEquals(0L to 99L, Html5LocalHttpServer.parseRange("bytes=-500", total = 100L))
    }

    @Test
    fun parseRange_endBeyondTotal_clamps() {
        assertEquals(50L to 99L, Html5LocalHttpServer.parseRange("bytes=50-999", total = 100L))
    }

    @Test
    fun parseRange_startBeyondTotal_unsatisfiable() {
        assertNull(Html5LocalHttpServer.parseRange("bytes=100-", total = 100L))
        assertNull(Html5LocalHttpServer.parseRange("bytes=200-300", total = 100L))
    }

    @Test
    fun parseRange_multiRange_rejected() {
        assertNull(Html5LocalHttpServer.parseRange("bytes=0-10,20-30", total = 100L))
    }

    @Test
    fun parseRange_garbage_rejected() {
        assertNull(Html5LocalHttpServer.parseRange("rows=0-10", total = 100L))
        assertNull(Html5LocalHttpServer.parseRange("bytes=abc", total = 100L))
        assertNull(Html5LocalHttpServer.parseRange("bytes=-0", total = 100L))
    }

    @Test
    fun parseRange_zeroTotal_unsatisfiable() {
        assertNull(Html5LocalHttpServer.parseRange("bytes=0-10", total = 0L))
    }
}
