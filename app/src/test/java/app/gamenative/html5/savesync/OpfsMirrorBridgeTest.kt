package app.gamenative.html5.savesync

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// pure-jvm. mirrors Html5FsBridgeTest.kt 3 precedent — base64 round-trip is NOT
// exercised here (android.util.Base64 returns stubs on JVM); device 08 Row 1)
// covers the byte-level base64 round-trip via Moonstone GOG round-trip. all sandbox + path
// + listing + callback semantics exercised here.
class OpfsMirrorBridgeTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun bridge(): OpfsMirrorBridge =
        OpfsMirrorBridge(containerId = "test", rootResolver = { tmp.root })

    @Test fun listInstallFiles_returnsRelativePaths() {
        val saveDir = File(tmp.root, "save").apply { mkdirs() }
        File(saveDir, "data.json").writeText("hello")
        File(saveDir, "slot1.json").writeText("world")
        val raw = bridge().listInstallFiles("save")
        val arr = JSONArray(raw)
        val names = (0 until arr.length()).map { arr.getString(it) }.toSet()
        assertEquals(setOf("save/data.json", "save/slot1.json"), names)
    }

    @Test fun listInstallFiles_emptyForMissing() {
        assertEquals("[]", bridge().listInstallFiles("nonexistent"))
    }

    @Test fun listInstallFiles_rejectsTraversal() {
        assertEquals("[]", bridge().listInstallFiles("../"))
    }

    @Test fun readInstallFile_nullForMissing() {
        assertNull(bridge().readInstallFile("save/nope.json"))
    }

    @Test fun readInstallFile_rejectsTraversal() {
        assertNull(bridge().readInstallFile("../../etc/passwd"))
    }

    @Test fun writeInstallFile_rejectsTraversal_noSideEffect() {
        // bytes parameter ignored on traversal reject path — base64 stub on JVM is irrelevant
        // because the canonical-path check fires BEFORE Base64.decode. assertion: returns false
        // and no file written outside installRoot.
        val before = tmp.root.parentFile?.list()?.toSet() ?: emptySet()
        assertEquals(false, bridge().writeInstallFile("../../etc/passwd", "anything"))
        val after = tmp.root.parentFile?.list()?.toSet() ?: emptySet()
        assertEquals("traversal must not create siblings: $before vs $after", before, after)
    }

    @Test fun markFlushDone_invokesCallback() {
        var called = false
        OpfsMirrorBridge(containerId = "x", rootResolver = { tmp.root }, onFlushDone = { called = true })
            .markFlushDone()
        assertTrue(called)
    }

    @Test fun construction_succeedsWithRealInstallRoot() {
        val b = bridge()
        assertNotNull(b)
        // smoke: listInstallFiles on freshly-constructed bridge with empty install root
        assertEquals("[]", b.listInstallFiles(""))
    }

    // base64 round-trip writeInstallFile success path + readInstallFile success path are
    // covered by device 08 Row 1 — Moonstone GOG save flushes through this path).
    // any future Robolectric variant of this test class can promote those assertions.
}
