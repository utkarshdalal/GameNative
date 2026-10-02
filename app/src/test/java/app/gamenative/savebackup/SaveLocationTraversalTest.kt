package app.gamenative.savebackup

import app.gamenative.enums.PathType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Example tests for path-traversal safety in [SaveRoot.normalizeSubpath] (CWE-22).
 *
 * A persisted or auto-discovered subpath must stay within its [PathType] root. `normalizeSubpath`
 * resolves internal `..` segments and rejects any `..` that would escape above the subpath root,
 * closing the hole where a preserved `..` reached `Path.resolve` in [SaveLocationResolver] and could
 * read/write outside the selected save directory.
 */
class SaveLocationTraversalTest {

    @Test
    fun resolvesInternalParentSegments() {
        assertEquals("a/c", SaveRoot.normalizeSubpath("a/b/../c"))
        assertEquals("a", SaveRoot.normalizeSubpath("a/b/.."))
        assertEquals("x/y", SaveRoot.normalizeSubpath("x/./y"))
        assertEquals("", SaveRoot.normalizeSubpath("a/.."))
    }

    @Test
    fun rejectsEscapingParentAtStart() {
        assertThrows(IllegalArgumentException::class.java) {
            SaveRoot.normalizeSubpath("../x")
        }
    }

    @Test
    fun rejectsEscapingParentMidPath() {
        assertThrows(IllegalArgumentException::class.java) {
            SaveRoot.normalizeSubpath("a/../../b")
        }
    }

    @Test
    fun rejectsEscapingParentAfterDrivePrefix() {
        // Drive prefix is stripped first, then the leading '..' escapes the root.
        assertThrows(IllegalArgumentException::class.java) {
            SaveRoot.normalizeSubpath("C:/../../etc")
        }
    }

    @Test
    fun constructorRejectsEscapingSubpath() {
        assertThrows(IllegalArgumentException::class.java) {
            SaveRoot(PathType.WinSavedGames, "../../outside")
        }
    }

    @Test
    fun backslashesTreatedAsSeparatorsForTraversal() {
        // '\' is normalized to '/', so a backslash '..' still escapes and is rejected.
        assertThrows(IllegalArgumentException::class.java) {
            SaveRoot.normalizeSubpath("..\\..\\x")
        }
    }
}
