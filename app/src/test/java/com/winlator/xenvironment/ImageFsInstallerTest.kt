package com.winlator.xenvironment

import androidx.test.core.app.ApplicationProvider
import com.winlator.core.FileUtils
import java.io.File
import java.lang.reflect.Method
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun invokeEnsureSharedHomeRoot(context: android.content.Context, rootDir: File) {
        val method: Method = ImageFsInstaller::class.java.getDeclaredMethod(
            "ensureSharedHomeRoot",
            android.content.Context::class.java,
            File::class.java,
        )
        method.isAccessible = true
        method.invoke(null, context, rootDir)
    }

}
