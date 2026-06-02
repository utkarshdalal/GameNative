package app.gamenative.html5.shim

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// pure-jvm. @JavascriptInterface is classpath-metadata-only on JVM precedent).
// base64 branches are NOT exercised here (android.util.Base64 is not on pure-JVM classpath);
// those are covered by on-device SMOKE. all sandbox + path semantics + utf8
// encoding round-trip exercised here.
class Html5FsBridgeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun bridge(): Html5FsBridge =
        Html5FsBridge(containerId = "STEAM_test", sandboxRoot = tempFolder.root)

    // ---------------- absolute-path reject ----------------

    @Test
    fun withinSandbox_rejectsUnixAbsolute() {
        val b = bridge()
        assertNull(b.withinSandbox("/etc/passwd"))
    }

    @Test
    fun withinSandbox_rejectsWindowsAbsolute() {
        val b = bridge()
        assertNull(b.withinSandbox("C:/Windows/System32/cmd.exe"))
        assertNull(b.withinSandbox("C:\\Windows\\System32\\cmd.exe"))
    }

    @Test
    fun withinSandbox_rejectsBackslashAbsolute() {
        val b = bridge()
        assertNull(b.withinSandbox("\\share\\secret"))
    }

    @Test
    fun withinSandbox_rejectsEmpty() {
        val b = bridge()
        assertNull(b.withinSandbox(""))
    }

    // ---------------- layer 1: `..` pre-normalize reject ----------------

    @Test
    fun withinSandbox_rejectsDotDotSegment() {
        val b = bridge()
        assertNull(b.withinSandbox("../escape"))
        assertNull(b.withinSandbox("save/../../etc"))
        assertNull(b.withinSandbox("foo/bar/../../.."))
        // backslash-delimited windows-style traversal also rejected pre-normalize
        assertNull(b.withinSandbox("save\\..\\..\\etc"))
    }

    @Test
    fun withinSandbox_acceptsPlainRelative() {
        val b = bridge()
        val f = b.withinSandbox("save/file.rpgsave")
        assertNotNull(f)
        assertTrue(f!!.canonicalPath.startsWith(tempFolder.root.canonicalPath))
    }

    @Test
    fun withinSandbox_acceptsSingleDotAndEmptySegments() {
        val b = bridge()
        // `./a.txt` is fine — single `.` is not `..`; double-slash / trailing empty segments too.
        assertNotNull(b.withinSandbox("./a.txt"))
        assertNotNull(b.withinSandbox("a//b.txt"))
    }

    // ---------------- layer 2: canonical-path escape ----------------

    @Test
    fun withinSandbox_rejectsCanonicalEscapeViaSymlink() {
        // skip on filesystems/OS that don't support symlink creation via java.nio
        val outsideDir = File(tempFolder.root.parentFile, "outside-${System.nanoTime()}").apply { mkdirs() }
        try {
            val linkPath = java.nio.file.Paths.get(tempFolder.root.absolutePath, "escape-link")
            java.nio.file.Files.createSymbolicLink(linkPath, outsideDir.toPath())
            // layer 1 doesn't see ".." — symlink is a plain segment. layer 2 must catch it.
            val b = bridge()
            assertNull(b.withinSandbox("escape-link/secret.txt"))
        } catch (_: UnsupportedOperationException) {
            // OS/FS doesn't support symlinks — symlink-escape vector not present in this env.
        } catch (_: java.nio.file.FileSystemException) {
            // windows without SeCreateSymbolicLinkPrivilege — same fallout.
        } finally {
            outsideDir.deleteRecursively()
        }
    }

    // ---------------- writeFile + readFile utf8 roundtrip ----------------

    @Test
    fun writeFile_utf8_roundTrip() {
        val b = bridge()
        assertTrue(b.writeFile("save/file1.rpgsave", "hello world", "utf8"))
        assertEquals("hello world", b.readFile("save/file1.rpgsave", "utf8"))
    }

    @Test
    fun writeFile_utf8_createsParentDirs() {
        val b = bridge()
        assertTrue(b.writeFile("a/b/c/d.txt", "nested", "utf8"))
        assertTrue(File(tempFolder.root, "a/b/c/d.txt").exists())
    }

    @Test
    fun writeFile_unknownEncoding_returnsFalse() {
        val b = bridge()
        assertFalse(b.writeFile("x.txt", "blah", "utf-16"))
    }

    @Test
    fun writeFile_absolutePath_returnsFalse() {
        val b = bridge()
        assertFalse(b.writeFile("/etc/passwd", "x", "utf8"))
    }

    @Test
    fun writeFile_dotDotPath_returnsFalse() {
        val b = bridge()
        assertFalse(b.writeFile("../escape.txt", "x", "utf8"))
    }

    @Test
    fun readFile_missing_returnsNull() {
        val b = bridge()
        assertNull(b.readFile("does/not/exist.txt", "utf8"))
    }

    // ---------------- fixture-based roundtrip (from html5-saves/save/ committed in ) ----------------

    @Test
    fun writeFile_readFile_realRpgsaveFixture_utf8_roundTrip() {
        val fixtureBytes = readFixtureBytes("html5-saves/save/file1.rpgsave")
        val b = bridge()
        // .rpgsave is base64+LZString JSON as a string — safe to treat as utf8 for this round-trip.
        val original = String(fixtureBytes, Charsets.UTF_8)
        assertTrue(b.writeFile("save/file1.rpgsave", original, "utf8"))
        assertEquals(original, b.readFile("save/file1.rpgsave", "utf8"))
    }

    // ---------------- exists / unlink ----------------

    @Test
    fun exists_matchesFileState() {
        val b = bridge()
        assertFalse(b.exists("x.txt"))
        b.writeFile("x.txt", "hi", "utf8")
        assertTrue(b.exists("x.txt"))
    }

    @Test
    fun unlink_removesFile() {
        val b = bridge()
        b.writeFile("x.txt", "hi", "utf8")
        assertTrue(b.unlink("x.txt"))
        assertFalse(b.exists("x.txt"))
    }

    @Test
    fun unlink_missing_returnsFalse() {
        assertFalse(bridge().unlink("never.txt"))
    }

    // ---------------- stat ----------------

    @Test
    fun stat_existingFile_returnsCorrectShape() {
        val b = bridge()
        b.writeFile("s.txt", "abcde", "utf8")
        val json = JSONObject(b.stat("s.txt"))
        assertEquals(5L, json.getLong("size"))
        assertTrue(json.getBoolean("isFile"))
        assertFalse(json.getBoolean("isDirectory"))
        assertTrue(json.has("mtimeMs"))
    }

    @Test
    fun stat_missing_returnsEnoentError() {
        val json = JSONObject(bridge().stat("nope.txt"))
        assertEquals("ENOENT", json.getString("error"))
    }

    @Test
    fun stat_noLeakedPath_noLeakedSandboxRoot() {
        // stat JSON MUST NOT include filesystem path or sandboxRoot fields.
        val b = bridge()
        b.writeFile("s.txt", "x", "utf8")
        val json = JSONObject(b.stat("s.txt"))
        assertFalse(json.has("path"))
        assertFalse(json.has("absolutePath"))
        assertFalse(json.has("sandboxRoot"))
        assertFalse(json.has("canonical"))
    }

    // ---------------- mkdir ----------------

    @Test
    fun mkdir_recursive_createsNestedTree() {
        val b = bridge()
        assertTrue(b.mkdir("deep/nested/path", true))
        assertTrue(File(tempFolder.root, "deep/nested/path").isDirectory)
    }

    @Test
    fun mkdir_nonRecursive_createsMissingParents() {
        // bridge emulates the populated windows tree: even a non-recursive mkdir creates missing
        // parents. nw.js Storage.preparePaths mkdirSync's deep AppData paths without {recursive},
        // and our sandboxed windows tree (unlike real windows) doesn't pre-seed AppData. see
        // Html5FsBridge.mkdir.
        val b = bridge()
        assertTrue(b.mkdir("lone/deep", false))
        assertTrue(File(tempFolder.root, "lone/deep").isDirectory)
    }

    @Test
    fun mkdir_existingDir_returnsTrue() {
        val b = bridge()
        b.mkdir("d", false)
        assertTrue(b.mkdir("d", false))
    }

    // ---------------- readdir ----------------

    @Test
    fun readdir_returnsJsonArrayOfNames() {
        val b = bridge()
        b.writeFile("dir/a.txt", "a", "utf8")
        b.writeFile("dir/b.txt", "b", "utf8")
        b.mkdir("dir/sub", false)
        val arr = JSONArray(b.readdir("dir"))
        val names = (0 until arr.length()).map { arr.getString(it) }.toSet()
        assertEquals(setOf("a.txt", "b.txt", "sub"), names)
    }

    @Test
    fun readdir_missing_returnsEmptyArray() {
        assertEquals("[]", bridge().readdir("nope"))
    }

    // ---------------- rename ----------------

    @Test
    fun rename_movesFile() {
        val b = bridge()
        b.writeFile("old.txt", "content", "utf8")
        assertTrue(b.rename("old.txt", "new.txt"))
        assertFalse(b.exists("old.txt"))
        assertTrue(b.exists("new.txt"))
    }

    @Test
    fun rename_createsParentDir() {
        val b = bridge()
        b.writeFile("a.txt", "x", "utf8")
        assertTrue(b.rename("a.txt", "into/new/b.txt"))
        assertTrue(b.exists("into/new/b.txt"))
    }

    // ---------------- appendFile ----------------

    @Test
    fun appendFile_utf8_concatenates() {
        val b = bridge()
        assertTrue(b.appendFile("log.txt", "a", "utf8"))
        assertTrue(b.appendFile("log.txt", "b", "utf8"))
        assertEquals("ab", b.readFile("log.txt", "utf8"))
    }

    // ---------------- case-insensitive walk (bridge-level smoke for Html5DiskPath integration) ----------------
    //
    // these lock in NW.js / Windows posture: callers compose paths in arbitrary case (CrossCode
    // hits `C:\Users\xuser\AppData\Roaming\CrossCode\...`, RMMV writes `save/file1.rpgsave` then
    // some plugins try `Save/File1.rpgsave`, etc.). bridge must fold case on every surface.

    @Test
    fun readFile_caseDiffersFromDisk_resolves() {
        val b = bridge()
        b.writeFile("save/file1.rpgsave", "payload", "utf8")
        assertEquals("payload", b.readFile("Save/File1.rpgsave", "utf8"))
        assertEquals("payload", b.readFile("SAVE/FILE1.RPGSAVE", "utf8"))
    }

    @Test
    fun exists_caseDiffersFromDisk_resolves() {
        val b = bridge()
        b.writeFile("save/file1.rpgsave", "x", "utf8")
        assertTrue(b.exists("Save/File1.rpgsave"))
        assertTrue(b.exists("SAVE/file1.RPGSAVE"))
    }

    @Test
    fun stat_caseDiffersFromDisk_resolves() {
        val b = bridge()
        b.writeFile("save/file1.rpgsave", "12345", "utf8")
        val json = JSONObject(b.stat("Save/File1.rpgsave"))
        assertEquals(5L, json.getLong("size"))
        assertTrue(json.getBoolean("isFile"))
    }

    @Test
    fun unlink_caseDiffersFromDisk_resolves() {
        val b = bridge()
        b.writeFile("save/file1.rpgsave", "x", "utf8")
        assertTrue(b.unlink("Save/File1.rpgsave"))
        assertFalse(b.exists("save/file1.rpgsave"))
    }

    @Test
    fun readdir_caseDiffersFromDisk_resolves() {
        val b = bridge()
        b.writeFile("save/a.txt", "a", "utf8")
        b.writeFile("save/b.txt", "b", "utf8")
        val arr = JSONArray(b.readdir("SAVE"))
        val names = (0 until arr.length()).map { arr.getString(it) }.toSet()
        assertEquals(setOf("a.txt", "b.txt"), names)
    }

    // CrossCode-class write semantics: parent dir exists in one case, new file written under
    // requested case. result must land in the EXISTING case-folded parent (not a new sibling).
    @Test
    fun writeFile_caseDifferingParent_landsInExistingDir() {
        assumeCaseSensitiveFs()
        val b = bridge()
        // pre-create parent in lowercase
        b.mkdir("appdata/roaming", true)
        // write with mixed case — must hit the existing lowercase dir, not create a sibling
        assertTrue(b.writeFile("AppData/Roaming/CrossCode/save.json", "{}", "utf8"))
        assertTrue(File(tempFolder.root, "appdata/roaming/CrossCode/save.json").isFile)
        // no sibling "AppData" dir created
        assertFalse(
            "case-folded parent must NOT spawn a case-differing sibling",
            File(tempFolder.root, "AppData").exists(),
        )
    }

    // RMMV atomic-save pattern: write tmp, rename onto final. dest path uses different case
    // for an existing parent dir. rename must land in the existing dir.
    @Test
    fun rename_destinationCaseDifferingParent_landsInExistingDir() {
        assumeCaseSensitiveFs()
        val b = bridge()
        b.writeFile("save/.tmp.rpgsave", "newdata", "utf8")
        assertTrue(b.rename("save/.tmp.rpgsave", "Save/file1.rpgsave"))
        assertTrue(File(tempFolder.root, "save/file1.rpgsave").isFile)
        assertFalse(File(tempFolder.root, "Save").exists())
    }

    // appendFile under case-folded parent — same write-mode semantics as writeFile.
    @Test
    fun appendFile_caseDifferingParent_landsInExistingDir() {
        assumeCaseSensitiveFs()
        val b = bridge()
        b.mkdir("logs", false)
        assertTrue(b.appendFile("LOGS/run.log", "tick\n", "utf8"))
        assertTrue(File(tempFolder.root, "logs/run.log").isFile)
        assertFalse(File(tempFolder.root, "LOGS").exists())
    }

    // mkdir on a case-fold of an existing dir is a no-op (returns true, doesn't create sibling).
    @Test
    fun mkdir_caseFoldOfExistingDir_noOpsAndReturnsTrue() {
        assumeCaseSensitiveFs()
        val b = bridge()
        b.mkdir("data", false)
        assertTrue(b.mkdir("DATA", false))
        // no DATA sibling
        val matchingDirs = tempFolder.root.list { _, name -> name.equals("data", ignoreCase = true) }
        assertEquals(1, matchingDirs?.size)
        assertEquals("data", matchingDirs?.first())
    }

    // production target (Android ext4/f2fs) is case-sensitive; macOS APFS is case-INSENSITIVE
    // by default, so "AppData" and "appdata" are the same inode and the "no-sibling" assertions
    // are meaningless there. Linux CI runs ext4 and exercises the real path. shared util tests
    // in Html5DiskPathTest are filesystem-agnostic (they test the walk logic directly without
    // depending on disk case behavior) and stay enabled everywhere.
    private fun assumeCaseSensitiveFs() {
        val probe = File(tempFolder.root, "CASE_PROBE_${System.nanoTime()}")
        probe.writeText("x")
        val collides = File(probe.parentFile, probe.name.lowercase()).exists()
        probe.delete()
        org.junit.Assume.assumeFalse(
            "skipped on case-insensitive filesystem (production target is case-sensitive Android)",
            collides,
        )
    }

    // ---------------- fixture reader (mirrors SteamworksStubTest idiom) ----------------

    private fun readFixtureBytes(relPath: String): ByteArray {
        val candidates = listOf(
            File("src/test/resources/$relPath"),
            File("app/src/test/resources/$relPath"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("fixture not found: $relPath; tried: ${candidates.map { it.absolutePath }}")
        return f.readBytes()
    }
}
