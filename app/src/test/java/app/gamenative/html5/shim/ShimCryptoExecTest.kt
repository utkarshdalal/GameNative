package app.gamenative.html5.shim

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// EXECUTES crypto.js under Rhino against NIST SP800-38A AES-256-CTR test vectors (F.5.5/F.5.6).
// CTR decrypt == encrypt (XOR with keystream), so feeding the published ciphertext through
// createDecipheriv('aes-256-ctr').update() must yield the published plaintext byte-for-byte.
// a one-char regression in the S-box / MixColumns / counter increment silently corrupts every
// encrypted-asset title (OMORI lineage) -- a string-match test can't catch that, this can.
class ShimCryptoExecTest {
    private lateinit var js: ShimJsRuntime

    // NIST SP800-38A F.5 CTR-AES256. counter block == IV; 4 plaintext/ciphertext blocks.
    private val keyHex = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4"
    private val ivHex = "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
    private val cipherHex =
        "601ec313775789a5b7a7f504bbf3d228" +
            "f443e3ca4d62b59aca84e990cacaf5c5" +
            "2b0930daa23de94ce87017ba2d84988d" +
            "dfc9c58db67aada613c2dd08457941a6"
    private val plainHex =
        "6bc1bee22e409f96e93d7e117393172a" +
            "ae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52ef" +
            "f69f2445df4f9b17ad2b417be66c3710"

    @Before
    fun setUp() {
        js = ShimJsRuntime().load("require-dispatcher.js").load("crypto.js")
        // hex<->bytes helpers + the fixed vectors as scope globals.
        js.eval(
            """
            var hexToBytes = function (h) {
                var a = new Uint8Array(h.length / 2);
                for (var i = 0; i < a.length; i++) a[i] = parseInt(h.substr(i * 2, 2), 16);
                return a;
            };
            var bytesToHex = function (b) {
                var s = '';
                for (var i = 0; i < b.length; i++) {
                    var x = (b[i] & 0xff).toString(16);
                    s += (x.length < 2 ? '0' : '') + x;
                }
                return s;
            };
            var crypto = window.require('crypto');
            var KEY = hexToBytes('$keyHex');
            var IV = hexToBytes('$ivHex');
            var CT = hexToBytes('$cipherHex');
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        js.close()
    }

    @Test
    fun decrypts_nist_vector_in_one_update() {
        val out = js.evalString(
            "bytesToHex(crypto.createDecipheriv('aes-256-ctr', KEY, IV).update(CT))",
        )
        assertEquals(plainHex, out)
    }

    @Test
    fun multi_chunk_update_matches_single_shot() {
        // split mid-block (10) and across a block boundary (30) to exercise keystreamPos carry
        // across .update() calls + counter increment between blocks.
        val out = js.evalString(
            """
            (function () {
                var d = crypto.createDecipheriv('aes-256-ctr', KEY, IV);
                var p1 = bytesToHex(d.update(CT.subarray(0, 10)));
                var p2 = bytesToHex(d.update(CT.subarray(10, 30)));
                var p3 = bytesToHex(d.update(CT.subarray(30)));
                return p1 + p2 + p3;
            })()
            """.trimIndent(),
        )
        assertEquals(plainHex, out)
    }

    @Test
    fun final_returns_empty() {
        // CTR has no padding -- final() must be 0 bytes so Buffer.concat([update, final]) is exact.
        assertEquals(
            "0",
            js.evalString("String(crypto.createDecipheriv('aes-256-ctr', KEY, IV).final().length)"),
        )
    }

    @Test
    fun update_returns_uint8array_without_buffer_global() {
        // crypto test does not load fs.js, so window.Buffer is absent -> Uint8Array result.
        assertTrue(
            js.evalBoolean(
                "crypto.createDecipheriv('aes-256-ctr', KEY, IV).update(CT) instanceof Uint8Array",
            ),
        )
    }

    @Test
    fun wrong_key_length_throws() {
        assertTrue(
            js.evalBoolean(
                """
                (function () {
                    try { crypto.createDecipheriv('aes-256-ctr', KEY.subarray(0, 31), IV); return false; }
                    catch (e) { return true; }
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun wrong_iv_length_throws() {
        assertTrue(
            js.evalBoolean(
                """
                (function () {
                    try { crypto.createDecipheriv('aes-256-ctr', KEY, IV.subarray(0, 15)); return false; }
                    catch (e) { return true; }
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun unsupported_algorithm_throws_not_implemented() {
        val code = js.evalString(
            """
            (function () {
                try { crypto.createDecipheriv('aes-128-cbc', KEY, IV); return 'NO_THROW'; }
                catch (e) { return e.message; }
            })()
            """.trimIndent(),
        )
        assertTrue("expected NOT_IMPLEMENTED_V1, got: $code", code.contains("NOT_IMPLEMENTED_V1"))
    }
}
