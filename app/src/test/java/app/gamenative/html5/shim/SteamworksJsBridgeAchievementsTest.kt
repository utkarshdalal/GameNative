package app.gamenative.html5.shim

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// pure-jvm. @JavascriptInterface metadata-only on JVM classpath precedent).
// covers Plan (10 bridge methods) + atomic-rename writes + cache reads.
// constructor extension: containerId + appId + gseDir.
class SteamworksJsBridgeAchievementsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun bridge(): SteamworksJsBridge {
        val gseDir = tempFolder.newFolder("gse")
        return SteamworksJsBridge(
            containerId = "STEAM_test",
            appId = 379210,
            gseDir = gseDir,
        )
    }

    private fun gseDirOf(b: SteamworksJsBridge): File {
        // bridge stores gseDir; we re-derive via the achievements.json path the bridge writes.
        // tempFolder root + only "gse" subfolder we create per call to bridge().
        return tempFolder.root.listFiles()?.firstOrNull { it.isDirectory && it.name == "gse" }
            ?: error("gse dir not found under tempFolder")
    }

    // ---------------- achievements ----------------

    @Test
    fun activateAchievement_writesGoldbergShape() {
        val b = bridge()
        b.activateAchievement("ACH_FIRST_KILL")
        val achFile = File(gseDirOf(b), "achievements.json")
        assertTrue("achievements.json not written", achFile.exists())
        val json = JSONObject(achFile.readText())
        val entry = json.optJSONObject("ACH_FIRST_KILL")
        assertTrue("entry missing", entry != null)
        assertEquals(true, entry!!.optBoolean("earned", false))
        assertTrue("earned_time missing or zero", entry.optLong("earned_time", 0L) > 0L)
    }

    @Test
    fun activateAchievement_isIdempotent() {
        val b = bridge()
        assertTrue(b.activateAchievement("ACH_X"))
        val achFile = File(gseDirOf(b), "achievements.json")
        val firstTime = JSONObject(achFile.readText()).getJSONObject("ACH_X").getLong("earned_time")
        // second call same name. earned_time MUST NOT bump and method must not throw.
        assertTrue(b.activateAchievement("ACH_X"))
        val secondTime = JSONObject(achFile.readText()).getJSONObject("ACH_X").getLong("earned_time")
        assertEquals("earned_time bumped on idempotent call", firstTime, secondTime)
    }

    @Test
    fun clearAchievement_removesKey() {
        val b = bridge()
        b.activateAchievement("ACH_X")
        b.clearAchievement("ACH_X")
        val achFile = File(gseDirOf(b), "achievements.json")
        val json = JSONObject(achFile.readText())
        assertFalse("key X still present after clear", json.has("ACH_X"))
    }

    @Test
    fun getAchievement_returnsCacheState() {
        val b = bridge()
        b.seedFromSchema(
            achievements = mapOf("X" to true, "Y" to false),
            achTimes = mapOf("X" to 1714000000L),
            stats = emptyMap(),
            types = emptyMap(),
        )
        assertEquals(true, b.getAchievement("X"))
        assertEquals(false, b.getAchievement("Y"))
        assertEquals(false, b.getAchievement("Z"))
    }

    @Test
    fun getAchievementNames_returnsJsonArrayOfKeys() {
        val b = bridge()
        b.seedFromSchema(
            achievements = mapOf("X" to true, "Y" to false),
            achTimes = emptyMap(),
            stats = emptyMap(),
            types = emptyMap(),
        )
        val arr = JSONArray(b.getAchievementNames())
        val names = (0 until arr.length()).map { arr.getString(it) }.toSet()
        assertTrue("X missing", names.contains("X"))
        assertTrue("Y missing", names.contains("Y"))
    }

    @Test
    fun getNumberOfAchievements_returnsCacheSize() {
        val b = bridge()
        b.seedFromSchema(
            achievements = mapOf("X" to true, "Y" to false, "Z" to true),
            achTimes = emptyMap(),
            stats = emptyMap(),
            types = emptyMap(),
        )
        assertEquals(3, b.getNumberOfAchievements())
    }

    @Test
    fun indicateAchievementProgress_returnsTrueNoFileWrite() {
        val b = bridge()
        // noop returning true. achievements.json must NOT be touched.
        assertTrue(b.indicateAchievementProgress("ACH_X", 5, 10))
        val achFile = File(gseDirOf(b), "achievements.json")
        assertFalse("achievements.json should not exist after noop progress call", achFile.exists())
    }

    // ---------------- stats ----------------

    @Test
    fun setStat_writesLeInt32_whenTypeInt() {
        val b = bridge()
        b.seedFromSchema(
            achievements = emptyMap(),
            achTimes = emptyMap(),
            stats = emptyMap(),
            types = mapOf("kills" to "int"),
        )
        assertTrue(b.setStat("kills", 42.0))
        val statFile = File(gseDirOf(b), "stats/kills")
        assertTrue("stat file missing", statFile.exists())
        val bytes = statFile.readBytes()
        assertEquals("expected 4-byte LE int32", 4, bytes.size)
        assertEquals(42, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun setStat_writesLeFloat32_whenTypeFloat() {
        val b = bridge()
        b.seedFromSchema(
            achievements = emptyMap(),
            achTimes = emptyMap(),
            stats = emptyMap(),
            types = mapOf("ratio" to "float"),
        )
        assertTrue(b.setStat("ratio", 0.85))
        val statFile = File(gseDirOf(b), "stats/ratio")
        assertTrue("stat file missing", statFile.exists())
        val bytes = statFile.readBytes()
        assertEquals(4, bytes.size)
        val readBack = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(0.85f, readBack, 0.0001f)
    }

    @Test
    fun setStat_lowercasesFilename() {
        val b = bridge()
        b.seedFromSchema(
            achievements = emptyMap(),
            achTimes = emptyMap(),
            stats = emptyMap(),
            types = mapOf("KillCount" to "int"),
        )
        assertTrue(b.setStat("KillCount", 5.0))
        val lower = File(gseDirOf(b), "stats/killcount")
        assertTrue("expected lowercase filename per SteamService.kt:3066,3078", lower.exists())
    }

    @Test
    fun getStatInt_readsCache() {
        val b = bridge()
        b.seedFromSchema(
            achievements = emptyMap(),
            achTimes = emptyMap(),
            stats = mapOf("kills" to 10),
            types = mapOf("kills" to "int"),
        )
        assertEquals(10, b.getStatInt("kills"))
        assertEquals(0, b.getStatInt("missing"))
    }

    @Test
    fun getStatFloat_readsCache() {
        val b = bridge()
        b.seedFromSchema(
            achievements = emptyMap(),
            achTimes = emptyMap(),
            stats = mapOf("ratio" to 0.85f),
            types = mapOf("ratio" to "float"),
        )
        assertEquals(0.85, b.getStatFloat("ratio"), 0.0001)
        assertEquals(0.0, b.getStatFloat("missing"), 0.0)
    }

    @Test
    fun storeStats_touchesAchievementsJson() {
        val b = bridge()
        // pre-state: write a known achievement so we can detect the touch by mtime delta
        b.activateAchievement("ACH_X")
        val achFile = File(gseDirOf(b), "achievements.json")
        assertTrue(achFile.exists())
        val mtime1 = achFile.lastModified()
        // ensure clock ticks forward (mtime resolution can be 1s on some FS)
        Thread.sleep(1100)
        assertTrue(b.storeStats())
        val mtime2 = achFile.lastModified()
        assertNotEquals("storeStats did not touch achievements.json", mtime1, mtime2)
    }

    @Test
    fun requestStats_returnsTrue() {
        val b = bridge()
        // cache populated at seed; sync return true
        assertTrue(b.requestStats())
    }

    @Test
    fun atomicWrite_doesNotLeaveTmpFile() {
        val b = bridge()
        b.activateAchievement("ACH_X")
        val gseDir = gseDirOf(b)
        val tmp = File(gseDir, "achievements.json.tmp")
        assertFalse(".tmp file should not exist after atomic rename", tmp.exists())
        assertTrue(File(gseDir, "achievements.json").exists())
    }
}
