package app.gamenative.html5.shim

import android.content.Context
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// the @JavascriptInterface log() entry point defers to a coroutine, which would add test
// ceremony without buying much; appendLog is the logic that matters.
@RunWith(RobolectricTestRunner::class)
class Html5DiagnosticBridgeTest {

    private lateinit var context: Context
    private lateinit var bridge: Html5DiagnosticBridge

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        bridge = Html5DiagnosticBridge(context)
        File(context.filesDir, "html5-logs").deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "html5-logs").deleteRecursively()
    }

    @Test
    fun appendLog_writesToCorrectPath() {
        val f = bridge.appendLog("container-abc", """{"ts":1,"api":"localStorage"}""")
        val expected = File(context.filesDir, "html5-logs/container-abc/save-trace.jsonl")
        assertEquals(expected.absolutePath, f.absolutePath)
        assertTrue("expected jsonl file to exist", f.exists())
        assertTrue(
            "expected single newline-terminated line",
            f.readText().endsWith("\n") && f.readText().count { it == '\n' } == 1,
        )
    }

    @Test
    fun appendLog_rotatesOver10MB() {
        val dir = File(context.filesDir, "html5-logs/rot-container")
        dir.mkdirs()
        val f = File(dir, "save-trace.jsonl")
        val seed = ByteArray(10_000_001) { 'x'.code.toByte() }
        f.writeBytes(seed)
        assertTrue("seed failed", f.length() > 10_000_000L)

        bridge.appendLog("rot-container", """{"rotated":true}""")

        val old = File(dir, "save-trace.jsonl.old")
        assertTrue("expected .old to exist", old.exists())
        assertEquals("rotated file should carry the seed size", 10_000_001L, old.length())
        // fresh .jsonl should contain only the new line.
        assertTrue("expected fresh jsonl", f.exists())
        assertTrue("fresh jsonl should be small", f.length() < 200L)
    }

    @Test
    fun appendLog_second_rotation_replaces_old() {
        val dir = File(context.filesDir, "html5-logs/rot2-container")
        dir.mkdirs()
        // an existing .old exercises the delete-and-replace path.
        File(dir, "save-trace.jsonl.old").writeText("STALE_OLD")
        val f = File(dir, "save-trace.jsonl")
        f.writeBytes(ByteArray(10_000_001) { 'x'.code.toByte() })

        bridge.appendLog("rot2-container", """{"r":true}""")

        val old = File(dir, "save-trace.jsonl.old")
        assertFalse("stale .old must have been deleted before rename", old.readText() == "STALE_OLD")
        assertEquals(10_000_001L, old.length())
    }

    @Test
    fun log_skippedWhenNoSlug() {
        // no attach() -- log() returns before touching the filesystem.
        bridge.log("""{"ignored":true}""")
        val rootDir = File(context.filesDir, "html5-logs")
        assertFalse("no container attached — no log dir should exist", rootDir.exists() && rootDir.listFiles()?.isNotEmpty() == true)
    }
}
