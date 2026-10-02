package app.gamenative.savebackup

import app.gamenative.enums.PathType
import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/**
 * Property test for Epic/GOG known-folder mapping — game-save-backup Task 19.3.
 *
 * Feature: game-save-backup, Property 18: Epic/GOG known folders map to the most-specific supported
 * root.
 *
 * The Epic/GOG strategies resolve an **absolute** save folder and map it onto a [SaveRoot] via the
 * shared [SaveRootMatcher] (the same longest-prefix matcher the container browser confirm uses).
 * This property drives that matcher directly:
 *
 * - For any absolute folder that lies under one or more supported [PathType] roots, mapping yields
 *   the longest-prefix root plus the remainder as the relative subpath, and root-joined-with-subpath
 *   resolves back to the same absolute folder (Req 15.3).
 * - For any folder under no supported root, mapping yields `null` so the strategy defers to the
 *   browser (Req 15.4).
 *
 * The candidate roots are built on a temporary `drive_c` that mirrors the real nesting
 * ([PathType.toAbsPath]'s `Root` ⊂ `WinMyDocuments`/`WinSavedGames`/`WinAppData*`), so the
 * longest-prefix choice is non-trivial. Robolectric-free; ≥100 iterations per property.
 */
@RunWith(JUnitQuickcheck::class)
class KnownFolderMatcherPropertyTest {

    private val tempDirs = mutableListOf<Path>()

    /** Supported roots to generate "under a known root" folders beneath (excluding SteamUserData). */
    private val mappableRoots: List<PathType> = listOf(
        PathType.WinMyDocuments,
        PathType.WinSavedGames,
        PathType.WinAppDataRoaming,
        PathType.WinAppDataLocal,
        PathType.WinAppDataLocalLow,
        PathType.WinProgramData,
        PathType.Root,
    )

    @After
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    // Feature: game-save-backup, Property 18: a folder under a supported root maps to the
    // longest-prefix root and round-trips back to the same absolute folder.
    @Property(trials = 200)
    fun folderUnderSupportedRootMapsToLongestPrefix(
        rootSelector: Int,
        subSegmentSeed: Int,
        subDepthSelector: Int,
    ) {
        val driveC = newTempDir("known-drivec")
        val candidateRoots = buildCandidateRoots(driveC)

        // Pick a target supported root and generate a folder some levels beneath it.
        val targetType = mappableRoots[Math.floorMod(rootSelector, mappableRoots.size)]
        val targetRoot = candidateRoots.getValue(targetType)
        val depth = Math.floorMod(subDepthSelector, 4) // 0..3 extra segments
        var folder = targetRoot
        for (i in 0 until depth) {
            folder = folder.resolve("seg${Math.floorMod(subSegmentSeed + i, 100)}")
        }
        folder = folder.normalize()

        val matched = SaveRootMatcher.match(candidateRoots, folder)
        assertTrue("a folder under a supported root must map, got null for $folder", matched != null)

        // Longest-prefix: no OTHER candidate root that is also a prefix is strictly longer than the
        // selected one.
        val selectedRoot = candidateRoots.getValue(matched!!.pathType).normalize()
        candidateRoots.forEach { (type, root) ->
            val norm = root.normalize()
            if (type != matched.pathType && folder.startsWith(norm)) {
                assertTrue(
                    "no matching root may be longer than the selected root ($type vs ${matched.pathType})",
                    norm.nameCount <= selectedRoot.nameCount,
                )
            }
        }

        // Round-trip: selected root joined with the returned subpath resolves back to the folder.
        val roundTripped =
            if (matched.relativeSubpath.isEmpty()) selectedRoot
            else selectedRoot.resolve(matched.relativeSubpath)
        assertEquals("root + subpath must resolve back to the folder", folder, roundTripped.normalize())
    }

    // Feature: game-save-backup, Property 18: a folder under NO supported root maps to null so the
    // strategy defers to the browser.
    @Property(trials = 100)
    fun folderOutsideAllRootsMapsToNull(outsideSeed: Int) {
        val driveC = newTempDir("known-drivec-out")
        val candidateRoots = buildCandidateRoots(driveC)

        // A sibling of drive_c that is under none of the candidate roots.
        val outside = driveC.parent.resolve("outside_${Math.floorMod(outsideSeed, 10_000)}/saves").normalize()

        val matched = SaveRootMatcher.match(candidateRoots, outside)
        assertNull("a folder under no supported root must not map", matched)
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Build a candidate-root map on a temp `drive_c` that mirrors the real nesting: `Root` is the
     * users/<user> dir, and the Win* roots are its nested children. This matches the structure
     * [PathType.toAbsPath] produces so the longest-prefix choice is genuinely tested.
     */
    private fun buildCandidateRoots(driveC: Path): Map<PathType, Path> {
        val userDir = driveC.resolve("users/xuser")
        val roots = mapOf(
            PathType.Root to userDir,
            PathType.WinMyDocuments to userDir.resolve("Documents"),
            PathType.WinSavedGames to userDir.resolve("Saved Games"),
            PathType.WinAppDataRoaming to userDir.resolve("AppData/Roaming"),
            PathType.WinAppDataLocal to userDir.resolve("AppData/Local"),
            PathType.WinAppDataLocalLow to userDir.resolve("AppData/LocalLow"),
            PathType.WinProgramData to driveC.resolve("ProgramData"),
        )
        roots.values.forEach { Files.createDirectories(it) }
        return roots.mapValues { it.value.normalize() }
    }

    private fun newTempDir(prefix: String): Path {
        val dir = Files.createTempDirectory(prefix)
        tempDirs.add(dir)
        return dir
    }
}
