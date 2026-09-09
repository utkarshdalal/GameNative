package app.gamenative.savebackup

import app.gamenative.enums.PathType
import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.generator.InRange
import com.pholser.junit.quickcheck.generator.Size
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/**
 * Property test for [ContainerBrowserConfirm.map] — game-save-backup Task 8.3 (Property 5).
 *
 * Feature: game-save-backup, Property 5: ContainerBrowser confirm round-trips through the
 * most-specific PathType.
 *
 * For any directory that lies within one or more supported [PathType] roots under `drive_c`,
 * confirming that directory selects the **longest (most specific)** matching root and yields a
 * [SaveLocation] whose `PathType` root plus relative subpath resolves back to the exact same
 * absolute directory. Because the supported roots nest (`Root` ⊂ `WinMyDocuments` /
 * `WinSavedGames` / `WinAppData*`), the selected root is uniquely determined by the longest-prefix
 * rule. For any directory not under any supported root, confirm is rejected and yields no
 * [SaveLocation] (Requirement 3.8, 3.9).
 *
 * The test drives the **pure** [ContainerBrowserConfirm.map] with a candidate-roots map built on a
 * temporary `drive_c`, faithfully mirroring the real nesting produced by [PathType.toAbsPath]:
 *
 *   Root               = driveC/users/xuser
 *   WinMyDocuments     = driveC/users/xuser/Documents
 *   WinSavedGames      = driveC/users/xuser/Saved Games
 *   WinAppDataRoaming  = driveC/users/xuser/AppData/Roaming
 *   WinAppDataLocal    = driveC/users/xuser/AppData/Local
 *   WinAppDataLocalLow = driveC/users/xuser/AppData/LocalLow
 *   WinProgramData     = driveC/ProgramData          (sibling of users)
 *
 * `Root` is a genuine path prefix of the Documents / Saved Games / AppData roots, so a directory
 * generated "under Root" may in fact fall under a deeper root — the longest-prefix rule must then
 * pick the deeper root, never `Root`. The test computes the expected root independently (the
 * longest candidate that is a segment-aware prefix of the confirmed dir) rather than assuming it is
 * the root it generated from, which exercises the nesting correctness directly.
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration, no Robolectric): [map] is pure path math, so no [com.winlator.container.Container]
 * is needed. Every `@Property` runs a minimum of 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class ContainerBrowserConfirmPropertyTest {

    private val tempRoots = mutableListOf<Path>()

    @After
    fun tearDown() {
        tempRoots.forEach { deleteRecursively(it) }
        tempRoots.clear()
    }

    // Feature: game-save-backup, Property 5: ContainerBrowser confirm round-trips through the
    // most-specific PathType — a directory under one/more supported roots maps to the LONGEST
    // matching root and that root joined with the returned relative subpath resolves back to the
    // exact same absolute directory.
    @Property(trials = 200)
    fun confirmUnderSupportedRootRoundTripsThroughMostSpecificRoot(
        @Size(min = 0, max = 5) segments: List<
            @InRange(minInt = 0, maxInt = Int.MAX_VALUE)
            Int,
            >,
        rootSelector: Int,
    ) {
        val driveC = newTempDir("cb-confirm-drivec")
        val candidateRoots = buildCandidateRoots(driveC)

        // Pick an arbitrary supported root to generate a directory under.
        val supported = candidateRoots.keys.toList()
        val generatedFrom = supported[Math.floorMod(rootSelector, supported.size)]
        val generatedRoot = candidateRoots.getValue(generatedFrom)

        // Build a safe relative subpath from constrained, filesystem-legal segments. Segments never
        // contain separators or traversal, so the confirmed dir genuinely lives under generatedRoot.
        val subSegments = segments.map { safeSegment(it) }
        val confirmedDir = subSegments.fold(generatedRoot) { acc, seg -> acc.resolve(seg) }.normalize()

        val result = ContainerBrowserConfirm.map(candidateRoots, confirmedDir)

        assertTrue(
            "Expected Selected for a directory under supported root $generatedFrom, got $result",
            result is ConfirmResult.Selected,
        )
        val selected = (result as ConfirmResult.Selected).saveLocation

        // Independently compute the expected longest-prefix root: among all candidate roots that
        // are a segment-aware prefix of the confirmed dir, the one with the longest path. Ties are
        // impossible (two roots both prefixing the same path are themselves nested).
        val expectedPathType = expectedLongestPrefixRoot(candidateRoots, confirmedDir)
        assertEquals(
            "confirm must select the longest (most specific) matching root",
            expectedPathType,
            selected.pathType,
        )

        // Longest-prefix invariant: no OTHER candidate root is both a prefix of the confirmed dir
        // and strictly longer than the selected root.
        val selectedRoot = candidateRoots.getValue(selected.pathType).normalize()
        candidateRoots.forEach { (pathType, root) ->
            val norm = root.normalize()
            if (pathType != selected.pathType && confirmedDir.startsWith(norm)) {
                assertTrue(
                    "no matching root may be longer than the selected root " +
                        "($pathType len=${norm.nameCount} vs selected len=${selectedRoot.nameCount})",
                    norm.nameCount <= selectedRoot.nameCount,
                )
            }
        }

        // Round-trip: selectedRoot joined with the returned relative subpath resolves back to the
        // exact same absolute directory (Req 3.8).
        val roundTripped =
            if (selected.relativeSubpath.isEmpty()) {
                selectedRoot
            } else {
                selectedRoot.resolve(selected.relativeSubpath)
            }.normalize()
        assertEquals(
            "root + relative subpath must resolve back to the confirmed directory",
            confirmedDir,
            roundTripped,
        )

        // The returned subpath is a pure relative path (no leading separator, no drive prefix).
        assertFalse(selected.relativeSubpath.startsWith("/"))
        assertFalse(selected.relativeSubpath.startsWith("\\"))
    }

    // Feature: game-save-backup, Property 5: nesting correctness — when a directory generated under
    // `Root` also falls under a deeper root (e.g. its subpath starts with "Documents/..."), the
    // longest-prefix rule must select the deeper root (WinMyDocuments), never `Root`.
    @Property(trials = 200)
    fun confirmUnderRootButAlsoUnderDeeperRootPicksDeeperRoot(
        @Size(min = 0, max = 4) tailSegments: List<
            @InRange(minInt = 0, maxInt = Int.MAX_VALUE)
            Int,
            >,
        deeperSelector: Int,
    ) {
        val driveC = newTempDir("cb-nesting-drivec")
        val candidateRoots = buildCandidateRoots(driveC)
        val rootPath = candidateRoots.getValue(PathType.Root).normalize()

        // Deeper roots that nest under Root (i.e. Root is a strict prefix of them).
        val deeperRoots = candidateRoots
            .filterKeys { it != PathType.Root && it != PathType.WinProgramData }
            .filterValues { it.normalize().startsWith(rootPath) && it.normalize().nameCount > rootPath.nameCount }
        val deeperEntries = deeperRoots.entries.toList()
        val (deeperType, deeperRoot) = deeperEntries[Math.floorMod(deeperSelector, deeperEntries.size)]

        // A directory that is under the deeper root (and therefore also under Root).
        val confirmedDir = tailSegments
            .map { safeSegment(it) }
            .fold(deeperRoot.normalize()) { acc, seg -> acc.resolve(seg) }
            .normalize()

        val result = ContainerBrowserConfirm.map(candidateRoots, confirmedDir)
        assertTrue("Expected Selected, got $result", result is ConfirmResult.Selected)
        val selected = (result as ConfirmResult.Selected).saveLocation

        // The longest-prefix rule must pick the deeper root, not Root — even though the directory is
        // legitimately under Root as well.
        assertEquals(
            "longest-prefix must pick the deeper nested root, not Root",
            deeperType,
            selected.pathType,
        )
        assertTrue(
            "selecting Root here would violate the most-specific rule",
            selected.pathType != PathType.Root,
        )

        // Round-trip back to the same absolute directory.
        val selectedRoot = candidateRoots.getValue(selected.pathType).normalize()
        val roundTripped =
            if (selected.relativeSubpath.isEmpty()) selectedRoot else selectedRoot.resolve(selected.relativeSubpath)
        assertEquals(confirmedDir, roundTripped.normalize())
    }

    // Feature: game-save-backup, Property 5: a directory not under ANY supported root is rejected
    // and yields no SaveLocation (Req 3.9).
    @Property(trials = 200)
    fun confirmOutsideAllSupportedRootsIsRejected(
        @Size(min = 0, max = 4) segments: List<
            @InRange(minInt = 0, maxInt = Int.MAX_VALUE)
            Int,
            >,
        unmatchedSelector: Int,
    ) {
        val driveC = newTempDir("cb-reject-drivec")
        val candidateRoots = buildCandidateRoots(driveC)

        // Bases genuinely under NO candidate root:
        //  - driveC/Program Files/...          (listable but no PathType covers it)
        //  - driveC/windows/...                 (sibling of users, not covered)
        //  - driveC/users/otheruser/...         (sibling of users/xuser -> not under Root)
        //  - driveC/...                         (directly under drive_c, above users)
        val unmatchedBases = listOf(
            driveC.resolve("Program Files"),
            driveC.resolve("windows"),
            driveC.resolve("users").resolve("otheruser"),
            driveC,
        )
        val base = unmatchedBases[Math.floorMod(unmatchedSelector, unmatchedBases.size)]

        val confirmedDir = segments
            .map { safeSegment(it) }
            .fold(base.normalize()) { acc, seg -> acc.resolve(seg) }
            .normalize()

        // Sanity: the generated dir really is under none of the candidate roots. (When base is
        // driveC itself with zero segments it equals driveC, which is above every root.)
        val underAnyRoot = candidateRoots.values.any { confirmedDir.startsWith(it.normalize()) }

        val result = ContainerBrowserConfirm.map(candidateRoots, confirmedDir)

        if (underAnyRoot) {
            // Defensive: if a segment somehow reconstructed a supported root, it must be Selected —
            // never a false rejection. In practice safeSegment avoids the reserved names so this
            // branch is not hit, but the assertion keeps the property honest.
            assertTrue(result is ConfirmResult.Selected)
        } else {
            assertTrue(
                "Expected Rejected for a directory under no supported root, got $result",
                result is ConfirmResult.Rejected,
            )
            assertEquals(
                ContainerBrowserConfirm.NOT_SUPPORTED_ROOT_REASON,
                (result as ConfirmResult.Rejected).reason,
            )
            // No SaveLocation is produced (isConfirmable agrees).
            assertNull(
                "a rejected directory yields no SaveLocation",
                (result as? ConfirmResult.Selected)?.saveLocation,
            )
            assertFalse(ContainerBrowserConfirm.isConfirmable(candidateRoots, confirmedDir))
        }
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Build a set of NESTED candidate roots on [driveC] mirroring the real [PathType.toAbsPath]
     * layout, and create the directories on disk (map() is pure path math and does not require
     * them, but creating them keeps the fixture faithful and self-documenting).
     */
    private fun buildCandidateRoots(driveC: Path): Map<PathType, Path> {
        val userHome = driveC.resolve("users").resolve("xuser")
        val roots = linkedMapOf(
            PathType.Root to userHome,
            PathType.WinMyDocuments to userHome.resolve("Documents"),
            PathType.WinSavedGames to userHome.resolve("Saved Games"),
            PathType.WinAppDataRoaming to userHome.resolve("AppData").resolve("Roaming"),
            PathType.WinAppDataLocal to userHome.resolve("AppData").resolve("Local"),
            PathType.WinAppDataLocalLow to userHome.resolve("AppData").resolve("LocalLow"),
            PathType.WinProgramData to driveC.resolve("ProgramData"),
        )
        roots.values.forEach { Files.createDirectories(it) }
        return roots.mapValues { it.value.normalize() }
    }

    /**
     * Independently compute the expected longest-prefix root: among all candidate roots that are a
     * segment-aware prefix of [dir], the one with the longest path.
     */
    private fun expectedLongestPrefixRoot(candidateRoots: Map<PathType, Path>, dir: Path): PathType =
        candidateRoots.entries
            .filter { dir.startsWith(it.value.normalize()) }
            .maxByOrNull { it.value.normalize().nameCount }!!
            .key

    /**
     * A filesystem-legal, separator-free, traversal-free path segment derived from an arbitrary
     * int. Never one of the reserved root folder names, so a subpath built from these can never
     * accidentally reconstruct a deeper supported root when appended to a shallower one.
     */
    private fun safeSegment(seed: Int): String = "seg" + Math.floorMod(seed, 100_000)

    private fun newTempDir(prefix: String): Path {
        val dir: Path = Files.createTempDirectory(prefix)
        tempRoots.add(dir)
        return dir
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path: Path -> Files.deleteIfExists(path) }
        }
    }
}
