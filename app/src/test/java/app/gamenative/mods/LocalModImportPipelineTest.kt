package app.gamenative.mods

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModImportPipelineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun requireUsableArchive_rejectsEmptyAndDirectoryOnlyArchives() {
        assertThrows(InvalidLocalModArchiveException::class.java) {
            LocalModImportPipeline.requireUsableArchive(emptyList(), TEST_FAILURE_MESSAGE)
        }
        assertThrows(InvalidLocalModArchiveException::class.java) {
            LocalModImportPipeline.requireUsableArchive(
                listOf(ModArchiveEntry("Data", directory = true, sizeBytes = 0L)),
                TEST_FAILURE_MESSAGE,
            )
        }

        LocalModImportPipeline.requireUsableArchive(
            listOf(ModArchiveEntry("Data/plugin.dll", directory = false, sizeBytes = 1L)),
            TEST_FAILURE_MESSAGE,
        )
    }

    @Test
    fun finalizeExtractedContent_promotesNewContentAndRemovesBackup() = runBlocking {
        val parent = temporaryFolder.newFolder("successful-promotion")
        val source = parent.resolve("install.tmp").apply { mkdirs() }
        source.resolve("new.txt").writeText("new")
        val target = parent.resolve("install").apply { mkdirs() }
        target.resolve("old.txt").writeText("old")

        LocalModImportPipeline.finalizeExtractedContent(
            source,
            target,
            TEST_FAILURE_MESSAGE,
        ) { Unit }

        assertEquals("new", target.resolve("new.txt").readText())
        assertFalse(target.resolve("old.txt").exists())
        assertFalse(parent.resolve("install.previous").exists())
    }

    @Test
    fun finalizeExtractedContent_restoresPreviousContentWhenPromotionFails() {
        val parent = temporaryFolder.newFolder("failed-promotion")
        val missingSource = parent.resolve("missing.tmp")
        val target = parent.resolve("install").apply { mkdirs() }
        target.resolve("old.txt").writeText("old")

        assertThrows(IOException::class.java) {
            runBlocking {
                LocalModImportPipeline.finalizeExtractedContent(
                    missingSource,
                    target,
                    TEST_FAILURE_MESSAGE,
                ) { Unit }
            }
        }

        assertTrue(target.isDirectory)
        assertEquals("old", target.resolve("old.txt").readText())
        assertFalse(parent.resolve("install.previous").exists())
    }

    @Test
    fun finalizeExtractedContent_recoversRollbackAfterInterruptedPromotion() {
        val parent = temporaryFolder.newFolder("interrupted-promotion")
        val missingSource = parent.resolve("missing.tmp")
        val target = parent.resolve("install").apply { mkdirs() }
        target.resolve("partial-new.txt").writeText("partial")
        val backup = parent.resolve("install.previous").apply { mkdirs() }
        backup.resolve("old.txt").writeText("old")

        assertThrows(IOException::class.java) {
            runBlocking {
                LocalModImportPipeline.finalizeExtractedContent(
                    missingSource,
                    target,
                    TEST_FAILURE_MESSAGE,
                ) { Unit }
            }
        }

        assertEquals("old", target.resolve("old.txt").readText())
        assertFalse(target.resolve("partial-new.txt").exists())
        assertFalse(backup.exists())
    }

    @Test
    fun finalizeExtractedContent_restoresPreviousContentWhenRegistrationFails() {
        val parent = temporaryFolder.newFolder("failed-registration")
        val source = parent.resolve("install.tmp").apply { mkdirs() }
        source.resolve("new.txt").writeText("new")
        val target = parent.resolve("install").apply { mkdirs() }
        target.resolve("old.txt").writeText("old")

        assertThrows(IOException::class.java) {
            runBlocking {
                LocalModImportPipeline.finalizeExtractedContent(
                    source,
                    target,
                    TEST_FAILURE_MESSAGE,
                ) {
                    throw IOException("Database registration failed")
                }
            }
        }

        assertEquals("old", target.resolve("old.txt").readText())
        assertFalse(target.resolve("new.txt").exists())
        assertFalse(parent.resolve("install.previous").exists())
    }

    @Test
    fun finalizeExtractedContent_rollsBackWhenCancellationWinsBeforeRegistration() {
        val parent = temporaryFolder.newFolder("canceled-registration")
        val source = parent.resolve("install.tmp").apply { mkdirs() }
        source.resolve("new.txt").writeText("new")
        val target = parent.resolve("install").apply { mkdirs() }
        target.resolve("old.txt").writeText("old")

        assertThrows(ModImportCanceledException::class.java) {
            runBlocking {
                LocalModImportPipeline.finalizeExtractedContent(
                    source,
                    target,
                    TEST_FAILURE_MESSAGE,
                ) {
                    throw ModImportCanceledException("Import canceled")
                }
            }
        }

        assertEquals("old", target.resolve("old.txt").readText())
        assertFalse(target.resolve("new.txt").exists())
        assertFalse(parent.resolve("install.previous").exists())
    }

    private companion object {
        const val TEST_FAILURE_MESSAGE = "Test finalization failed"
    }
}
