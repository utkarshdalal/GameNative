package app.gamenative.savebackup

import com.pholser.junit.quickcheck.From
import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.generator.java.lang.Encoded
import com.pholser.junit.quickcheck.generator.java.lang.Encoded.InCharset
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/**
 * Property test for [StreamTransfer.transfer] byte-count invariant — game-save-backup Task 6.2
 * (Property 6).
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration, no Robolectric). [StreamTransfer.transfer] is source-agnostic and takes
 * plain stream providers, so the external side is modelled with an in-memory, `DocumentFile`-shaped
 * fake: the source is a [ByteArrayInputStream] over arbitrary content and the destination is a
 * [ByteArrayOutputStream] — no `ContentResolver` is involved.
 *
 * The invariant is driven deterministically by varying the *declared* `sourceSize` against the
 * actual content size: `transfer` counts the bytes copied from the source and compares that count
 * to the declared `sourceSize`, so Success occurs exactly when the two are equal. This is the clean
 * biconditional driver called for by task 6.2.
 *
 * `transfer` is `suspend`, so each `@Property` (a plain function) drives it with [runBlocking].
 * Every `@Property` runs a minimum of 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class StreamTransferByteCountPropertyTest {

    // Feature: game-save-backup, Property 6: Stream transfer succeeds iff byte counts match —
    // when the destination faithfully records every byte, transfer reports Success iff the declared
    // source size equals the actual content size, and otherwise reports ByteMismatch and never
    // Success. The Success case additionally round-trips the content byte-for-byte.
    @Property(trials = 200)
    fun successIffDeclaredSizeMatchesContentSize(
        @From(Encoded::class) @InCharset("UTF-8") contentSeed: String,
        sizeDelta: Int,
    ) = runBlocking {
        val content: ByteArray = contentSeed.toByteArray(Charsets.UTF_8)
        val actualSize = content.size.toLong()

        // Arbitrary declared source size: sometimes exactly correct (delta == 0), sometimes wrong.
        // A non-negative, bounded declared size keeps the biconditional meaningful and deterministic.
        val declaredSize = (actualSize + (sizeDelta % 4096)).coerceAtLeast(0L)

        val dest = ByteArrayOutputStream()
        val openSource: () -> InputStream? = { ByteArrayInputStream(content) }
        val openDest: () -> OutputStream? = { dest }

        val result = StreamTransfer.transfer(openSource, openDest, sourceSize = declaredSize)

        // The destination always receives exactly the source content — the copy loop itself is
        // faithful; only the declared size varies.
        assertArrayEquals("destination must faithfully receive the source bytes", content, dest.toByteArray())

        if (declaredSize == actualSize) {
            assertEquals(
                "equal byte counts must yield Success",
                FileTransferResult.Success(actualSize),
                result,
            )
        } else {
            assertEquals(
                "unequal byte counts must yield ByteMismatch(written=actual, expected=declared)",
                FileTransferResult.ByteMismatch(written = actualSize, expected = declaredSize),
                result,
            )
            assertTrue("mismatch must never be reported as Success", result !is FileTransferResult.Success)
        }
    }

    // Feature: game-save-backup, Property 6: Stream transfer succeeds iff byte counts match —
    // the clean biconditional: for arbitrary content of size C and an arbitrary, independently
    // generated expected size E, the result is Success(C) exactly when E == C, and is
    // ByteMismatch(written=C, expected=E) — never Success — whenever E != C.
    @Property(trials = 100)
    fun resultIsSuccessIffExpectedSizeEqualsContentSize(
        @From(Encoded::class) @InCharset("UTF-8") contentSeed: String,
        expectedSeed: Int,
    ) = runBlocking {
        val content: ByteArray = contentSeed.toByteArray(Charsets.UTF_8)
        val contentSize = content.size.toLong()

        // An arbitrary non-negative expected size, spanning both the matching value and many
        // non-matching ones (byte sizes cannot be negative).
        val expectedSize = Math.floorMod(expectedSeed, 8192).toLong()

        val result = StreamTransfer.transfer(
            openSource = { ByteArrayInputStream(content) },
            openDest = { ByteArrayOutputStream() },
            sourceSize = expectedSize,
        )

        if (expectedSize == contentSize) {
            assertEquals(FileTransferResult.Success(contentSize), result)
        } else {
            assertEquals(
                FileTransferResult.ByteMismatch(written = contentSize, expected = expectedSize),
                result,
            )
            assertTrue("a byte-count mismatch must never be Success", result !is FileTransferResult.Success)
        }
    }
}
