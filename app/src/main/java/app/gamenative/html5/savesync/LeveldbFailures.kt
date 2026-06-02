package app.gamenative.html5.savesync

import java.io.File
import java.io.FileNotFoundException

// shared iq80/leveldb exception → SaveSyncFailure classification. extracted from the
// near-verbatim copies that lived in LevelDbRewriter + RmmvSaveMapper so the mapping is
// defined ONCE. both keep thin `classifyFailure` wrappers (test seams call them directly).
internal object LeveldbFailures {

    // [sstLdbAsCorruption] = LevelDbRewriter-only branch: an FNE naming a `.sst`/`.ldb` file is
    // a MANIFEST-references-missing-table case (internally-inconsistent snapshot), which we tag
    // Corruption rather than PathMissing. RmmvSaveMapper has no such branch, so it passes false.
    fun classify(t: Throwable, src: File, dst: File, sstLdbAsCorruption: Boolean): SaveSyncFailure {
        val msg = t.message?.lowercase().orEmpty()
        return when {
            msg.contains("lock") -> SaveSyncFailure.LockContention(t)
            msg.contains("corrupt") || isCorruptionLike(t) -> SaveSyncFailure.Corruption(src.absolutePath, t)
            sstLdbAsCorruption && t is FileNotFoundException && (msg.contains(".sst") || msg.contains(".ldb")) ->
                SaveSyncFailure.Corruption(src.absolutePath, t)
            t is FileNotFoundException || msg.contains("no such file") -> SaveSyncFailure.PathMissing(src.absolutePath)
            t is SecurityException || msg.contains("permission denied") -> SaveSyncFailure.PermissionDenied(src.absolutePath, t)
            else -> SaveSyncFailure.Other(t)
        }
    }

    // iq80 surfaces corruption via DBException message OR nested class names; we only tag a
    // raw DBException as corruption if its class name contains "corruption" (leaves plain
    // DBException for lock/etc to fall through message keywords).
    fun isCorruptionLike(t: Throwable): Boolean {
        val cls = t::class.java.name.lowercase()
        if (cls.contains("corruption")) return true
        val cause = t.cause
        if (cause != null && cause !== t) {
            val causeCls = cause::class.java.name.lowercase()
            if (causeCls.contains("corruption")) return true
        }
        return false
    }
}
