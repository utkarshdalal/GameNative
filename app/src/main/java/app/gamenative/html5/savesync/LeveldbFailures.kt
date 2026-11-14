package app.gamenative.html5.savesync

import java.io.File
import java.io.FileNotFoundException

// iq80/leveldb exception -> SaveSyncFailure, shared by LevelDbRewriter and RmmvSaveMapper (both keep
// thin `classifyFailure` wrappers because tests call them directly).
internal object LeveldbFailures {

    // [sstLdbAsCorruption]: an FNE naming a `.sst`/`.ldb` means the MANIFEST references a missing
    // table (inconsistent snapshot), so it's Corruption, not PathMissing.
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

    // iq80 signals corruption via exception class name (or its cause's); a plain DBException must
    // fall through to the message-keyword checks.
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
