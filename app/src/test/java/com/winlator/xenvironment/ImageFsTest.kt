package com.winlator.xenvironment

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageFsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val sharedDir = File(context.filesDir, "imagefs_shared")

    @get:Rule
    val tmp = TemporaryFolder()

    private fun imageFs(): ImageFs = ImageFs.find(tmp.root)
    private fun configDir(): File = File(tmp.root, ".winlator")

    @After
    fun tearDown() {
        sharedDir.deleteRecursively()
    }

    // ── shared dir ────────────────────────────────────────────────────────────

    @Test
    fun getImageFsSharedDir_createsAndReturnsSharedDirectory() {
        val actual = ImageFs.getImageFsSharedDir(context)
        val expected = File(context.filesDir, "imagefs_shared")

        assertTrue("Shared dir should exist after call", actual.exists())
        assertTrue("Shared dir should be a directory", actual.isDirectory)
        assertEquals(expected.absolutePath, actual.absolutePath)
    }

    @Test
    fun getSharedProtonDir_createsAndReturnsSharedProtonDirectory() {
        val actual = ImageFs.getSharedProtonDir(context)
        val expected = File(context.filesDir, "imagefs_shared/proton")

        assertTrue("Shared proton dir should exist after call", actual.exists())
        assertTrue("Shared proton dir should be a directory", actual.isDirectory)
        assertEquals(expected.absolutePath, actual.absolutePath)
    }

    @Test
    fun getSharedProtonDir_isUnderSharedRoot() {
        val sharedRoot = ImageFs.getImageFsSharedDir(context)
        val sharedProton = ImageFs.getSharedProtonDir(context)
        val expected = File(sharedRoot, "proton")

        assertEquals(expected.absolutePath, sharedProton.absolutePath)
    }

    // ── getVariant ────────────────────────────────────────────────────────────

    @Test
    fun `getVariant returns empty string when file is absent`() {
        assertEquals("", imageFs().variant)
    }

    @Test
    fun `getVariant returns empty string when file exists but is empty`() {
        configDir().also { it.mkdirs() }
        File(configDir(), ".variant").createNewFile()

        assertEquals("", imageFs().variant)
    }

    @Test
    fun `getVariant returns empty string when file contains only whitespace`() {
        configDir().also { it.mkdirs() }
        File(configDir(), ".variant").writeText("   ")

        assertEquals("", imageFs().variant)
    }

    @Test
    fun `getVariant returns stored value`() {
        imageFs().createVariantFile("glibc")
        assertEquals("glibc", imageFs().variant)
    }

    // ── getVersion ────────────────────────────────────────────────────────────

    @Test
    fun `getVersion returns 0 when file is absent`() {
        assertEquals(0, imageFs().version)
    }

    @Test
    fun `getVersion returns 0 when file exists but is empty`() {
        configDir().also { it.mkdirs() }
        File(configDir(), ".img_version").createNewFile()

        assertEquals(0, imageFs().version)
    }

    @Test
    fun `getVersion returns 0 when file contains only whitespace`() {
        configDir().also { it.mkdirs() }
        File(configDir(), ".img_version").writeText("   ")

        assertEquals(0, imageFs().version)
    }

    @Test
    fun `getVersion returns 0 when file contains non-numeric content`() {
        configDir().also { it.mkdirs() }
        File(configDir(), ".img_version").writeText("corrupted")

        assertEquals(0, imageFs().version)
    }

    @Test
    fun `getVersion returns stored value`() {
        imageFs().createImgVersionFile(26)
        assertEquals(26, imageFs().version)
    }
}
