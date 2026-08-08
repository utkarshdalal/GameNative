package app.gamenative.html5.savesync

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import timber.log.Timber

// boot-time defensive pass. chromium webview's LocalStorage and IndexedDB leveldbs can wedge
// into a permanent compaction-error loop after a force-stop mid-compaction (android low-memory
// kill, ANR, `adb install -r`). LOG signature:
// "Compaction error: Corruption: not an sstable (bad magic number)"
// once wedged, every WebView launch silently drops new writes until the user clears app data --
// chromium ships no auto-repair for LocalStorage.

// MUST run before any WebView opens. chromium takes an exclusive lock on the leveldb dirs the
// moment the first WebView is created.

// NOTE on iq80 0.12: `Iq80DBFactory.repair` is a permanent `throw new UnsupportedOperationException()`
// stub -- it is not implemented. so in practice every wedge falls through to wipe. we still call
// repair first in case a future iq80 version implements it (free upgrade with no code change).
object Html5LeveldbHealth {

    private const val WEDGE_SIGNATURE = "Compaction error: Corruption: not an sstable"
    private const val LOG_TAIL_BYTES = 32 * 1024L

    data class RepairResult(
        val scanned: Int,
        val wedged: Int,
        val repaired: Int,
        val wiped: Int,
    )

    fun repairIfWedged(context: Context): RepairResult {
        val appWebview = File(context.dataDir, "app_webview")
        if (!appWebview.isDirectory) {
            return RepairResult(0, 0, 0, 0)
        }

        val candidates = collectCandidates(appWebview)
        var wedged = 0
        var repaired = 0
        var wiped = 0

        for (candidate in candidates) {
            if (!hasWedgeSignature(candidate.dir)) continue
            wedged++
            when (repairOrWipe(candidate)) {
                Outcome.REPAIRED -> repaired++
                Outcome.WIPED -> wiped++
            }
        }

        if (wedged > 0) {
            Timber.tag("Html5LeveldbHealth").i(
                "boot scan: scanned=%d wedged=%d repaired=%d wiped=%d",
                candidates.size, wedged, repaired, wiped,
            )
        } else {
            Timber.tag("Html5LeveldbHealth").d("boot scan clean: scanned=%d", candidates.size)
        }

        return RepairResult(candidates.size, wedged, repaired, wiped)
    }

    private data class Candidate(val dir: File, val useIdb1: Boolean)

    // chromium IDB requires `idb_cmp1` comparator -- iq80 must match or repair re-sorts wrong.
    // LS uses default bytewise.
    private fun collectCandidates(appWebview: File): List<Candidate> {
        val profiles = appWebview.listFiles { f ->
            f.isDirectory && (f.name == "Default" || f.name.startsWith("Profile-"))
        } ?: return emptyList()

        val out = mutableListOf<Candidate>()
        for (profile in profiles) {
            val ls = File(profile, "Local Storage/leveldb")
            if (ls.hasLevelDbLog()) out.add(Candidate(ls, useIdb1 = false))

            val idbRoot = File(profile, "IndexedDB")
            val idbLevelDbs = idbRoot.listFiles { f ->
                f.isDirectory && f.name.endsWith(".leveldb")
            } ?: emptyArray()
            for (db in idbLevelDbs) {
                if (db.hasLevelDbLog()) out.add(Candidate(db, useIdb1 = true))
            }
        }
        return out
    }

    private fun File.hasLevelDbLog(): Boolean = isDirectory && File(this, "LOG").isFile

    private fun hasWedgeSignature(dir: File): Boolean {
        val logs = listOf("LOG", "LOG.old").map { File(dir, it) }.filter { it.isFile }
        return logs.any { containsSignature(it) }
    }

    private fun containsSignature(file: File): Boolean {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = (len - LOG_TAIL_BYTES).coerceAtLeast(0L)
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                String(buf, Charsets.US_ASCII).contains(WEDGE_SIGNATURE)
            }
        }.getOrDefault(false)
    }

    private enum class Outcome { REPAIRED, WIPED }

    private fun repairOrWipe(candidate: Candidate): Outcome {
        val tag = candidate.dir.relativePath()
        val repairOk = runCatching {
            Iq80DBFactory.factory.repair(candidate.dir, repairOptions(candidate.useIdb1))
        }.onFailure { t ->
            // iq80 0.12 always throws UnsupportedOperationException -- silent so the wedged-boot
            // log isn't dominated by an expected stack trace. real repair failures (any other
            // throwable) keep the warn+stack so they stay diagnosable.
            if (t !is UnsupportedOperationException) {
                Timber.tag("Html5LeveldbHealth").w(t, "repair failed for %s — falling back to wipe", tag)
            }
        }.isSuccess

        if (repairOk) {
            Timber.tag("Html5LeveldbHealth").i("repaired %s", tag)
            return Outcome.REPAIRED
        }

        val wipedBytes = candidate.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        candidate.dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        Timber.tag("Html5LeveldbHealth").w("wiped %s (%d bytes)", tag, wipedBytes)
        return Outcome.WIPED
    }

    private fun repairOptions(useIdb1: Boolean): Options = Options().apply {
        createIfMissing(false)
        errorIfExists(false)
        paranoidChecks(false)
        compressionType(CompressionType.SNAPPY)
        if (useIdb1) comparator(Idb1Comparator())
    }

    private fun File.relativePath(): String {
        val parts = path.split("/app_webview/")
        return if (parts.size == 2) "app_webview/" + parts[1] else absolutePath
    }
}
