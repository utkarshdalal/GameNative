package app.gamenative.savebackup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber

/**
 * The outcome of transferring a single file through a stream copy + byte-count verification.
 *
 * On any non-[Success] result the destination is **not** presented as a valid transferred save
 * (Requirements 7.6, 13.1). [StreamTransfer] itself does not commit or expose the destination — it
 * only returns the result; the calling codec/engine is responsible for staging so that a
 * destination is only ever treated as valid after a [Success].
 */
sealed interface FileTransferResult {
    /** The copy completed and the bytes written to the destination equal the source byte size (Req 7.5). */
    data class Success(val bytes: Long) : FileTransferResult

    /**
     * The copy completed but the number of bytes written to the destination did not equal the
     * source byte size (Requirement 7.6). The destination must not be treated as a valid save.
     */
    data class ByteMismatch(val written: Long, val expected: Long) : FileTransferResult

    /**
     * The source or destination stream could not be opened, or an I/O error occurred during the
     * copy (Requirement 13.1). The transfer is aborted and never reported as successful.
     */
    data class StreamError(val message: String) : FileTransferResult
}

/**
 * The one stream-based file-transfer implementation used for **every** external location
 * (Requirement 7.3), regardless of the backing SAF `DocumentsProvider` — local storage, SD card,
 * USB OTG, or a WebDAV/rclone provider. External I/O always goes through `DocumentFile` +
 * `ContentResolver` streams and **never** converts the SAF tree URI to a filesystem path
 * (Requirements 7.1, 7.2).
 *
 * ## Design: testable without a live ContentResolver
 *
 * The core [transfer] operates purely on **stream providers** — a source `() -> InputStream?`, a
 * destination `() -> OutputStream?`, and the expected source byte size — and returns a
 * [FileTransferResult]. It has no dependency on Android's `ContentResolver`, so the byte-count
 * invariant (Property 6, task 6.2) can be exercised with an in-memory / `DocumentFile`-shaped fake
 * for the external side.
 *
 * The [transferToDocument] / [transferFromDocument] helpers are thin adapters that obtain those
 * streams from `DocumentFile` + `ContentResolver` for the real SAF path. They contain no copy or
 * verify logic of their own; they only wire streams into [transfer].
 *
 * ## Byte-count-verify pattern (reused from `CustomGameImporter.moveTree`)
 *
 * The copy loop counts the actual number of bytes **written to the destination** (Requirement 7.4)
 * using the same 64 KiB buffer as `CustomGameImporter.moveTree`, then compares that count to the
 * source byte size. Equal → [FileTransferResult.Success]; unequal → [FileTransferResult.ByteMismatch]
 * (Requirements 7.4, 7.5, 7.6). A null stream or an I/O failure yields
 * [FileTransferResult.StreamError] (Requirement 13.1).
 */
object StreamTransfer {

    /** Match `CustomGameImporter.moveTree`'s 64 KiB copy buffer. */
    private const val BUFFER_SIZE = 1 shl 16

    private const val TAG = "StreamTransfer"

    /**
     * Copy a single file from a source stream to a destination stream and verify the byte count.
     *
     * This is the source-agnostic core used for every external location. It is intentionally
     * decoupled from Android's `ContentResolver` (it takes stream providers) so it can be property
     * tested against an in-memory fake for the external side.
     *
     * @param openSource obtains the source [InputStream]; returning `null` or throwing is treated
     *   as a stream-open failure (Requirement 13.1).
     * @param openDest obtains the destination [OutputStream]; returning `null` or throwing is
     *   treated as a stream-open failure (Requirement 13.1).
     * @param sourceSize the byte size of the source file, compared against the bytes actually
     *   written to the destination (Requirement 7.4).
     * @return [FileTransferResult.Success] when bytes written equal [sourceSize],
     *   [FileTransferResult.ByteMismatch] when they differ (destination not valid),
     *   [FileTransferResult.StreamError] when a stream cannot be opened or an I/O error occurs.
     */
    suspend fun transfer(
        openSource: () -> InputStream?,
        openDest: () -> OutputStream?,
        sourceSize: Long,
        onBytes: (Long) -> Unit = {},
    ): FileTransferResult {
        val input: InputStream = try {
            openSource() ?: return FileTransferResult.StreamError("Could not open source stream")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to open source stream")
            return FileTransferResult.StreamError("Could not open source stream: ${e.message}")
        }

        return input.use { ins ->
            val output: OutputStream = try {
                openDest() ?: return FileTransferResult.StreamError("Could not open destination stream")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to open destination stream")
                return FileTransferResult.StreamError("Could not open destination stream: ${e.message}")
            }

            output.use { out ->
                var written = 0L
                try {
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        onBytes(n.toLong())
                    }
                    out.flush()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // ensureActive() throws this on cancellation; propagate it rather than
                    // masking it as a StreamError so the caller can distinguish cancel from I/O.
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "I/O error during copy")
                    return FileTransferResult.StreamError("I/O error during copy: ${e.message}")
                }

                // Compare bytes WRITTEN to destination against the source byte size (Req 7.4).
                if (written == sourceSize) {
                    FileTransferResult.Success(written)
                } else {
                    FileTransferResult.ByteMismatch(written = written, expected = sourceSize)
                }
            }
        }
    }

    /**
     * Real SAF path: copy an in-container source file to a destination `DocumentFile` on the
     * external location, using `ContentResolver` streams only — the tree URI is never converted to
     * a filesystem path (Requirements 7.1, 7.2, 7.3).
     *
     * @param sourceSize the byte size of the source file (Requirement 7.4).
     */
    suspend fun transferToDocument(
        context: Context,
        openSource: () -> InputStream?,
        sourceSize: Long,
        destination: DocumentFile,
        onBytes: (Long) -> Unit = {},
    ): FileTransferResult = transfer(
        openSource = openSource,
        openDest = { openDocumentOutput(context, destination.uri) },
        sourceSize = sourceSize,
        onBytes = onBytes,
    )

    /**
     * Real SAF path: copy a source `DocumentFile` on the external location to an in-container
     * destination, using `ContentResolver` streams only for the external read — the tree URI is
     * never converted to a filesystem path (Requirements 7.1, 7.2, 7.3).
     *
     * The source byte size is read from `DocumentFile.length()`.
     */
    suspend fun transferFromDocument(
        context: Context,
        source: DocumentFile,
        openDest: () -> OutputStream?,
        onBytes: (Long) -> Unit = {},
    ): FileTransferResult = transfer(
        openSource = { openDocumentInput(context, source.uri) },
        openDest = openDest,
        sourceSize = source.length(),
        onBytes = onBytes,
    )

    /** Open an external source stream via `ContentResolver` (no filesystem-path conversion). */
    private fun openDocumentInput(context: Context, uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    /** Open an external destination stream via `ContentResolver` (no filesystem-path conversion). */
    private fun openDocumentOutput(context: Context, uri: Uri): OutputStream? =
        context.contentResolver.openOutputStream(uri, "wt")
}
