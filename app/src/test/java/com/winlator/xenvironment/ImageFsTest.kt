package com.winlator.xenvironment

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageFsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val sharedDir = File(context.filesDir, "imagefs_shared")
    private val glibcDir = File(context.filesDir, "glibc")
    private val bionicDir = File(context.filesDir, "bionic")

    @After
    fun tearDown() {
        sharedDir.deleteRecursively()
        glibcDir.deleteRecursively()
        bionicDir.deleteRecursively()
    }

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

    @Test
    fun getVariantRootDir_createsAndReturnsVariantDirectory() {
        val actual = ImageFs.getVariantRootDir(context, "glibc")
        val expected = File(context.filesDir, "glibc/imagefs")

        assertTrue("Variant root dir should exist after call", actual.exists())
        assertTrue("Variant root dir should be a directory", actual.isDirectory)
        assertEquals(expected.absolutePath, actual.absolutePath)
    }

    @Test
    fun getVariantRootDir_returnsDistinctPathsPerVariant() {
        val glibcRoot = ImageFs.getVariantRootDir(context, "glibc")
        val bionicRoot = ImageFs.getVariantRootDir(context, "bionic")

        assertEquals(File(context.filesDir, "glibc/imagefs").absolutePath, glibcRoot.absolutePath)
        assertEquals(File(context.filesDir, "bionic/imagefs").absolutePath, bionicRoot.absolutePath)
    }
}
