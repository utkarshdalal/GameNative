package app.gamenative.html5.savesync

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import timber.log.Timber

// chromium's LS and IDB leveldbs can wedge into a permanent compaction-error loop after a kill
// mid-compaction (low-memory kill, ANR, reinstall); once wedged, every launch silently drops new
// writes and chromium has no auto-repair for LS.
//
// MUST run before any WebView opens -- chromium locks the leveldb dirs at first WebView creation.
//
// iq80 0.12's `repair` is an UnsupportedOperationException stub, so in practice every wedge is
// wiped; repair is still tried first in case a later iq80 implements it.
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

        // without this the next launch's inbound gate still reads "wine unchanged", skips the restore, and exit
        // sync copies the empty store over the Wine copy and uploads it.
        if (wiped > 0) {
            runCatching { Html5SaveSyncService.clearAllSyncState(context) }
                .onFailure { Timber.tag("Html5LeveldbHealth").e(it, "clearAllSyncState failed") }
        }

        if (wedged > 0) {
            Timber.tag("Html5LeveldbHealth").i(
                "boot scan: scanned=%d wedged=%d repaired=%d wiped=%d",
                candidates.size, wedged, repaired, wiped,
            )
        } else {
            Timber.tag("Html5LeveldbHealth").d("boot scan clean: scanned=%d", candidates.size)
        }

        // after wedge repair so it never opens a wedged store.
        runCatching { Html5PendingLsPurge.purgeAtBoot(context) }
            .onFailure { Timber.tag("Html5LeveldbHealth").e(it, "Html5PendingLsPurge failed") }

        // TEMPORARY: rides this pre-WebView boot pass; after wedge repair for the same reason.
        runCatching { Html5LsOriginCleanup.runOnce(context) }
            .onFailure { Timber.tag("Html5LeveldbHealth").e(it, "Html5LsOriginCleanup failed") }

        return RepairResult(candidates.size, wedged, repaired, wiped)
    }

    private data class Candidate(val dir: File, val useIdb1: Boolean)

    // IDB needs chromium's `idb_cmp1` comparator or repair re-sorts wrong; LS is bytewise.
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
            // UnsupportedOperationException is iq80's expected stub; don't log its stack.
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
