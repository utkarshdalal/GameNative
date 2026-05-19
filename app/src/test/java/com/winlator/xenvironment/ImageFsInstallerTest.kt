package com.winlator.xenvironment

import androidx.test.core.app.ApplicationProvider
import com.winlator.core.FileUtils
import java.io.File
import java.lang.reflect.Method
import java.nio.file.Files
import io.mockk.every
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

    @After
    fun tearDown() {
        sharedDir.deleteRecursively()
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

}
