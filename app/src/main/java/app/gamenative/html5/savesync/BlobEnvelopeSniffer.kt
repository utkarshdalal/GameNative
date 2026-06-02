package app.gamenative.html5.savesync

import java.io.File
import timber.log.Timber

// pre-sync gate: classifies IDB blob files by Blink SSV envelope prefix to catch cross-chromium
// format skew BEFORE writing bytes this device's WebView can't parse. Electron 39 / Chromium ~140
// blobs start `ff 11 02 ...`, WebView 109 writes `ff 15 fe ...`; importing the former succeeds on disk
// but WebView 109 deserializes blob-wrapped values as undefined and the game crashes on slot load.
// heuristic only -- a runtime probe of the device's WebView would be the real check.
object BlobEnvelopeSniffer {

    // enough to tell `ff 11 02` (desktop legacy-compat) from `ff 15 fe` (WebView 109 trailer-info).
    private const val SIGNATURE_BYTES = 3

    data class Signature(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Signature && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        fun hex(): String = bytes.joinToString(" ") { "%02x".format(it) }
    }

    data class Report(
        val blobsExamined: Int,
        val distinctSignatures: Map<Signature, Int>,
        val firstOffender: File?,
    )

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

    // only WebView 109's shape so far: `ff 15` = Blink envelope v21, `fe` = trailer-info block.
    val POC_COMPATIBLE_SIGNATURES: Set<Signature> = setOf(
        Signature(byteArrayOf(0xFF.toByte(), 0x15, 0xFE.toByte())),
    )

    // let through the inbound gate: LevelDbRewriter strips the `ff 11 02` snappy wrapper when it
    // inlines sidecar bytes (maybeDecompressSnappyValue), leaving native `ff 15 fe` SSV.
    val REWRITABLE_SIGNATURES: Set<Signature> = setOf(
        Signature(byteArrayOf(0xFF.toByte(), 0x11, 0x02)),
    )
}
