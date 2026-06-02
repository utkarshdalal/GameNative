package app.gamenative.html5.savesync

import java.io.File
import timber.log.Timber

// reads the first N bytes of every chromium IDB blob file under a directory tree and
// classifies them by Blink SSV envelope prefix. used as a pre-sync gate to detect
// cross-chromium-version format skew BEFORE silently writing bytes this device's
// WebView cannot parse.

// BACKGROUND. Electron 39 / Chromium ~140 cloud saves (e.g. Wayward) blob-wrapped IDB
// records start with `ff 11 02 <5 bytes> ff 15 fe <6 bytes>`. WebView 109 writes blobs
// starting `ff 15 fe <13 zeros>`. Importing desktop bytes succeeds at the file level but
// WebView 109's SSV deserializer silently returns undefined for blob-wrapped values,
// crashing the game on slot load.

// this sniffer is a PoC heuristic, not a source of truth. real validation would runtime-probe
// the device's WebView by writing a synthetic blob-wrapped record, reading back the envelope,
// and persisting the fingerprint. follow-up todo.
object BlobEnvelopeSniffer {

    // bytes captured per blob. 3 bytes is enough to tell `ff 11 02` (desktop legacy compat
    // prefix) from `ff 15 fe` (WebView 109 trailer-info prefix). keep small so scanning a
    // large blob tree stays cheap.
    private const val SIGNATURE_BYTES = 3

    data class Signature(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Signature && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        fun hex(): String = bytes.joinToString(" ") { "%02x".format(it) }
    }

    data class Report(
        val blobsExamined: Int,
        val distinctSignatures: Map<Signature, Int>, // signature → count
        val firstOffender: File?,                    // first blob matching a non-compatible signature
    )

    // walks `.indexeddb.blob/<db>/<bucket>/<blob>` and returns every distinct signature found.
    // null-safe -- missing dir returns an empty report (no blobs, no problem).
    fun inspect(blobDir: File?, compatibleSignatures: Set<Signature>): Report {
        if (blobDir == null || !blobDir.isDirectory) {
            return Report(blobsExamined = 0, distinctSignatures = emptyMap(), firstOffender = null)
        }
        val counts = mutableMapOf<Signature, Int>()
        var examined = 0
        var firstOffender: File? = null
        blobDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val sig = readSignature(f) ?: return@forEach
            examined++
            counts.merge(sig, 1, Int::plus)
            if (firstOffender == null && sig !in compatibleSignatures) {
                firstOffender = f
            }
        }
        return Report(
            blobsExamined = examined,
            distinctSignatures = counts,
            firstOffender = firstOffender,
        )
    }

    private fun readSignature(f: File): Signature? {
        val buf = ByteArray(SIGNATURE_BYTES)
        return runCatching {
            f.inputStream().use { it.read(buf) }
            Signature(buf)
        }.onFailure {
            Timber.tag("BlobEnvelopeSniffer").w(it, "failed to read signature from %s", f.absolutePath)
        }.getOrNull()
    }

    // signatures known compatible with the device's WebView. PoC: hardcoded to WebView 109's
    // trailer-info envelope (`ff 15 fe`). extend when we add more target WebView versions or
    // replace with a runtime probe.
    
    // why this signature: WebView 109.0.5414 writes blob-wrapped IDB records with prefix
    // `ff 15 fe 00*13 ff 0f 6f ...`. `ff 15` = Blink envelope v21, `fe 00...` = trailer-info
    // block (chromium ~115+ feature).
    val POC_COMPATIBLE_SIGNATURES: Set<Signature> = setOf(
        Signature(byteArrayOf(0xFF.toByte(), 0x15, 0xFE.toByte())),
    )

    // signatures we let through the inbound gate because LevelDbRewriter handles them inline
    // when sidecar bytes are inlined into IDB records (`maybeDecompressSnappyValue` strips the
    // ff 11 02 snappy wrapper and emits native ff 15 fe V8 SSV).
    // captures the Electron 39 / Chromium ~140 legacy-compat shape -- the only non-native sig
    // observed on device so far.
    val REWRITABLE_SIGNATURES: Set<Signature> = setOf(
        Signature(byteArrayOf(0xFF.toByte(), 0x11, 0x02)),
    )
}
