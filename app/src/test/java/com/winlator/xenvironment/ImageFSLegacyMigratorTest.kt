package com.winlator.xenvironment

import androidx.test.core.app.ApplicationProvider
import java.io.File
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
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
        mockkStatic(ImageFsInstaller::class)
        every { ImageFsInstaller.ensureSharedHomeRoot(any(), any()) } just runs
        every { ImageFsInstaller.ensureProtonVersionSymlink(any(), any(), any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkStatic(ImageFsInstaller::class)
        legacyImageFsRoot.deleteRecursively()
        sharedDir.deleteRecursively()
    }

    private fun assertEnsureCalls(wineVersion: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        verify(exactly = 1) { ImageFsInstaller.ensureSharedHomeRoot(context, legacyImageFsRoot) }
        verify(exactly = 1) {
            ImageFsInstaller.ensureProtonVersionSymlink(context, legacyImageFsRoot, wineVersion)
        }
    }

    @Test
    fun migrateLegacyDirsIfNeeded_returnsTrueWhenLegacyHomeMissing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot, "")

        assertTrue(migrated)
        assertEnsureCalls("")
    }

    @Test
    fun migrateLegacyDirsIfNeeded_movesLegacyHomeToSharedHome() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacyHome = File(legacyImageFsRoot, "home").apply { mkdirs() }
        val legacyFile = File(legacyHome, "marker.txt").apply { writeText("legacy-content") }

        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot, "")

        assertTrue(migrated)
        assertEnsureCalls("")
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

        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot, "")

        assertTrue(migrated)
        assertEnsureCalls("")
        assertFalse(File(sharedHome, "old.txt").exists())
        assertEquals("new-legacy", File(sharedHome, "new.txt").readText())
    }

    @Test
    fun migrateLegacyDirsIfNeeded_callsEnsureMethodsWhenNoLegacyHomeExists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot, "")

        assertTrue(migrated)
        assertEnsureCalls("")
    }

    @Test
    fun migrateLegacyDirsIfNeeded_movesLegacyProtonDirsToSharedProton() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val protonDir = File(legacyImageFsRoot, "opt/proton-ge-9-2").apply { mkdirs() }
        val protonFile = File(protonDir, "version.txt").apply { writeText("ge-9-2") }

        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot, "proton-ge-9-2")

        val sharedProtonDir = File(sharedDir, "proton/proton-ge-9-2")
        assertTrue(migrated)
        assertEnsureCalls("proton-ge-9-2")
        assertFalse("Legacy proton dir should be moved away", protonDir.exists())
        assertTrue("Shared proton dir should exist", sharedProtonDir.exists())
        assertEquals("ge-9-2", File(sharedProtonDir, protonFile.name).readText())
    }

    @Test
    fun migrateLegacyDirsIfNeeded_skipsNonProtonAndSymlinkEntriesInOpt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val optDir = File(legacyImageFsRoot, "opt").apply { mkdirs() }
        File(optDir, "wine-ge-custom").apply { mkdirs() }

        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot, "")

        assertTrue(migrated)
        assertEnsureCalls("")
        assertTrue(File(optDir, "wine-ge-custom").exists())
    }

    @Test
    fun migrateLegacyDirsIfNeeded_removesLegacyProtonDirWhenSharedAlreadyExists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacyProton = File(legacyImageFsRoot, "opt/proton-ge-9-5").apply { mkdirs() }
        File(legacyProton, "from-legacy.txt").writeText("legacy")

        val existingShared = File(sharedDir, "proton/proton-ge-9-5").apply { mkdirs() }
        File(existingShared, "existing.txt").writeText("shared")

        val migrated = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, legacyImageFsRoot, "")

        assertTrue(migrated)
        assertEnsureCalls("")
        assertFalse("Legacy proton should be removed when shared exists", legacyProton.exists())
        assertEquals("shared", File(existingShared, "existing.txt").readText())
    }
}
