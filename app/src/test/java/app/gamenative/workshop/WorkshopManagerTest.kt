package app.gamenative.workshop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class WorkshopManagerTest {

    private lateinit var tempDir: File
    private lateinit var workshopContentDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("workshop_test").toFile()
        workshopContentDir = File(tempDir, "content")
        workshopContentDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private fun makeItem(
        id: Long,
        timeUpdated: Long = 1000L,
        fileUrl: String = "https://example.com/file.bin",
        manifestId: Long = 0L,
        title: String = "Item $id",
    ) = WorkshopItem(
        publishedFileId = id,
        appId = 480,
        title = title,
        fileSizeBytes = 1024,
        manifestId = manifestId,
        timeUpdated = timeUpdated,
        fileUrl = fileUrl,
    )

    private fun markComplete(itemId: Long, timestamp: Long = 1000L) {
        val dir = File(workshopContentDir, itemId.toString()).apply { mkdirs() }
        File(dir, ".workshop_complete").writeText(timestamp.toString())
    }

    private fun addContent(itemId: Long, fileName: String = "data.bin") {
        val dir = File(workshopContentDir, itemId.toString()).apply { mkdirs() }
        File(dir, fileName).writeText("content")
    }

    // ── parseEnabledIds ─────────────────────────────────────────────────

    @Test
    fun parseEnabledIds_nullInput_returnsEmptySet() {
        assertEquals(emptySet<Long>(), WorkshopManager.parseEnabledIds(null))
    }

    @Test
    fun parseEnabledIds_emptyString_returnsEmptySet() {
        assertEquals(emptySet<Long>(), WorkshopManager.parseEnabledIds(""))
    }

    @Test
    fun parseEnabledIds_singleId_returnsSetWithOneElement() {
        assertEquals(setOf(12345L), WorkshopManager.parseEnabledIds("12345"))
    }

    @Test
    fun parseEnabledIds_multipleIds_returnsCorrectSet() {
        assertEquals(
            setOf(100L, 200L, 300L),
            WorkshopManager.parseEnabledIds("100,200,300"),
        )
    }

    @Test
    fun parseEnabledIds_whitespace_isTrimmed() {
        assertEquals(
            setOf(100L, 200L, 300L),
            WorkshopManager.parseEnabledIds(" 100 , 200 , 300 "),
        )
    }

    @Test
    fun parseEnabledIds_malformedEntries_areSkipped() {
        assertEquals(
            setOf(100L, 300L),
            WorkshopManager.parseEnabledIds("100,abc,300,"),
        )
    }

    // ── cleanupUnsubscribedItems ────────────────────────────────────────

    @Test
    fun cleanup_removesUnsubscribedDirs() {
        addContent(111)
        addContent(222)
        addContent(333)

        val subscribed = listOf(makeItem(111), makeItem(333))
        WorkshopManager.cleanupUnsubscribedItems(subscribed, workshopContentDir)

        assertTrue(File(workshopContentDir, "111").exists())
        assertFalse(File(workshopContentDir, "222").exists())
        assertTrue(File(workshopContentDir, "333").exists())
    }

    @Test
    fun cleanup_removesPartialSiblingOfUnsubscribed() {
        addContent(111)
        File(workshopContentDir, "111.partial").mkdirs()
        addContent(222)

        val subscribed = listOf(makeItem(222))
        WorkshopManager.cleanupUnsubscribedItems(subscribed, workshopContentDir)

        assertFalse(File(workshopContentDir, "111").exists())
        assertFalse(File(workshopContentDir, "111.partial").exists())
        assertTrue(File(workshopContentDir, "222").exists())
    }

    @Test
    fun cleanup_ignoresNonnumericDirs() {
        addContent(111)
        File(workshopContentDir, "metadata").mkdirs()

        WorkshopManager.cleanupUnsubscribedItems(emptyList(), workshopContentDir)

        // Non-numeric dirs are never touched
        assertTrue(File(workshopContentDir, "metadata").exists())
        // Numeric dir removed because it's not subscribed
        assertFalse(File(workshopContentDir, "111").exists())
    }

    @Test
    fun cleanup_nonexistentDir_doesNotThrow() {
        val missingDir = File(tempDir, "does_not_exist")
        // Should return without error
        WorkshopManager.cleanupUnsubscribedItems(emptyList(), missingDir)
    }

    // ── getItemsNeedingSync ─────────────────────────────────────────────

    @Test
    fun sync_itemWithNoMarker_needsSync() {
        addContent(111)
        val items = listOf(makeItem(111))

        val result = WorkshopManager.getItemsNeedingSync(items, workshopContentDir)

        assertEquals(listOf(makeItem(111)), result)
    }

    @Test
    fun sync_itemWithMarker_isUpToDate() {
        addContent(111)
        markComplete(111, timestamp = 1000L)
        val items = listOf(makeItem(111, timeUpdated = 1000L))

        val result = WorkshopManager.getItemsNeedingSync(items, workshopContentDir)

        assertTrue(result.isEmpty())
    }

    @Test
    fun sync_itemWithStaleMarker_needsSync() {
        addContent(111)
        markComplete(111, timestamp = 1000L)
        val items = listOf(makeItem(111, timeUpdated = 2000L))

        val result = WorkshopManager.getItemsNeedingSync(items, workshopContentDir)

        assertEquals(1, result.size)
        assertEquals(111L, result[0].publishedFileId)
    }

    @Test
    fun sync_unavailableItem_isSkipped() {
        val items = listOf(makeItem(111, fileUrl = "", manifestId = 0L))

        val result = WorkshopManager.getItemsNeedingSync(items, workshopContentDir)

        assertTrue(result.isEmpty())
    }

    @Test
    fun sync_manifestOnlyItem_needsSync() {
        val items = listOf(makeItem(111, fileUrl = "", manifestId = 999L))

        val result = WorkshopManager.getItemsNeedingSync(items, workshopContentDir)

        assertEquals(1, result.size)
    }

    @Test
    fun sync_partialWithMarker_cleansUpAndIsUpToDate() {
        addContent(111)
        markComplete(111, timestamp = 1000L)
        File(workshopContentDir, "111.partial").mkdirs()
        // When both marker and .partial exist, code cleans up .partial
        // and considers the item up-to-date (no re-download needed).
        val items = listOf(makeItem(111, timeUpdated = 1000L))

        val result = WorkshopManager.getItemsNeedingSync(items, workshopContentDir)

        assertTrue(result.isEmpty())
        assertFalse(File(workshopContentDir, "111.partial").exists())
    }

    @Test
    fun sync_partialDirWithoutMarker_needsSync() {
        File(workshopContentDir, "111.partial").mkdirs()
        val items = listOf(makeItem(111))

        val result = WorkshopManager.getItemsNeedingSync(items, workshopContentDir)

        assertEquals(1, result.size)
    }

    @Test
    fun sync_nonexistentContentDir_returnsAllDownloadable() {
        val missingDir = File(tempDir, "does_not_exist")
        val items = listOf(makeItem(111), makeItem(222))

        val result = WorkshopManager.getItemsNeedingSync(items, missingDir)

        assertEquals(2, result.size)
    }

    // ── updateMarkerTimestamps ──────────────────────────────────────────

    @Test
    fun updateMarker_writesTimestampWhenDifferent() {
        addContent(111)
        markComplete(111, timestamp = 1000L)
        val items = listOf(makeItem(111, timeUpdated = 2000L))

        WorkshopManager.updateMarkerTimestamps(items, workshopContentDir)

        val marker = File(File(workshopContentDir, "111"), ".workshop_complete")
        assertEquals("2000", marker.readText().trim())
    }

    @Test
    fun updateMarker_skipsWhenAlreadyCurrent() {
        addContent(111)
        markComplete(111, timestamp = 1000L)
        val items = listOf(makeItem(111, timeUpdated = 1000L))

        WorkshopManager.updateMarkerTimestamps(items, workshopContentDir)

        val marker = File(File(workshopContentDir, "111"), ".workshop_complete")
        assertEquals("1000", marker.readText().trim())
    }

    @Test
    fun updateMarker_skipsMissingDir() {
        // No directory created for item 111
        val items = listOf(makeItem(111, timeUpdated = 2000L))

        // Should not throw
        WorkshopManager.updateMarkerTimestamps(items, workshopContentDir)

        assertFalse(File(workshopContentDir, "111").exists())
    }

    @Test
    fun updateMarker_skipsZeroTimestamp() {
        addContent(111)
        markComplete(111, timestamp = 1000L)
        val items = listOf(makeItem(111, timeUpdated = 0L))

        WorkshopManager.updateMarkerTimestamps(items, workshopContentDir)

        // Should remain unchanged
        val marker = File(File(workshopContentDir, "111"), ".workshop_complete")
        assertEquals("1000", marker.readText().trim())
    }

    @Test
    fun updateMarker_writesWhenMarkerHasNoTimestamp() {
        val itemDir = File(workshopContentDir, "111").apply { mkdirs() }
        File(itemDir, ".workshop_complete").writeText("")
        val items = listOf(makeItem(111, timeUpdated = 5000L))

        WorkshopManager.updateMarkerTimestamps(items, workshopContentDir)

        assertEquals("5000", File(itemDir, ".workshop_complete").readText().trim())
    }

    // ── configureModSymlinks: deselected mod cleanup ────────────────────

    @Test
    fun configureSymlinks_removesDeselectedMods() {
        // Create 3 mod dirs with actual content
        addContent(111)
        addContent(222)
        addContent(333)

        // Only items 111 and 333 are "enabled"
        val items = listOf(makeItem(111), makeItem(333))

        // gameRootDir just needs to exist; the method checks workshopContentDir
        val gameRootDir = File(tempDir, "game").apply { mkdirs() }
        WorkshopManager.configureModSymlinks(
            gameRootDir = gameRootDir,
            workshopContentDir = workshopContentDir,
            items = items,
        )

        assertTrue(File(workshopContentDir, "111").exists())
        assertFalse(File(workshopContentDir, "222").exists())
        assertTrue(File(workshopContentDir, "333").exists())
    }

    @Test
    fun configureSymlinks_removesPartialOfDeselected() {
        addContent(111)
        File(workshopContentDir, "111.partial").mkdirs()

        val gameRootDir = File(tempDir, "game").apply { mkdirs() }
        WorkshopManager.configureModSymlinks(
            gameRootDir = gameRootDir,
            workshopContentDir = workshopContentDir,
            items = listOf(makeItem(222)),
        )

        assertFalse(File(workshopContentDir, "111").exists())
        assertFalse(File(workshopContentDir, "111.partial").exists())
    }

    @Test
    fun configureSymlinks_noItems_skipsDeselectionCleanup() {
        addContent(111)

        val gameRootDir = File(tempDir, "game").apply { mkdirs() }
        WorkshopManager.configureModSymlinks(
            gameRootDir = gameRootDir,
            workshopContentDir = workshopContentDir,
            items = emptyList(),
        )

        // Empty items list → enabledIdSet is null → no deletion
        assertTrue(File(workshopContentDir, "111").exists())
    }

    @Test
    fun configureSymlinks_nonexistentContentDir_doesNotThrow() {
        val missingDir = File(tempDir, "missing")
        val gameRootDir = File(tempDir, "game").apply { mkdirs() }

        WorkshopManager.configureModSymlinks(
            gameRootDir = gameRootDir,
            workshopContentDir = missingDir,
            items = listOf(makeItem(111)),
        )
    }

    // ── getWorkshopContentDir ───────────────────────────────────────────

    @Test
    fun getWorkshopContentDir_buildsCorrectPath() {
        val dir = WorkshopManager.getWorkshopContentDir("/home/xuser/.wine", 480)
        val expected = File(
            "/home/xuser/.wine",
            "drive_c/Program Files (x86)/Steam/steamapps/workshop/content/480",
        )
        assertEquals(expected, dir)
    }

    // ── fixItemFileNames ────────────────────────────────────────────────

    @Test
    fun fixFileNames_renamesMisnamedFile() {
        val dir = File(workshopContentDir, "111").apply { mkdirs() }
        File(dir, "ugc_111.bin").writeText("data")
        val items = listOf(makeItem(111).copy(fileName = "my_mod.vpk"))

        WorkshopManager.fixItemFileNames(items, workshopContentDir)

        assertFalse(File(dir, "ugc_111.bin").exists())
        assertTrue(File(dir, "my_mod.vpk").exists())
    }

    @Test
    fun fixFileNames_skipsWhenCorrectNameExists() {
        val dir = File(workshopContentDir, "111").apply { mkdirs() }
        File(dir, "my_mod.vpk").writeText("data")
        val items = listOf(makeItem(111).copy(fileName = "my_mod.vpk"))

        WorkshopManager.fixItemFileNames(items, workshopContentDir)

        assertTrue(File(dir, "my_mod.vpk").exists())
    }

    @Test
    fun fixFileNames_skipsEmptyFileName() {
        val dir = File(workshopContentDir, "111").apply { mkdirs() }
        File(dir, "ugc_111.bin").writeText("data")
        val items = listOf(makeItem(111).copy(fileName = ""))

        WorkshopManager.fixItemFileNames(items, workshopContentDir)

        // File should remain untouched
        assertTrue(File(dir, "ugc_111.bin").exists())
    }

    @Test
    fun fixFileNames_skipsWhenMultipleContentFiles() {
        val dir = File(workshopContentDir, "111").apply { mkdirs() }
        File(dir, "file_a.bin").writeText("data")
        File(dir, "file_b.bin").writeText("data")
        val items = listOf(makeItem(111).copy(fileName = "my_mod.vpk"))

        WorkshopManager.fixItemFileNames(items, workshopContentDir)

        // Neither file should be renamed because there are multiple content files
        assertTrue(File(dir, "file_a.bin").exists())
        assertTrue(File(dir, "file_b.bin").exists())
        assertFalse(File(dir, "my_mod.vpk").exists())
    }

    @Test
    fun fixFileNames_ignoresDotfiles() {
        val dir = File(workshopContentDir, "111").apply { mkdirs() }
        File(dir, ".workshop_complete").writeText("1000")
        File(dir, "ugc_111.bin").writeText("data")
        val items = listOf(makeItem(111).copy(fileName = "my_mod.vpk"))

        WorkshopManager.fixItemFileNames(items, workshopContentDir)

        // .workshop_complete should not count as a content file
        assertTrue(File(dir, "my_mod.vpk").exists())
        assertTrue(File(dir, ".workshop_complete").exists())
    }

    @Test
    fun fixFileNames_ckmSkipsExtractedProducts() {
        val dir = File(workshopContentDir, "111").apply { mkdirs() }
        // After CKM extraction, an .esp file remains — don't rename it back to .ckm
        File(dir, "dragonborn.esp").writeText("TES4 data")
        val items = listOf(makeItem(111).copy(fileName = "dragonborn.ckm"))

        WorkshopManager.fixItemFileNames(items, workshopContentDir)

        // Should NOT rename .esp back to .ckm
        assertTrue(File(dir, "dragonborn.esp").exists())
        assertFalse(File(dir, "dragonborn.ckm").exists())
    }

    @Test
    fun fixFileNames_stripsPathFromFileName() {
        val dir = File(workshopContentDir, "111").apply { mkdirs() }
        File(dir, "ugc_111.bin").writeText("data")
        // Steam sometimes includes a path prefix in fileName
        val items = listOf(makeItem(111).copy(fileName = "mods/my_mod.vpk"))

        WorkshopManager.fixItemFileNames(items, workshopContentDir)

        assertTrue(File(dir, "my_mod.vpk").exists())
    }
}
