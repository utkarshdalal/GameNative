package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import java.io.File
import java.io.RandomAccessFile
import timber.log.Timber

private fun hex(offset: Long): String = "0x${offset.toString(16)}"

/**
 * One fixed byte range of a game file, named by its offset and the bytes that have to be there already.
 */
class BinaryPatch(
    val offset: Long,
    original: ByteArray,
    replacement: ByteArray,
) {
    val original: ByteArray = original.copyOf()
    val replacement: ByteArray = replacement.copyOf()

    init {
        require(offset >= 0) { "Patch offset $offset is negative" }
        require(original.isNotEmpty()) { "Patch at ${hex(offset)} has no bytes" }
        require(original.size == replacement.size) {
            "Patch at ${hex(offset)} swaps ${original.size} bytes for ${replacement.size}"
        }
    }
}

/**
 * Overwrites fixed byte ranges of a game file on every launch.
 *
 * Used for games whose shipped executable carries a bug that only shows up under the container and
 * that the one build on the store can be corrected for in place. [expectedFileLength] and the original
 * bytes of every patch pin that build down: a file that reads back as neither the original nor the
 * finished patch is left alone, so an unknown build is never written to.
 */
class BinaryPatchFix(
    private val targetRelativePath: String,
    private val expectedFileLength: Long? = null,
    private val patches: List<BinaryPatch>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        val target = File(installPath, targetRelativePath)
        return try {
            if (!target.isFile) return skipped(gameId, "'$targetRelativePath' is not in the game folder")
            val length = target.length()
            if (expectedFileLength != null && length != expectedFileLength) {
                return skipped(gameId, "'$targetRelativePath' is $length bytes, not the $expectedFileLength this patch is for")
            }
            RandomAccessFile(target, "rw").use { file -> patch(gameId, file) }
        } catch (e: Throwable) {
            skipped(gameId, "patching '$targetRelativePath' failed", e)
        }
    }

    private fun patch(gameId: String, file: RandomAccessFile): Boolean {
        val pending = ArrayList<BinaryPatch>(patches.size)
        for (patch in patches) {
            val current = ByteArray(patch.original.size)
            file.seek(patch.offset)
            file.readFully(current)
            when {
                current.contentEquals(patch.replacement) -> Unit
                current.contentEquals(patch.original) -> pending += patch
                else -> return skipped(gameId, "'$targetRelativePath' holds unknown bytes at ${hex(patch.offset)}")
            }
        }
        if (pending.isEmpty()) return true

        for (patch in pending) {
            file.seek(patch.offset)
            file.write(patch.replacement)
        }
        file.fd.sync()
        Timber.tag("GameFixes")
            .i("Patched '$targetRelativePath' at ${pending.joinToString { hex(it.offset) }} for game $gameId")
        return true
    }

    private fun skipped(gameId: String, reason: String, error: Throwable? = null): Boolean {
        val message = "Skipping the byte patch of '$targetRelativePath' for game $gameId: $reason"
        if (error != null) {
            Timber.tag("GameFixes").w(error, message)
        } else {
            Timber.tag("GameFixes").w(message)
        }
        return false
    }
}
