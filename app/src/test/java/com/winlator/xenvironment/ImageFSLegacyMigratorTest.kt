package com.winlator.xenvironment

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageFSLegacyMigratorTest {
    private lateinit var filesDir: File
    private lateinit var legacyImageFsRoot: File
    private lateinit var sharedDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        filesDir = context.filesDir
        legacyImageFsRoot = File(filesDir, "legacy-imagefs-test-${System.nanoTime()}").apply { mkdirs() }
        sharedDir = File(filesDir, "imagefs_shared").apply { deleteRecursively() }
    }

    @After
    fun tearDown() {
        legacyImageFsRoot.deleteRecursively()
        sharedDir.deleteRecursively()
    }

    @Test
    fun migrateLegacyDirsIfNeeded_returnsTrueWhenLegacyHomeMissing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot)

        assertTrue(migrated)
        assertFalse(File(legacyImageFsRoot, "home").exists())
    }

    @Test
    fun migrateLegacyDirsIfNeeded_movesLegacyHomeToSharedHome() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacyHome = File(legacyImageFsRoot, "home").apply { mkdirs() }
        val legacyFile = File(legacyHome, "marker.txt").apply { writeText("legacy-content") }

        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot)

        assertTrue(migrated)
        assertFalse("Legacy home should have been moved away", legacyHome.exists())
        val sharedHome = File(sharedDir, "home")
        assertTrue(sharedHome.exists())
        assertEquals("legacy-content", File(sharedHome, legacyFile.name).readText())
    }

    @Test
    fun migrateLegacyDirsIfNeeded_overwritesExistingSharedHomeWithLegacyHome() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sharedHome = File(sharedDir, "home").apply { mkdirs() }
        File(sharedHome, "old.txt").writeText("old-shared")

        val legacyHome = File(legacyImageFsRoot, "home").apply { mkdirs() }
        File(legacyHome, "new.txt").writeText("new-legacy")

        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot)

        assertTrue(migrated)
        assertFalse(File(sharedHome, "old.txt").exists())
        assertEquals("new-legacy", File(sharedHome, "new.txt").readText())
    }

    @Test
    fun migrateLegacyDirsIfNeeded_noopWhenLegacyHomeIsSymlink() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val realHome = File(legacyImageFsRoot, "real-home").apply { mkdirs() }
        val symlinkPath = File(legacyImageFsRoot, "home").toPath()
        Files.createSymbolicLink(symlinkPath, realHome.toPath())

        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot)

        assertTrue(migrated)
        assertTrue("Symlink should remain untouched", Files.isSymbolicLink(symlinkPath))
    }
}
