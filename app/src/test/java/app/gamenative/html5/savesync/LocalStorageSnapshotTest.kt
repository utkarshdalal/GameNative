package app.gamenative.html5.savesync

import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalStorageSnapshotTest {

    private fun b64Utf16(s: String): String {
        val out = ByteArray(s.length * 2)
        s.forEachIndexed { i, c ->
            out[2 * i] = (c.code and 0xFF).toByte()
            out[2 * i + 1] = (c.code shr 8).toByte()
        }
        return Base64.getEncoder().encodeToString(out)
    }

    // what evaluateJavascript hands back: the script's JSON string, JSON-encoded again
    private fun evaluateResult(vararg kv: Pair<String, String>): String {
        val inner = kv.joinToString(",", "[", "]") { (k, v) -> "[\"${b64Utf16(k)}\",\"${b64Utf16(v)}\"]" }
        return JsonPrimitive(inner).toString()
    }

    @Test
    fun encode_usesLatin1WhenEveryCodeUnitFitsAByte() {
        assertArrayEquals(byteArrayOf(1, 't'.code.toByte(), 'r'.code.toByte()), LocalStorageSnapshot.encode("tr".toCharArray()))
        assertArrayEquals(byteArrayOf(1, 0xE9.toByte()), LocalStorageSnapshot.encode("é".toCharArray()))
    }

    @Test
    fun encode_usesUtf16LeOtherwise() {
        assertArrayEquals(byteArrayOf(0, 'a'.code.toByte(), 0, 0xAC.toByte(), 0x20), LocalStorageSnapshot.encode("a€".toCharArray()))
    }

    @Test
    fun parse_decodesPairsIntoChromiumEncoding() {
        val pairs = LocalStorageSnapshot.parse(evaluateResult("-1options" to "{\"v\":1}", "k€" to "\ud800"))!!

        assertEquals(2, pairs.size)
        assertArrayEquals(LocalStorageSnapshot.encode("-1options".toCharArray()), pairs[0].first)
        assertArrayEquals(LocalStorageSnapshot.encode("{\"v\":1}".toCharArray()), pairs[0].second)
        assertArrayEquals(LocalStorageSnapshot.encode("k€".toCharArray()), pairs[1].first)
        // lone surrogate survives the round trip
        assertArrayEquals(byteArrayOf(0, 0x00, 0xD8.toByte()), pairs[1].second)
    }

    @Test
    fun decode_reversesEncode_andReadsUnprefixedBytesAsLatin1() {
        for (s in listOf("tr", "é", "a€", "\ud800")) {
            assertEquals(s, String(LocalStorageSnapshot.decode(LocalStorageSnapshot.encode(s.toCharArray()))))
        }
        assertEquals("k1", String(LocalStorageSnapshot.decode("k1".toByteArray())))
    }

    // launch restore payload uses the capture's per-string encoding
    @Test
    fun toRestoreJson_isBase64Utf16Pairs() {
        val json = LocalStorageSnapshot.toRestoreJson(
            listOf(LocalStorageSnapshot.encode("k€".toCharArray()) to LocalStorageSnapshot.encode("v".toCharArray())),
        )

        assertEquals("[[\"${b64Utf16("k€")}\",\"${b64Utf16("v")}\"]]", json)
    }

    @Test
    fun parse_emptyStorageIsEmptyList() {
        assertEquals(0, LocalStorageSnapshot.parse(JsonPrimitive("[]").toString())!!.size)
    }

    @Test
    fun parse_nullWhenCaptureFailed() {
        assertNull(LocalStorageSnapshot.parse(null))
        assertNull(LocalStorageSnapshot.parse("null"))
        assertNull(LocalStorageSnapshot.parse("not json"))
        assertNull(LocalStorageSnapshot.parse(JsonPrimitive("[[\"%%%\"]]").toString()))
    }
}
