package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilsPatternTest {

    private fun dirWith(vararg fileNames: String): Path {
        val dir = Files.createTempDirectory("fileutils-pattern")
        fileNames.forEach { Files.createFile(dir.resolve(it)) }
        return dir
    }

    private fun matches(dir: Path, pattern: String): List<String> =
        FileUtils.findFiles(dir, pattern).map { it.name }.toList().sorted()

    private fun matchesRecursive(dir: Path, pattern: String): List<String> =
        FileUtils.findFilesRecursive(dir, pattern, recursive = true).map { it.name }.toList().sorted()

    @Test
    fun questionMarkStandsForOneCharacter() {
        val dir = dirWith("FFXII_000", "FFXII_001", "FFXII_00", "GameSetting.ini")

        assertEquals(listOf("FFXII_000", "FFXII_001"), matches(dir, "FFXII_???"))
        assertEquals(listOf("FFXII_000", "FFXII_001"), matchesRecursive(dir, "FFXII_???"))
    }

    @Test
    fun matchingRequiresTheWholeName() {
        val dir = dirWith("FFXII_0000", "save.sav.bak", "old_save.sav")

        assertEquals(emptyList<String>(), matches(dir, "FFXII_???"))
        assertEquals(listOf("old_save.sav"), matches(dir, "*.sav"))
        assertEquals(emptyList<String>(), matches(dir, "save.sav"))
    }

    @Test
    fun starDotStarMatchesNamesWithoutADot() {
        val dir = dirWith("README", "save.sav", ".hidden")

        assertEquals(listOf(".hidden", "README", "save.sav"), matches(dir, "*.*"))
        assertEquals(listOf(".hidden", "README", "save.sav"), matches(dir, "*"))
    }

    @Test
    fun bracketClassesFollowFnmatch() {
        val dir = dirWith("save1.dat", "Save2.dat", "xave3.dat", "save.dat", "slot_a", "slot_b", "slot_7", "a]b", "a-b")

        assertEquals(listOf("Save2.dat", "save1.dat"), matches(dir, "[sS]ave?.dat"))
        assertEquals(listOf("slot_a", "slot_b"), matches(dir, "slot_[a-z]"))
        assertEquals(listOf("slot_7"), matches(dir, "slot_[!a-z]"))
        assertEquals(listOf("slot_7"), matches(dir, "slot_[^a-z]"))
        assertEquals(listOf("a]b"), matches(dir, "a[]]b"))
        assertEquals(listOf("a-b"), matches(dir, "a[x-]b"))
    }

    @Test
    fun unclosedBracketIsLiteral() {
        val dir = dirWith("a[b", "ab")

        assertEquals(listOf("a[b"), matches(dir, "a[b"))
    }

    @Test
    fun matchesUfsPatternWorksOnNames() {
        assertTrue(FileUtils.matchesUfsPattern("Profile1.SAV", "profile?.sav"))
        assertTrue(FileUtils.matchesUfsPattern("noext", "*.*"))
        assertFalse(FileUtils.matchesUfsPattern("save.sav.bak", "*.sav"))
        assertFalse(FileUtils.matchesUfsPattern("save.sav", "save.sa"))
        assertTrue(FileUtils.matchesUfsPattern("a+b(1).sav", "a+b(?).sav"))
        assertFalse(FileUtils.matchesUfsPattern("aab(1).sav", "a+b(?).sav"))
    }

    @Test
    fun nonRecursiveScanIgnoresSubdirectories() {
        val dir = dirWith("top.sav")
        val nested = Files.createDirectory(dir.resolve("nested.sav"))
        Files.createFile(nested.resolve("deep.sav"))

        assertEquals(listOf("top.sav"), matches(dir, "*.sav"))
        assertEquals(listOf("top.sav"), FileUtils.findFilesRecursive(dir, "*.sav", recursive = false).map { it.name }.toList())
        assertEquals(listOf("deep.sav", "top.sav"), matchesRecursive(dir, "*.sav"))
    }

    @Test
    fun recursiveScanHasNoDepthLimit() {
        val dir = dirWith()
        var current = dir
        repeat(8) { current = Files.createDirectory(current.resolve("d$it")) }
        Files.createFile(current.resolve("deep.sav"))

        assertEquals(listOf("deep.sav"), matchesRecursive(dir, "*.sav"))
    }

    @Test
    fun steamAutocloudVdfIsSkipped() {
        listOf("steam_autocloud.vdf", "Steam_AutoCloud.VDF").forEach { name ->
            val dir = dirWith(name, "save.vdf")

            assertEquals(listOf("save.vdf"), matches(dir, "*.vdf"))
            assertEquals(listOf("save.vdf"), matchesRecursive(dir, "*"))
        }
    }

    @Test
    fun filesWithRelativePathLongerThan199CharactersAreSkipped() {
        val dir = dirWith()
        val nested = Files.createDirectory(dir.resolve("a".repeat(100)))
        Files.createFile(nested.resolve("b".repeat(97) + ".s"))
        Files.createFile(nested.resolve("c".repeat(96) + ".s"))

        assertEquals(listOf("c".repeat(96) + ".s"), matchesRecursive(dir, "*.s"))
    }

    @Test
    fun symlinkedDirectoriesAreNotFollowed() {
        val target = dirWith("linked.sav")
        val dir = dirWith("own.sav")
        Files.createSymbolicLink(dir.resolve("link"), target)

        assertEquals(listOf("own.sav"), matchesRecursive(dir, "*.sav"))
    }

    @Test
    fun resultsAreSortedByNameWithinEachDirectory() {
        val dir = dirWith("b.sav", "a.sav")
        val nested = Files.createDirectory(dir.resolve("0sub"))
        Files.createFile(nested.resolve("z.sav"))
        Files.createFile(nested.resolve("y.sav"))

        assertEquals(
            listOf("a.sav", "b.sav", "y.sav", "z.sav"),
            FileUtils.findFilesRecursive(dir, "*.sav", recursive = true).map { it.name }.toList(),
        )
    }

    @Test
    fun starStillMatchesAnyRun() {
        val dir = dirWith("save.sav", "Profile1.sav", "Profile22.sav", "notes.txt")

        assertEquals(listOf("Profile1.sav", "Profile22.sav", "save.sav"), matches(dir, "*.sav"))
        assertEquals(listOf("Profile1.sav", "Profile22.sav"), matches(dir, "Profile*.sav"))
        assertEquals(listOf("Profile1.sav", "Profile22.sav", "notes.txt", "save.sav").sorted(), matches(dir, "*"))
    }

    @Test
    fun literalPartsAreNotTreatedAsRegex() {
        val dir = dirWith("save.sav", "saveXsav")

        assertEquals(listOf("save.sav"), matches(dir, "save.sav"))
    }

    @Test
    fun matchingIgnoresCase() {
        val dir = dirWith("FFXII_000")

        assertEquals(listOf("FFXII_000"), matches(dir, "ffxii_???"))
    }

    @Test
    fun recursiveMatchFindsNestedFiles() {
        val dir = dirWith("FFXII_000")
        val nested = Files.createDirectory(dir.resolve("backup"))
        Files.createFile(nested.resolve("FFXII_007"))

        assertEquals(listOf("FFXII_000", "FFXII_007"), matchesRecursive(dir, "FFXII_???"))
    }
}
