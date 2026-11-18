package app.gamenative.service.gog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class GOGManifestUtilsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeInfo(dir: File, gameId: String, rootGameId: String): File =
        dir.resolve("goggame-$gameId.info").apply {
            writeText("""{"gameId": "$gameId", "rootGameId": "$rootGameId"}""")
        }

    @Test
    fun infoFileProductIds_listsEveryInfoFileInRoot() {
        val dir = tmp.newFolder("install")
        writeInfo(dir, "100", "100")
        writeInfo(dir, "200", "100")
        dir.resolve("goggame-200.hashdb").createNewFile()
        dir.resolve("goggame-galaxyFileList.ini").createNewFile()
        dir.resolve("sub").mkdirs()
        writeInfo(dir.resolve("sub"), "300", "100")

        assertEquals(setOf("100", "200"), GOGManifestUtils.infoFileProductIds(dir))
    }

    @Test
    fun infoFileProductIds_missingDirIsEmpty() {
        assertEquals(emptySet<String>(), GOGManifestUtils.infoFileProductIds(File(tmp.root, "missing")))
    }

    @Test
    fun selectBaseInfoFile_prefersKnownGameId() {
        val dir = tmp.newFolder("known")
        val other = writeInfo(dir, "050", "050")
        val base = writeInfo(dir, "100", "999")

        assertEquals(base, GOGManifestUtils.selectBaseInfoFile(listOf(other, base), "100"))
    }

    @Test
    fun selectBaseInfoFile_knownGameIdMissingNeverPicksDlc() {
        val dir = tmp.newFolder("missing-known")
        val dlc = writeInfo(dir, "050", "100")
        val base = writeInfo(dir, "100", "100")

        assertEquals(base, GOGManifestUtils.selectBaseInfoFile(listOf(dlc, base), "777"))
        assertNull(GOGManifestUtils.selectBaseInfoFile(listOf(dlc), "777"))
    }

    @Test
    fun selectBaseInfoFile_knownGameIdMissingSkipsUnreadableFiles() {
        val dir = tmp.newFolder("unreadable")
        val broken = dir.resolve("goggame-010.info").apply { writeText("not json") }
        val base = dir.resolve("goggame-100.info").apply { writeText("""{"gameId": "100"}""") }

        assertEquals(base, GOGManifestUtils.selectBaseInfoFile(listOf(base, broken), "777"))
        assertNull(GOGManifestUtils.selectBaseInfoFile(listOf(broken), "777"))
    }

    @Test
    fun selectBaseInfoFile_withoutGameIdPicksRootGame() {
        val dir = tmp.newFolder("unknown")
        val dlc = writeInfo(dir, "050", "100")
        val base = writeInfo(dir, "100", "100")

        assertEquals(base, GOGManifestUtils.selectBaseInfoFile(listOf(dlc, base), null))
    }

    @Test
    fun selectBaseInfoFile_fallsBackToFirstByName() {
        val dir = tmp.newFolder("fallback")
        val first = writeInfo(dir, "050", "999")
        val second = writeInfo(dir, "060", "999")

        assertEquals(first, GOGManifestUtils.selectBaseInfoFile(listOf(second, first), null))
        assertNull(GOGManifestUtils.selectBaseInfoFile(emptyList(), "100"))
    }

    private fun recordOldBuild(dir: File) =
        GOGManifestUtils.writeRecordedFiles(dir, "old", 2, "en-US", setOf("100", "200"), listOf("bin/game.exe", "bin/old.dll", "dlc/200.pak"))

    @Test
    fun filesToRemove_sameLanguageAndProductSupersetListsDroppedFiles() {
        val dir = tmp.newFolder("superset")
        recordOldBuild(dir)

        assertEquals(
            listOf("bin/old.dll"),
            GOGManifestUtils.filesToRemove(dir, "old", 2, "en-US", setOf("100", "200", "300"), listOf("bin/game.exe", "dlc/200.pak", "dlc/300.pak")),
        )
    }

    @Test
    fun filesToRemove_languageChangedRemovesNothing() {
        val dir = tmp.newFolder("language")
        recordOldBuild(dir)

        assertNull(GOGManifestUtils.filesToRemove(dir, "old", 2, "de-DE", setOf("100", "200"), listOf("bin/game.exe")))
    }

    @Test
    fun filesToRemove_recordedDlcMissingFromThisRunRemovesNothing() {
        val dir = tmp.newFolder("dlc-missing")
        recordOldBuild(dir)

        assertNull(GOGManifestUtils.filesToRemove(dir, "old", 2, "en-US", setOf("100"), listOf("bin/game.exe")))
    }

    @Test
    fun filesToRemove_generationChangedRemovesNothing() {
        val dir = tmp.newFolder("generation")
        recordOldBuild(dir)

        assertNull(GOGManifestUtils.filesToRemove(dir, "old", 1, "en-US", setOf("100", "200"), listOf("bin/game.exe")))
    }

    @Test
    fun filesToRemove_listWithoutCoverageFieldsRemovesNothing() {
        val dir = tmp.newFolder("legacy-list")
        dir.resolve(GOGManifestUtils.FILE_LIST_FILE_NAME).writeText("""{"buildId": "old", "files": ["bin/game.exe", "bin/old.dll"]}""")

        assertNull(GOGManifestUtils.filesToRemove(dir, "old", 2, "en-US", setOf("100"), listOf("bin/game.exe")))
        assertEquals(listOf("bin/game.exe", "bin/old.dll"), GOGManifestUtils.readRecordedFiles(dir, "old"))
    }
}
