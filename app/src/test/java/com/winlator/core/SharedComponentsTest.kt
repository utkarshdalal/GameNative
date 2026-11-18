package com.winlator.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedComponentsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun linkCount(file: File): Int =
        (Files.getAttribute(file.toPath(), "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt()

    private fun sharedTree(): File {
        val shared = tmp.newFolder("shared")
        File(shared, "system32").mkdirs()
        File(shared, "system32/d3d11.dll").writeText("dxvk")
        File(shared, "syswow64").mkdirs()
        File(shared, "syswow64/dxgi.dll").writeText("dxvk32")
        File(shared, ".complete").writeText("marker")
        return shared
    }

    @Test
    fun materialize_copiesPrivateFiles() {
        val shared = sharedTree()
        val dest = tmp.newFolder("dest")

        assertTrue(SharedComponents.materialize(shared, dest, null))

        val copied = File(dest, "system32/d3d11.dll")
        assertEquals("dxvk", copied.readText())
        assertEquals("dxvk32", File(dest, "syswow64/dxgi.dll").readText())
        assertFalse(Files.isSameFile(copied.toPath(), File(shared, "system32/d3d11.dll").toPath()))
        assertEquals(1, linkCount(copied))
        assertEquals(1, linkCount(File(shared, "system32/d3d11.dll")))
        assertTrue(copied.canWrite())
        copied.writeText("overwritten")
        assertEquals("dxvk", File(shared, "system32/d3d11.dll").readText())
        assertFalse(File(dest, ".complete").exists())
    }

    @Test
    fun materialize_replacesAnExistingHardlinkWithACopy() {
        val shared = sharedTree()
        val dest = tmp.newFolder("dest")
        File(dest, "system32").mkdirs()
        val target = File(dest, "system32/d3d11.dll")
        Files.createLink(target.toPath(), File(shared, "system32/d3d11.dll").toPath())

        assertTrue(SharedComponents.materialize(shared, dest, null))

        assertFalse(Files.isSameFile(target.toPath(), File(shared, "system32/d3d11.dll").toPath()))
        assertEquals("dxvk", target.readText())
    }

    @Test
    fun materialize_copiesConfigFilesToo() {
        val shared = sharedTree()
        File(shared, "syswow64/dxvk.conf").writeText("conf")
        val dest = tmp.newFolder("dest")

        assertTrue(SharedComponents.materialize(shared, dest, null))

        assertEquals(1, linkCount(File(dest, "syswow64/dxvk.conf")))
        assertEquals(1, linkCount(File(shared, "syswow64/dxgi.dll")))
    }
}
