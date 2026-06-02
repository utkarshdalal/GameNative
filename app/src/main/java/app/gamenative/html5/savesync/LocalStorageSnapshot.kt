package app.gamenative.html5.savesync

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// the page's localStorage, read through evaluateJavascript just before WebView teardown. chromium
// commits the page's last LS writes to leveldb only after destroy(), so an outbound rewrite that reads
// the live leveldb misses them (e.g. keys a game deletes at session start and re-puts only at exit).
object LocalStorageSnapshot {

    // fires beforeunload first: games that save from window.onbeforeunload would otherwise only save
    // on the later about:blank load, after this read. only synchronous handler writes are seen.
    // keys/values go as base64 of UTF-16LE code units -- survives lone surrogates, which
    // encodeURIComponent would throw on.
    const val CAPTURE_JS =
        "(function(){try{window.dispatchEvent(new Event('beforeunload'));}catch(e){}" +
            "function u(s){var b='';for(var i=0;i<s.length;i++){var c=s.charCodeAt(i);" +
            "b+=String.fromCharCode(c&255,c>>8);}return btoa(b);}" +
            "try{var o=[];for(var j=0;j<localStorage.length;j++){var k=localStorage.key(j);" +
            "o.push([u(k),u(localStorage.getItem(k))]);}return JSON.stringify(o);}catch(e){return null;}})()"

    // evaluateJavascript result -> (chromium-encoded key, chromium-encoded value) pairs, or null when the
    // capture failed. the result is the script's return value JSON-encoded: a quoted string, or null.
    fun parse(evaluateResult: String?): List<Pair<ByteArray, ByteArray>>? = runCatching {
        val outer = Json.parseToJsonElement(evaluateResult ?: return null)
        if (outer !is JsonPrimitive || !outer.isString) return null
        Json.parseToJsonElement(outer.content).jsonArray.map { pair ->
            val kv = pair.jsonArray
            encode(utf16Le(kv[0].jsonPrimitive.content)) to encode(utf16Le(kv[1].jsonPrimitive.content))
        }
    }.getOrNull()

    private fun utf16Le(base64: String): CharArray {
        val b = Base64.getDecoder().decode(base64)
        return CharArray(b.size / 2) { i ->
            ((b[2 * i].toInt() and 0xFF) or ((b[2 * i + 1].toInt() and 0xFF) shl 8)).toChar()
        }
    }

    // chromium's LS string format: 0x01 + latin-1 when every code unit fits a byte, else 0x00 + UTF-16LE.
    // chromium picks by its internal string width, so bytes can differ from what it would write; both
    // formats decode to the same string.
    internal fun encode(chars: CharArray): ByteArray =
        if (chars.all { it.code <= 0xFF }) {
            ByteArray(chars.size + 1).also { out ->
                out[0] = 1
                chars.forEachIndexed { i, c -> out[i + 1] = c.code.toByte() }
            }
        } else {
            ByteArray(chars.size * 2 + 1).also { out ->
                out[0] = 0
                chars.forEachIndexed { i, c ->
                    out[2 * i + 1] = (c.code and 0xFF).toByte()
                    out[2 * i + 2] = (c.code shr 8).toByte()
                }
            }
        }

    // inverse of encode. unprefixed bytes (current chromium never writes them) read as latin-1.
    internal fun decode(bytes: ByteArray): CharArray = when {
        bytes.isNotEmpty() && bytes[0] == 1.toByte() ->
            CharArray(bytes.size - 1) { i -> (bytes[i + 1].toInt() and 0xFF).toChar() }
        bytes.isNotEmpty() && bytes[0] == 0.toByte() ->
            CharArray((bytes.size - 1) / 2) { i ->
                ((bytes[2 * i + 1].toInt() and 0xFF) or ((bytes[2 * i + 2].toInt() and 0xFF) shl 8)).toChar()
            }
        else -> CharArray(bytes.size) { i -> (bytes[i].toInt() and 0xFF).toChar() }
    }

    // launch-restore payload for ls-restore.js: JSON [[key, value], ...], each base64 of UTF-16LE code units like
    // CAPTURE_JS, from chromium-encoded pairs.
    fun toRestoreJson(entries: List<Pair<ByteArray, ByteArray>>): String =
        JsonArray(
            entries.map { (k, v) -> JsonArray(listOf(JsonPrimitive(utf16LeBase64(decode(k))), JsonPrimitive(utf16LeBase64(decode(v))))) },
        ).toString()

    private fun utf16LeBase64(chars: CharArray): String {
        val b = ByteArray(chars.size * 2)
        chars.forEachIndexed { i, c ->
            b[2 * i] = (c.code and 0xFF).toByte()
            b[2 * i + 1] = (c.code shr 8).toByte()
        }
        return Base64.getEncoder().encodeToString(b)
    }
}
