package com.winlator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileUtilsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ── readFirstLine ─────────────────────────────────────────────────────────

    @Test
    fun `readFirstLine returns null when file does not exist`() {
        val missing = tmp.root.resolve("missing.txt")
        assertNull(FileUtils.readFirstLine(missing))
    }

    @Test
    fun `readFirstLine returns null when file is empty`() {
        val file = tmp.newFile("empty.txt")
        assertNull(FileUtils.readFirstLine(file))
    }

    @Test
    fun `readFirstLine returns the single line`() {
        val file = tmp.newFile("single.txt").also { it.writeText("hello") }
        assertEquals("hello", FileUtils.readFirstLine(file))
    }

    @Test
    fun `readFirstLine returns only the first line of a multi-line file`() {
        val file = tmp.newFile("multi.txt").also { it.writeText("first\nsecond\nthird") }
        assertEquals("first", FileUtils.readFirstLine(file))
    }

    @Test
    fun `readFirstLine preserves leading and trailing whitespace`() {
        val file = tmp.newFile("padded.txt").also { it.writeText("  42  \nsecond") }
        assertEquals("  42  ", FileUtils.readFirstLine(file))
    }
}
