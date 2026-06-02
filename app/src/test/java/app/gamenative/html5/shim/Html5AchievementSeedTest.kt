package app.gamenative.html5.shim

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.AchievementBlocks
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.Stats
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import `in`.dragonbra.javasteam.types.KeyValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// pure-jvm. UserStatsCallback mocked via mockk (real ctor needs IPacketMsg + protobuf parsing).
// SteamService.Companion mocked for getGseSaveDirs / findSteamSettingsDir / generateAchievements
// — same pattern as Html5OptInServiceTest. Context is unused beyond the SteamService calls
// (themselves mocked), so a relaxed mockk<Context>() is sufficient.
class Html5AchievementSeedTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<android.content.Context>(relaxed = true)
    private val webViewContainer = mockk<app.gamenative.runtime.WebViewContainer>(relaxed = true)

    private lateinit var gseDir: File
    private lateinit var configDir: File

    @Before
    fun setUp() {
        gseDir = tempFolder.newFolder("gse")
        configDir = tempFolder.newFolder("config")
        mockkObject(app.gamenative.service.SteamService.Companion)
        every { app.gamenative.service.SteamService.getGseSaveDirs(any(), any()) } returns listOf(gseDir)
        every { app.gamenative.service.SteamService.findSteamSettingsDir(any(), any()) } returns configDir.absolutePath
        // default: cache miss; tests override per-case
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns null
        every { app.gamenative.service.SteamService.cachedAchievements } returns emptyList()
        coEvery { app.gamenative.service.SteamService.generateAchievements(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(app.gamenative.service.SteamService.Companion)
    }

    // ---------------- helpers ----------------

    private fun writeNameToBlockBit(map: Map<String, Pair<Int, Int>>) {
        val json = JSONObject()
        map.forEach { (n, p) -> json.put(n, JSONArray(listOf(p.first, p.second))) }
        File(configDir, "achievement_name_to_block.json").writeText(json.toString())
    }

    private fun fakeUserStats(
        result: EResult = EResult.OK,
        achievementBlocks: List<AchievementBlocks> = emptyList(),
        stats: List<Stats> = emptyList(),
        schemaKv: KeyValue = KeyValue(),
    ): UserStatsCallback {
        val mock = mockk<UserStatsCallback>(relaxed = true)
        every { mock.result } returns result
        every { mock.achievementBlocks } returns achievementBlocks
        every { mock.stats } returns stats
        every { mock.schemaKeyValues } returns schemaKv
        return mock
    }

    // build a schema KV mirroring the real VDF shape: stats[<id>][type] / [name] / [type_kind]
    private fun schemaWith(stats: List<Triple<Int, String, String>>): KeyValue {
        // each Triple = (statId, name, typeKind) — type defaulted to "2" (int) unless typeKind is float
        val root = KeyValue("stats")
        val statsContainer = KeyValue("stats").apply {
            stats.forEach { (id, name, typeKind) ->
                val typeNumeric = when (typeKind) {
                    "float", "avgrate" -> "3"
                    else -> "2"
                }
                children.add(
                    KeyValue(id.toString()).apply {
                        children.add(KeyValue("type", typeNumeric))
                        children.add(KeyValue("name", name))
                        children.add(KeyValue("type_kind", typeKind))
                    },
                )
            }
        }
        // wrap in a parent KV so root["stats"] returns statsContainer
        val wrapper = KeyValue()
        wrapper.children.add(statsContainer)
        return wrapper
    }

    // ---------------- tests ----------------

    @Test
    fun seed_skipsGenerateOnCacheHit() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        val fetcher: suspend (Int) -> UserStatsCallback? = { fakeUserStats() }
        Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)
        coVerify(exactly = 0) { app.gamenative.service.SteamService.generateAchievements(any(), any()) }
    }

    @Test
    fun seed_callsGenerateOnCacheMiss() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns null
        val fetcher: suspend (Int) -> UserStatsCallback? = { fakeUserStats() }
        Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)
        coVerify(exactly = 1) { app.gamenative.service.SteamService.generateAchievements(379210, any()) }
    }

    @Test
    fun seed_writesAchievementsJsonFromBlocks() = runBlocking {
        // blocks: id=0 with [t1, 0, t2] => bit0 unlocked, bit1 locked, bit2 unlocked
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        writeNameToBlockBit(
            mapOf(
                "FIRST_KILL" to (0 to 0),
                "FIRST_DEATH" to (0 to 1),
                "FIRST_BLOOD" to (0 to 2),
            ),
        )
        val fetcher: suspend (Int) -> UserStatsCallback? = {
            fakeUserStats(
                // bits 0 and 2 set, bit 1 clear — matches unlockTime list shape below
                stats = listOf(Stats(statId = 0, statValue = 0b101)),
                achievementBlocks = listOf(
                    AchievementBlocks(achievementId = 0, unlockTime = listOf(1714000000, 0, 1714000050)),
                ),
            )
        }

        Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)

        val json = JSONObject(File(gseDir, "achievements.json").readText())
        assertTrue(json.has("FIRST_KILL"))
        assertTrue(json.has("FIRST_BLOOD"))
        assertFalse("locked achievement should be omitted", json.has("FIRST_DEATH"))
        assertEquals(true, json.getJSONObject("FIRST_KILL").getBoolean("earned"))
        assertEquals(1714000000L, json.getJSONObject("FIRST_KILL").getLong("earned_time"))
        assertEquals(1714000050L, json.getJSONObject("FIRST_BLOOD").getLong("earned_time"))
    }

    @Test
    fun seed_writesStatFilesPerType() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        // float stat: schema typeKind=="float"; statValue is raw bits via floatToRawIntBits.
        val ratioBits = java.lang.Float.floatToRawIntBits(0.85f)
        val fetcher: suspend (Int) -> UserStatsCallback? = {
            fakeUserStats(
                stats = listOf(
                    Stats(statId = 1001, statValue = 10),
                    Stats(statId = 1002, statValue = ratioBits),
                ),
                schemaKv = schemaWith(
                    listOf(
                        Triple(1001, "kills", "int"),
                        Triple(1002, "ratio", "float"),
                    ),
                ),
            )
        }

        Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)

        val killsBytes = File(gseDir, "stats/kills").readBytes()
        assertEquals(4, killsBytes.size)
        assertEquals(10, ByteBuffer.wrap(killsBytes).order(ByteOrder.LITTLE_ENDIAN).int)

        val ratioBytes = File(gseDir, "stats/ratio").readBytes()
        assertEquals(4, ratioBytes.size)
        assertEquals(0.85f, ByteBuffer.wrap(ratioBytes).order(ByteOrder.LITTLE_ENDIAN).float, 0.0001f)
    }

    @Test
    fun seed_lazyMkdirsForNonWine() = runBlocking {
        // setup: tempFolder gives a NEW gseDir per @Before via newFolder() which already exists.
        // To prove lazy-mkdirs, point the SteamService stub at a non-existent path.
        val deepGse = File(tempFolder.root, "deep/nested/notyet/gse")
        assertFalse(deepGse.exists())
        every { app.gamenative.service.SteamService.getGseSaveDirs(any(), any()) } returns listOf(deepGse)
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        val fetcher: suspend (Int) -> UserStatsCallback? = { fakeUserStats() }
        Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)
        assertTrue("seed must lazy-mkdirs the gseDir for non-Wine packs", deepGse.exists())
    }

    @Test
    fun seed_offlineFallback_preservesExistingFile() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        val achFile = File(gseDir, "achievements.json")
        achFile.writeText("""{"X":{"earned":true,"earned_time":1700000000}}""")
        val mtimeBefore = achFile.lastModified()
        Thread.sleep(1100)
        val fetcher: suspend (Int) -> UserStatsCallback? = { null /* offline */ }

        val result = Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)

        assertTrue("wasOffline must be true on null fetcher", result.wasOffline)
        // file content unchanged
        val parsed = JSONObject(achFile.readText())
        assertTrue(parsed.has("X"))
        assertEquals(true, parsed.getJSONObject("X").getBoolean("earned"))
        assertEquals(mtimeBefore, achFile.lastModified())
    }

    @Test
    fun seed_emptyAchievementBlocks_preservesExistingFile() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        val achFile = File(gseDir, "achievements.json")
        achFile.writeText("""{"X":{"earned":true,"earned_time":1700000000}}""")
        val mtimeBefore = achFile.lastModified()
        Thread.sleep(1100)
        // OK result but empty achievementBlocks (transient empty per Pitfall 7)
        val fetcher: suspend (Int) -> UserStatsCallback? = { fakeUserStats(achievementBlocks = emptyList()) }

        Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)

        val parsed = JSONObject(achFile.readText())
        assertTrue(parsed.has("X"))
        assertEquals(mtimeBefore, achFile.lastModified())
    }

    @Test
    fun seed_returnsSeedResult_withGseDirsAndConfigDir() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        writeNameToBlockBit(mapOf("X" to (0 to 0)))
        val fetcher: suspend (Int) -> UserStatsCallback? = {
            fakeUserStats(
                stats = listOf(Stats(statId = 0, statValue = 0b1)),
                achievementBlocks = listOf(AchievementBlocks(0, listOf(1714000000))),
            )
        }
        val result = Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)
        assertEquals(listOf(gseDir), result.gseDirs)
        assertEquals(configDir.absolutePath, result.configDir)
        assertEquals(true, result.achievementsCache["X"])
        assertEquals(1714000000L, result.earnedTimes["X"])
        assertFalse(result.wasOffline)
    }

    @Test
    fun fromDisk_returnsExistingState() {
        // pre-populate disk
        File(gseDir, "achievements.json").writeText(
            """{"X":{"earned":true,"earned_time":1714000000}, "Y":{"earned":false}}""",
        )
        val statsDir = File(gseDir, "stats").also { it.mkdirs() }
        val killsBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(42).array()
        File(statsDir, "kills").writeBytes(killsBytes)

        val result = Html5AchievementSeed.fromDisk(context, 379210)

        assertEquals(true, result.achievementsCache["X"])
        assertEquals(false, result.achievementsCache["Y"])
        assertEquals(1714000000L, result.earnedTimes["X"])
        assertEquals(42, result.statsCache["kills"]?.toInt())
        assertFalse(result.wasOffline)
    }

    @Test
    fun decodeAchievementBlocks_handlesMultiBlockBitmask() {
        val blocks = listOf(
            AchievementBlocks(achievementId = 0, unlockTime = listOf(1714000001, 0, 1714000003)),
            AchievementBlocks(achievementId = 1, unlockTime = listOf(1714000004)),
        )
        // bitmasks must match the unlockTime non-zero pattern: block 0 = bits 0+2 set (0b101);
        // block 1 = bit 0 set (0b001). decode reads stats.statValue for the live bitmask and
        // achievementBlocks.unlockTime[bit] only for the timestamp value.
        val statsList = listOf(
            Stats(statId = 0, statValue = 0b101),
            Stats(statId = 1, statValue = 0b001),
        )
        val mock = fakeUserStats(achievementBlocks = blocks, stats = statsList)
        val mapping = mapOf(
            "A" to (0 to 0),
            "B" to (0 to 1),
            "C" to (0 to 2),
            "D" to (1 to 0),
        )
        val (state, times) = Html5AchievementSeed.decodeAchievementBlocks(mock, mapping)
        assertEquals(true, state["A"])
        assertNull("locked bit should not be earned=true", state["B"])
        assertEquals(true, state["C"])
        assertEquals(true, state["D"])
        assertEquals(1714000001L, times["A"])
        assertEquals(1714000003L, times["C"])
        assertEquals(1714000004L, times["D"])
    }

    // --- encodeUnlockBitmasks (F5.5: close-time bitmask seed + merge) ---

    @Test
    fun encodeUnlockBitmasks_seedsFromLiveStats_notTimestamps() {
        // block 0 already has bit 2 earned live; unlocking bit 0 must OR in WITHOUT dropping bit 2.
        val live = mapOf(0 to 0b100, 1 to 0)
        val mapping = mapOf("A" to (0 to 0), "B" to (0 to 1), "D" to (1 to 0))
        val out = Html5AchievementSeed.encodeUnlockBitmasks(live, mapping, setOf("A"))
        assertEquals("bit 0 OR'd in, live bit 2 preserved", 0b101, out[0])
        assertEquals("untouched block carries live value", 0, out[1])
    }

    @Test
    fun encodeUnlockBitmasks_multipleUnlocksSameBlock_orTogether() {
        val out = Html5AchievementSeed.encodeUnlockBitmasks(
            liveStats = emptyMap(),
            nameToBlockBit = mapOf("A" to (0 to 0), "B" to (0 to 1), "C" to (0 to 3)),
            unlockedNames = setOf("A", "B", "C"),
        )
        assertEquals(0b1011, out[0])
    }

    @Test
    fun encodeUnlockBitmasks_seedWithNoLiveEntry_startsFromZero() {
        // block has no live stat row → base 0, unlock sets only its bit.
        val out = Html5AchievementSeed.encodeUnlockBitmasks(emptyMap(), mapOf("D" to (5 to 2)), setOf("D"))
        assertEquals(0b100, out[5])
    }

    @Test
    fun encodeUnlockBitmasks_unknownName_ignored() {
        val live = mapOf(0 to 0b1)
        val out = Html5AchievementSeed.encodeUnlockBitmasks(live, mapOf("A" to (0 to 0)), setOf("UNKNOWN"))
        assertEquals("unknown unlock changes nothing", 0b1, out[0])
        assertEquals(1, out.size)
    }

    @Test
    fun encodeUnlockBitmasks_noUnlocks_returnsLiveUnchanged() {
        val live = mapOf(0 to 0b101, 2 to 7)
        val out = Html5AchievementSeed.encodeUnlockBitmasks(live, mapOf("A" to (0 to 0)), emptySet())
        assertEquals(live, out)
    }

    // --- achievementsToPush (F5.5: Steam-aware subtract vs offline additive fallback) ---

    @Test
    fun achievementsToPush_steamKnown_subtractsAlreadyEarned() {
        assertEquals(setOf("C"), Html5AchievementSeed.achievementsToPush(setOf("A", "B", "C"), setOf("A", "B")))
    }

    @Test
    fun achievementsToPush_steamHasAll_pushesNothing() {
        assertEquals(emptySet<String>(), Html5AchievementSeed.achievementsToPush(setOf("A", "B"), setOf("A", "B", "C")))
    }

    @Test
    fun achievementsToPush_fetchFailed_fallsBackToAdditive() {
        // null steamEarned = offline / fetch failed → push everything earned on disk so genuinely
        // new offline unlocks aren't dropped.
        val disk = setOf("A", "B", "C")
        assertEquals(disk, Html5AchievementSeed.achievementsToPush(disk, null))
    }
}
