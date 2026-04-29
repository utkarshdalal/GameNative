package com.winlator.xenvironment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlibcRuntimePathPatcherTest {
    @Test
    fun patchBytesForPackage_rewritesX11SocketPathToPackageAlias() {
        val oldPath = "/data/data/com.winlator/files/imagefs/usr/tmp/.X11-unix/X"
        val newPath = "/data/data/app.gamenative.debug/imgfs/usr/tmp/.X11-unix/X"
        val bytes = "before\u0000$oldPath\u0000after".toByteArray(Charsets.US_ASCII)

        val patched = GlibcRuntimePathPatcher.patchBytesForPackage(bytes, "app.gamenative.debug")
        val patchedText = bytes.toString(Charsets.US_ASCII)

        assertEquals(1, patched)
        assertFalse(patchedText.contains(oldPath))
        assertTrue(patchedText.contains(newPath))
        assertTrue(patchedText.contains("after"))
    }

    @Test
    fun patchBytesForPackage_doesNotTruncateUnknownPathsThatShareAPrefix() {
        val unknownPath = "/data/data/com.winlator/files/imagefs/usr/lib/not-listed"
        val bytes = "$unknownPath\u0000".toByteArray(Charsets.US_ASCII)

        val patched = GlibcRuntimePathPatcher.patchBytesForPackage(bytes, "app.gamenative.debug")
        val patchedText = bytes.toString(Charsets.US_ASCII)

        assertEquals(0, patched)
        assertTrue(patchedText.contains(unknownPath))
    }

    @Test
    fun patchBytesForPackage_rewritesLibredirectPathsAndIsIdempotent() {
        val oldPath = "/data/data/app.gamenative/files/imagefs/usr/tmp"
        val newPath = "/data/data/app.gamenative.debug/imgfs/usr/tmp"
        val bytes = "$oldPath\u0000".toByteArray(Charsets.US_ASCII)

        assertEquals(1, GlibcRuntimePathPatcher.patchBytesForPackage(bytes, "app.gamenative.debug"))
        assertEquals(0, GlibcRuntimePathPatcher.patchBytesForPackage(bytes, "app.gamenative.debug"))

        val patchedText = bytes.toString(Charsets.US_ASCII)
        assertFalse(patchedText.contains(oldPath))
        assertTrue(patchedText.contains(newPath))
    }

    @Test
    fun patchBytesForPackage_skipsReplacementWhenCapacityIsTooSmall() {
        val oldPath = "/data/data/com.winlator/files/imagefs/usr/tmp/.X11-unix/X"
        val bytes = "$oldPath\u0000".toByteArray(Charsets.US_ASCII)

        val patched = GlibcRuntimePathPatcher.patchBytesForPackage(
            bytes,
            "app.gamenative.debug.with.a.very.long.suffix",
        )

        assertEquals(0, patched)
        assertTrue(bytes.toString(Charsets.US_ASCII).contains(oldPath))
    }
}
