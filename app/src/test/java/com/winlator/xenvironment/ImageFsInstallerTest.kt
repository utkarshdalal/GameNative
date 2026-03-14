package com.winlator.xenvironment

import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.core.FileUtils
import java.io.File
import java.lang.reflect.Method
import java.nio.file.Files
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageFsInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val filesDir = context.filesDir
    private val sharedDir = File(filesDir, "imagefs_shared")
    private val imageFsLink = File(filesDir, "imagefs")
    private val glibcDir = File(filesDir, "glibc")
    private val bionicDir = File(filesDir, "bionic")

    @After
    fun tearDown() {
        sharedDir.deleteRecursively()
        if (Files.isSymbolicLink(imageFsLink.toPath()) || imageFsLink.exists()) {
            imageFsLink.delete()
        }
        glibcDir.deleteRecursively()
        bionicDir.deleteRecursively()
    }

    @Test
    fun ensureSharedHomeRoot_callsSymlinkWhenHomeIsNotSymlink() {
        val rootDir = File(filesDir, "imagefs-root-${System.nanoTime()}").apply { mkdirs() }
        val sharedHome = File(sharedDir, "home")
        val imageFsHome = File(rootDir, "home")
        val expectedTarget = sharedHome.path
        val expectedLink = imageFsHome.path

        mockkStatic(FileUtils::class)
        try {
            every { FileUtils.isSymlink(any()) } returns false
            every { FileUtils.symlink(any<String>(), any<String>()) } returns Unit

            invokeEnsureSharedHomeRoot(context, rootDir)

            assertTrue("Shared home should be created", sharedHome.exists())
            verify(exactly = 1) { FileUtils.symlink(expectedTarget, expectedLink) }
        } finally {
            unmockkStatic(FileUtils::class)
        }

        rootDir.deleteRecursively()
    }

    @Test
    fun ensureSharedHomeRoot_doesNotCallSymlinkWhenHomeAlreadySymlink() {
        val rootDir = File(filesDir, "imagefs-root-symlink-${System.nanoTime()}").apply { mkdirs() }
        val sharedHome = File(sharedDir, "home")
        val imageFsHome = File(rootDir, "home")

        mockkStatic(FileUtils::class)
        try {
            every { FileUtils.isSymlink(imageFsHome) } returns true
            every { FileUtils.symlink(any<String>(), any<String>()) } returns Unit

            invokeEnsureSharedHomeRoot(context, rootDir)

            assertTrue("Shared home should still be created", sharedHome.exists())
            verify(exactly = 0) { FileUtils.symlink(any<String>(), any<String>()) }
        } finally {
            unmockkStatic(FileUtils::class)
        }

        rootDir.deleteRecursively()
    }

    @Test
    fun ensureSharedHomeRoot_alwaysCreatesSharedHomeDirectory() {
        val rootDir = File(filesDir, "imagefs-root-shared-home-${System.nanoTime()}").apply { mkdirs() }
        val sharedHome = File(sharedDir, "home")

        invokeEnsureSharedHomeRoot(context, rootDir)

        assertTrue("Shared home should always be created", sharedHome.exists())
        assertTrue("Shared home should be a directory", sharedHome.isDirectory)

        rootDir.deleteRecursively()
    }

    @Test
    fun ensureSharedHomeRoot_usesExpectedLinkAndTargetPaths() {
        val rootDir = File(filesDir, "imagefs-root-paths-${System.nanoTime()}").apply { mkdirs() }
        val sharedHome = File(sharedDir, "home")
        val imageFsHome = File(rootDir, "home")

        mockkStatic(FileUtils::class)
        try {
            every { FileUtils.isSymlink(any()) } returns false
            every { FileUtils.symlink(any<String>(), any<String>()) } returns Unit

            invokeEnsureSharedHomeRoot(context, rootDir)

            verify(exactly = 1) { FileUtils.symlink(sharedHome.path, imageFsHome.path) }
            assertEquals(sharedHome.path, File(sharedDir, "home").path)
        } finally {
            unmockkStatic(FileUtils::class)
        }

        rootDir.deleteRecursively()
    }

    @Test
    fun ensureProtonVersionSymlink_callsSymlinkForActiveProtonVersion() {
        val rootDir = File(filesDir, "imagefs-root-proton-${System.nanoTime()}").apply { mkdirs() }
        val activeProtonVersion = "proton-ge-9-2"
        val sharedProtonTarget = File(sharedDir, "proton/$activeProtonVersion").apply { mkdirs() }
        val expectedLink = File(rootDir, "opt/$activeProtonVersion")

        mockkStatic(FileUtils::class)
        try {
            every { FileUtils.symlink(any<String>(), any<String>()) } returns Unit
            every { FileUtils.delete(any<File>()) } returns true
            every { FileUtils.isSymlink(any<File>()) } returns false

            ImageFsInstaller.ensureProtonVersionSymlink(context, rootDir, activeProtonVersion)

            verify(exactly = 1) {
                FileUtils.symlink(sharedProtonTarget.absolutePath, expectedLink.absolutePath)
            }
        } finally {
            unmockkStatic(FileUtils::class)
        }

        rootDir.deleteRecursively()
    }

    @Test
    fun ensureProtonVersionSymlink_replacesDanglingSymlinkAtActivePath() {
        val rootDir = File(filesDir, "imagefs-root-dangling-proton-${System.nanoTime()}").apply { mkdirs() }
        val activeProtonVersion = "proton-ge-9-3"
        val optDir = File(rootDir, "opt").apply { mkdirs() }
        val optVersionLink = File(optDir, activeProtonVersion)
        val missingTarget = File(rootDir, "missing-proton-target")
        Files.createSymbolicLink(optVersionLink.toPath(), missingTarget.toPath())

        val desiredTarget = File(sharedDir, "proton/$activeProtonVersion").apply { mkdirs() }

        assertFalse("Dangling symlink should report exists=false", optVersionLink.exists())
        assertTrue("Active path should still be a symlink", Files.isSymbolicLink(optVersionLink.toPath()))

        mockkStatic(FileUtils::class)
        try {
            every { FileUtils.delete(optVersionLink) } answers { optVersionLink.delete() }
            every { FileUtils.delete(any<File>()) } answers { firstArg<File>().delete() }
            every { FileUtils.symlink(any<String>(), any<String>()) } answers {
                val linkTarget = firstArg<String>()
                val linkPath = secondArg<String>()
                val linkFile = File(linkPath)
                if (Files.exists(linkFile.toPath()) || Files.isSymbolicLink(linkFile.toPath())) {
                    linkFile.delete()
                }
                Files.createSymbolicLink(linkFile.toPath(), File(linkTarget).toPath())
            }

            ImageFsInstaller.ensureProtonVersionSymlink(context, rootDir, activeProtonVersion)
        } finally {
            unmockkStatic(FileUtils::class)
        }

        assertTrue("Active path should remain a symlink", Files.isSymbolicLink(optVersionLink.toPath()))
        assertEquals(
            desiredTarget.canonicalPath,
            optVersionLink.canonicalFile.absolutePath,
        )

        rootDir.deleteRecursively()
    }

    @Test
    fun ensureImageFsSymlink_createsSymlinkToVariantRoot() {
        val glibcRoot = ImageFs.getVariantRootDir(context, "glibc")
        if (Files.exists(imageFsLink.toPath()) || Files.isSymbolicLink(imageFsLink.toPath())) {
            imageFsLink.deleteRecursively()
        }

        mockkStatic(FileUtils::class)
        try {
            every { FileUtils.symlink(any<String>(), any<String>()) } answers {
                val linkTarget = firstArg<String>()
                val linkPath = secondArg<String>()
                val linkFile = File(linkPath)
                if (Files.exists(linkFile.toPath()) || Files.isSymbolicLink(linkFile.toPath())) {
                    Files.deleteIfExists(linkFile.toPath())
                }
                Files.createSymbolicLink(linkFile.toPath(), File(linkTarget).toPath())
            }

            invokeEnsureImageFsSymlink(context, "glibc")
        } finally {
            unmockkStatic(FileUtils::class)
        }

        assertTrue("imagefs should be a symlink", Files.isSymbolicLink(imageFsLink.toPath()))
        assertEquals(glibcRoot.canonicalPath, imageFsLink.canonicalPath)
    }

    @Test
    fun ensureImageFsSymlink_retargetsExistingSymlink() {
        val glibcRoot = ImageFs.getVariantRootDir(context, "glibc")
        val bionicRoot = ImageFs.getVariantRootDir(context, "bionic")
        if (Files.exists(imageFsLink.toPath()) || Files.isSymbolicLink(imageFsLink.toPath())) {
            imageFsLink.deleteRecursively()
        }
        Files.createSymbolicLink(imageFsLink.toPath(), glibcRoot.toPath())

        mockkStatic(FileUtils::class)
        try {
            every { FileUtils.delete(any<File>()) } answers { firstArg<File>().delete() }
            every { FileUtils.symlink(any<String>(), any<String>()) } answers {
                val linkTarget = firstArg<String>()
                val linkPath = secondArg<String>()
                val linkFile = File(linkPath)
                if (Files.exists(linkFile.toPath()) || Files.isSymbolicLink(linkFile.toPath())) {
                    Files.deleteIfExists(linkFile.toPath())
                }
                Files.createSymbolicLink(linkFile.toPath(), File(linkTarget).toPath())
            }

            invokeEnsureImageFsSymlink(context, "bionic")
        } finally {
            unmockkStatic(FileUtils::class)
        }

        assertTrue("imagefs should remain a symlink", Files.isSymbolicLink(imageFsLink.toPath()))
        assertEquals(bionicRoot.canonicalPath, imageFsLink.canonicalPath)
    }

    @Test
    fun isVariantImageFsValid_returnsFalseWhenVersionFileMissing() {
        ImageFs.getVariantRootDir(context, "glibc")

        val valid = ImageFsInstaller.isVariantImageFsValid(context, "glibc")

        assertFalse(valid)
    }

    @Test
    fun isVariantImageFsValid_returnsFalseWhenVersionIsOutdated() {
        val rootDir = ImageFs.getVariantRootDir(context, "glibc")
        val versionFile = File(rootDir, ".winlator/.img_version").apply {
            parentFile?.mkdirs()
            writeText((ImageFsInstaller.LATEST_VERSION - 1).toString())
        }

        val valid = ImageFsInstaller.isVariantImageFsValid(context, "glibc")

        assertTrue(versionFile.exists())
        assertFalse(valid)
    }

    @Test
    fun isVariantImageFsValid_returnsTrueWhenVersionIsLatest() {
        val rootDir = ImageFs.getVariantRootDir(context, "bionic")
        val versionFile = File(rootDir, ".winlator/.img_version").apply {
            parentFile?.mkdirs()
            writeText(ImageFsInstaller.LATEST_VERSION.toString())
        }

        val valid = ImageFsInstaller.isVariantImageFsValid(context, "bionic")

        assertTrue(versionFile.exists())
        assertTrue(valid)
    }

    @Test
    fun removeCurrentProtonSymlink_removesOnlyNonActiveProtonSymlinks() {
        val optDir = File(filesDir, "imagefs-opt-remove-proton-${System.nanoTime()}").apply { mkdirs() }
        val active = File(optDir, "proton-ge-9-2").apply { mkdirs() }
        val stale = File(optDir, "proton-ge-8-1").apply { mkdirs() }
        val nonProton = File(optDir, "wine-9.0").apply { mkdirs() }

        mockkStatic(FileUtils::class)
        try {
            every { FileUtils.isSymlink(active) } returns false
            every { FileUtils.isSymlink(stale) } returns true
            every { FileUtils.isSymlink(nonProton) } returns false
            every { FileUtils.delete(any<File>()) } returns true

            invokeRemoveCurrentProtonSymlink(optDir, "proton-ge-9-2")

            verify(exactly = 1) { FileUtils.delete(stale) }
            verify(exactly = 0) { FileUtils.delete(active) }
            verify(exactly = 0) { FileUtils.delete(nonProton) }
        } finally {
            unmockkStatic(FileUtils::class)
        }

        optDir.deleteRecursively()
    }

    @Test
    fun ensureImageFsSymlinks_returnsFalseWhenLegacyMigrationFails() {
        val legacyRoot = File(filesDir, "legacy-migration-fail-${System.nanoTime()}").apply { mkdirs() }
        val container = mockk<Container>(relaxed = true)

        mockkStatic(ImageFSLegacyMigrator::class)
        try {
            every { ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(any(), any()) } returns false

            assertFalse(ImageFsInstaller.ensureImageFsSymlinks(context, legacyRoot, container))
        } finally {
            unmockkStatic(ImageFSLegacyMigrator::class)
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun ensureImageFsSymlinks_withGlibcContainer_setsImageFsSymlinkAndEnsuresSharedHome() {
        val legacyRoot = File(filesDir, "legacy-glibc-${System.nanoTime()}").apply { mkdirs() }
        val glibcRoot = ImageFs.getVariantRootDir(context, "glibc").apply { mkdirs() }
        if (Files.exists(imageFsLink.toPath()) || Files.isSymbolicLink(imageFsLink.toPath())) {
            imageFsLink.deleteRecursively()
        }

        val container = mockk<Container>()
        every { container.getWineVersion() } returns "wine-9.0"
        every { container.getContainerVariant() } returns Container.GLIBC

        mockkStatic(ImageFSLegacyMigrator::class)
        mockkStatic(FileUtils::class)
        try {
            every { ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(any(), any()) } returns true
            every { FileUtils.isSymlink(any()) } returns false
            every { FileUtils.symlink(any<String>(), any<String>()) } answers {
                val linkTarget = firstArg<String>()
                val linkPath = secondArg<String>()
                val linkFile = File(linkPath)
                linkFile.parentFile?.mkdirs()
                if (Files.exists(linkFile.toPath()) || Files.isSymbolicLink(linkFile.toPath())) {
                    Files.deleteIfExists(linkFile.toPath())
                }
                Files.createSymbolicLink(linkFile.toPath(), File(linkTarget).toPath())
            }

            assertTrue(ImageFsInstaller.ensureImageFsSymlinks(context, legacyRoot, container))

            assertTrue("imagefs should be a symlink", Files.isSymbolicLink(imageFsLink.toPath()))
            assertEquals(glibcRoot.canonicalPath, imageFsLink.canonicalPath)
            val sharedHome = File(sharedDir, "home")
            assertTrue("Shared home backing dir should exist", sharedHome.exists())
            verify(atLeast = 1) { FileUtils.symlink(sharedHome.path, File(legacyRoot, "home").path) }
            verify(atLeast = 1) { FileUtils.symlink(glibcRoot.absolutePath, imageFsLink.absolutePath) }
        } finally {
            unmockkStatic(FileUtils::class)
            unmockkStatic(ImageFSLegacyMigrator::class)
            legacyRoot.deleteRecursively()
        }
    }

    @Test
    fun ensureImageFsSymlinks_withBionicContainer_alsoCreatesProtonSymlink() {
        val legacyRoot = File(filesDir, "legacy-bionic-${System.nanoTime()}").apply { mkdirs() }
        ImageFs.getVariantRootDir(context, "bionic").apply { mkdirs() }
        val activeProtonVersion = "proton-ge-9-2"
        val sharedProtonTarget = File(sharedDir, "proton/$activeProtonVersion").apply { mkdirs() }
        val expectedLink = File(legacyRoot, "opt/$activeProtonVersion")

        if (Files.exists(imageFsLink.toPath()) || Files.isSymbolicLink(imageFsLink.toPath())) {
            imageFsLink.deleteRecursively()
        }

        val container = mockk<Container>()
        every { container.getWineVersion() } returns activeProtonVersion
        every { container.getContainerVariant() } returns Container.BIONIC

        mockkStatic(ImageFSLegacyMigrator::class)
        mockkStatic(FileUtils::class)
        try {
            every { ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(any(), any()) } returns true
            every { FileUtils.isSymlink(any()) } returns false
            every { FileUtils.delete(any<File>()) } returns true
            every { FileUtils.symlink(any<String>(), any<String>()) } answers {
                val linkTarget = firstArg<String>()
                val linkPath = secondArg<String>()
                val linkFile = File(linkPath)
                linkFile.parentFile?.mkdirs()
                if (Files.exists(linkFile.toPath()) || Files.isSymbolicLink(linkFile.toPath())) {
                    Files.deleteIfExists(linkFile.toPath())
                }
                Files.createSymbolicLink(linkFile.toPath(), File(linkTarget).toPath())
            }

            assertTrue(ImageFsInstaller.ensureImageFsSymlinks(context, legacyRoot, container))

            assertEquals(
                ImageFs.getVariantRootDir(context, "bionic").canonicalPath,
                imageFsLink.canonicalPath,
            )
            assertTrue(
                "Proton opt symlink is under the legacy imagefs root passed to ensureImageFsSymlinks",
                Files.isSymbolicLink(expectedLink.toPath()),
            )
            assertEquals(
                sharedProtonTarget.canonicalPath,
                expectedLink.canonicalFile.absolutePath,
            )
            verify(atLeast = 1) {
                FileUtils.symlink(sharedProtonTarget.absolutePath, expectedLink.absolutePath)
            }
        } finally {
            unmockkStatic(FileUtils::class)
            unmockkStatic(ImageFSLegacyMigrator::class)
            legacyRoot.deleteRecursively()
        }
    }

    private fun invokeEnsureSharedHomeRoot(context: android.content.Context, rootDir: File) {
        val method: Method = ImageFsInstaller::class.java.getDeclaredMethod(
            "ensureSharedHomeRoot",
            android.content.Context::class.java,
            File::class.java,
        )
        method.isAccessible = true
        method.invoke(null, context, rootDir)
    }

    private fun invokeRemoveCurrentProtonSymlink(optDir: File, activeProtonVersion: String) {
        val method: Method = ImageFsInstaller::class.java.getDeclaredMethod(
            "removeCurrentProtonSymlink",
            File::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, optDir, activeProtonVersion)
    }

    private fun invokeEnsureImageFsSymlink(context: android.content.Context, variant: String) {
        val method: Method = ImageFsInstaller::class.java.getDeclaredMethod(
            "ensureImageFsSymlink",
            android.content.Context::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, context, variant)
    }

}
