package app.gamenative.html5

import java.io.File
import java.io.IOException

// save writes go through a temp sibling + rename: every caller can be killed mid-write (android
// tears processes down right at launch/exit boundaries), and a straight writeBytes truncates the
// old save before the new bytes land, so a kill in that window leaves neither.
internal const val GN_TEMP_SUFFIX = ".gntmp"

// a staging file left by a kill is never a game file; hide it from listings handed to game code.
internal fun isGnTempName(name: String): Boolean = name.endsWith(GN_TEMP_SUFFIX)

// same-directory temp keeps the rename on one filesystem. leaves the previous file alone if the
// rename fails -- a truncated save is worse than a stale one.
// identical bytes are NOT rewritten: GOG's upload is mtime-driven and the OPFS exit flush hands
// back every file each session, so a blind rewrite would re-upload the whole save set.
internal fun File.writeBytesAtomic(bytes: ByteArray) {
    if (isFile && length() == bytes.size.toLong() && readBytes().contentEquals(bytes)) return
    val tmp = File(parentFile, name + GN_TEMP_SUFFIX)
    try {
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(this)) throw IOException("rename ${tmp.name} -> $name failed")
    } finally {
        tmp.delete() // no-op once the rename took it
    }
}
