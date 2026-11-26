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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// UserStatsCallback is mocked (real ctor needs IPacketMsg + protobuf parsing). SteamService.Companion
// is mocked for getGseSaveDirs / findSteamSettingsDir / generateAchievements, so a relaxed
// mockk<Context>() is sufficient.
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
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns null
        every { app.gamenative.service.SteamService.cachedAchievements } returns emptyList()
        coEvery { app.gamenative.service.SteamService.generateAchievements(any(), any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(app.gamenative.service.SteamService.Companion)
    }

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

    // mirrors the real VDF shape: stats[<id>][type] / [name] / [type_kind]
    private fun schemaWith(stats: List<Triple<Int, String, String>>): KeyValue {
        // each Triple = (statId, name, typeKind) -- type defaults to "2" (int) unless typeKind is float
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
        // root["stats"] must return statsContainer
        val wrapper = KeyValue()
        wrapper.children.add(statsContainer)
        return wrapper
    }

    @Test
    fun seed_skipsGenerateOnCacheHit() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        writeNameToBlockBit(mapOf("X" to (0 to 0)))
        val fetcher: suspend (Int) -> UserStatsCallback? = { fakeUserStats() }
        Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)
        coVerify(exactly = 0) { app.gamenative.service.SteamService.generateAchievements(any(), any(), any()) }
    }

    // the launch migration moves steam_settings out from under a warm cache.
    @Test
    fun seed_regeneratesWhenMappingIsGoneDespiteCacheHit() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        val fetcher: suspend (Int) -> UserStatsCallback? = { fakeUserStats() }
        Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)
        coVerify(exactly = 1) { app.gamenative.service.SteamService.generateAchievements(379210, any(), any()) }
    }

    @Test
    fun seed_usesUserdataDirAsHome() = runBlocking {
        val legacy = tempFolder.newFolder("gse-saves")
        every { app.gamenative.service.SteamService.getGseSaveDirs(any(), any()) } returns listOf(legacy, gseDir)
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        writeNameToBlockBit(mapOf("X" to (0 to 0)))
        val fetcher: suspend (Int) -> UserStatsCallback? = {
            fakeUserStats(
                stats = listOf(Stats(statId = 0, statValue = 0b1)),
                achievementBlocks = listOf(AchievementBlocks(0, listOf(1714000000))),
            )
        }

        val result = Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)

        assertEquals(listOf(gseDir, legacy), result.gseDirs)
        assertTrue(JSONObject(File(gseDir, "achievements.json").readText()).has("X"))
        assertFalse(File(legacy, "achievements.json").exists())
    }

    // generateAchievements leaves Steam's state in the legacy dir; the next migration would move it
    // over home, so the seed folds and removes it.
    @Test
    fun seed_keepsHomeUnlockAndDropsSteamOnlyLegacyFile() = runBlocking {
        val legacy = tempFolder.newFolder("gse-saves")
        every { app.gamenative.service.SteamService.getGseSaveDirs(any(), any()) } returns listOf(legacy, gseDir)
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        writeNameToBlockBit(mapOf("FIRST_KILL" to (0 to 0), "TOP_SPEED" to (0 to 1)))
        File(gseDir, "achievements.json").writeText("""{"TOP_SPEED":{"earned":true,"earned_time":1800000000}}""")
        File(legacy, "achievements.json").writeText(
            """{"FIRST_KILL":{"earned":false,"earned_time":0},"TOP_SPEED":{"earned":false,"earned_time":0}}""",
        )
        val fetcher: suspend (Int) -> UserStatsCallback? = {
            fakeUserStats(
                stats = listOf(Stats(statId = 0, statValue = 0)),
                achievementBlocks = listOf(AchievementBlocks(achievementId = 0, unlockTime = listOf(0, 0))),
            )
        }

        val result = Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)

        val json = JSONObject(File(gseDir, "achievements.json").readText())
        assertTrue("pending unlock must survive the seed", json.has("TOP_SPEED"))
        assertEquals(1800000000L, json.getJSONObject("TOP_SPEED").getLong("earned_time"))
        assertEquals(true, result.achievementsCache["TOP_SPEED"])
        assertFalse(File(legacy, "achievements.json").exists())
    }

    @Test
    fun seed_foldsLegacyUnlockIntoHome() = runBlocking {
        val legacy = tempFolder.newFolder("gse-saves")
        every { app.gamenative.service.SteamService.getGseSaveDirs(any(), any()) } returns listOf(legacy, gseDir)
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        writeNameToBlockBit(mapOf("TOP_SPEED" to (0 to 1)))
        File(legacy, "achievements.json").writeText("""{"TOP_SPEED":{"earned":true,"earned_time":1800000000}}""")
        val fetcher: suspend (Int) -> UserStatsCallback? = { fakeUserStats(stats = listOf(Stats(statId = 0, statValue = 0))) }

        Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)

        assertTrue(JSONObject(File(gseDir, "achievements.json").readText()).has("TOP_SPEED"))
        assertFalse(File(legacy, "achievements.json").exists())
    }

    // a later storeStats rewrites achievements.json from the bridge cache, so an empty cache would
    // erase the file anyway.
    @Test
    fun seed_withoutSchema_keepsLocalUnlocksInCache() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        File(gseDir, "achievements.json").writeText("""{"TOP_SPEED":{"earned":true,"earned_time":1800000000}}""")
        val fetcher: suspend (Int) -> UserStatsCallback? = { fakeUserStats() }

        val result = Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)

        assertEquals(true, result.achievementsCache["TOP_SPEED"])
        assertEquals(1800000000L, result.earnedTimes["TOP_SPEED"])
        assertTrue(JSONObject(File(gseDir, "achievements.json").readText()).has("TOP_SPEED"))
    }

    @Test
    fun mergeLocalUnlocks_unionsDirsWithNewestTime() {
        val a = File(tempFolder.root, "a.json").apply { writeText("""{"X":{"earned":true,"earned_time":100}}""") }
        val b = File(tempFolder.root, "b.json").apply {
            writeText("""{"X":{"earned":true,"earned_time":200},"Y":{"earned":true,"earned_time":50}}""")
        }
        val (state, times) = Html5AchievementSeed.mergeLocalUnlocks(
            listOf(a, b, File(tempFolder.root, "missing.json")),
            mapOf("X" to (0 to 0), "Y" to (0 to 1), "Z" to (0 to 2)),
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
        assertEquals(mapOf("X" to true, "Y" to true), state)
        assertEquals(200L, times["X"])
        assertEquals(50L, times["Y"])
    }

    @Test
    fun seed_callsGenerateOnCacheMiss() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns null
        val fetcher: suspend (Int) -> UserStatsCallback? = { fakeUserStats() }
        Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)
        coVerify(exactly = 1) { app.gamenative.service.SteamService.generateAchievements(379210, any(), any()) }
    }

    // the generator writes its schema as achievements.json in configDir, so without a
    // steam_settings dir configDir must not be gseDir, whose achievements.json is the earned state.
    @Test
    fun seed_withoutSteamSettings_keepsGeneratorOutputOutOfGseDir() = runBlocking {
        every { app.gamenative.service.SteamService.findSteamSettingsDir(any(), any()) } returns null
        val fetcher: suspend (Int) -> UserStatsCallback? = { null }
        val result = Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)
        val expected = File(gseDir, "steam_settings")
        coVerify(exactly = 1) {
            app.gamenative.service.SteamService.generateAchievements(379210, expected.absolutePath, any())
        }
        assertEquals(expected.absolutePath, result.configDir)
        assertTrue(expected.isDirectory)
    }

    @Test
    fun seed_keepsLocalUnlockSteamDoesNotHaveYet() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        writeNameToBlockBit(mapOf("FIRST_KILL" to (0 to 0), "FIRST_DEATH" to (0 to 1)))
        // earned offline, never pushed: Steam has no timestamp and a clear bit for it
        File(gseDir, "achievements.json").writeText("""{"FIRST_DEATH":{"earned":true,"earned_time":1800000000}}""")
        val fetcher: suspend (Int) -> UserStatsCallback? = {
            fakeUserStats(
                stats = listOf(Stats(statId = 0, statValue = 0b01)),
                achievementBlocks = listOf(AchievementBlocks(achievementId = 0, unlockTime = listOf(1714000000, 0))),
            )
        }

        val result = Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)

        val json = JSONObject(File(gseDir, "achievements.json").readText())
        assertTrue(json.has("FIRST_KILL"))
        assertTrue("offline unlock must survive the seed", json.has("FIRST_DEATH"))
        assertEquals(1800000000L, json.getJSONObject("FIRST_DEATH").getLong("earned_time"))
        assertEquals(true, result.achievementsCache["FIRST_DEATH"])
    }

    @Test
    fun seed_dropsLocalUnlockSteamHasResetSince() = runBlocking {
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        writeNameToBlockBit(mapOf("FIRST_KILL" to (0 to 0), "FIRST_DEATH" to (0 to 1)))
        File(gseDir, "achievements.json").writeText("""{"FIRST_DEATH":{"earned":true,"earned_time":1714000010}}""")
        // sticky unlock time with a clear live bit = reset on Steam after the local unlock
        val fetcher: suspend (Int) -> UserStatsCallback? = {
            fakeUserStats(
                stats = listOf(Stats(statId = 0, statValue = 0b01)),
                achievementBlocks = listOf(AchievementBlocks(achievementId = 0, unlockTime = listOf(1714000000, 1714000020))),
            )
        }

        val result = Html5AchievementSeed.seed(context, 379210, webViewContainer, fetcher)

        val json = JSONObject(File(gseDir, "achievements.json").readText())
        assertTrue(json.has("FIRST_KILL"))
        assertFalse("reset achievement must not come back", json.has("FIRST_DEATH"))
        assertFalse(result.achievementsCache.containsKey("FIRST_DEATH"))
    }

    @Test
    fun fromDisk_withoutSteamSettings_usesSameFallbackAsSeed() {
        every { app.gamenative.service.SteamService.findSteamSettingsDir(any(), any()) } returns null
        val result = Html5AchievementSeed.fromDisk(context, 379210)
        assertEquals(File(gseDir, "steam_settings").absolutePath, result.configDir)
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
                // bits 0 and 2 set, bit 1 clear -- matches unlockTime list shape below
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
        // tempFolder's gseDir already exists, so point the stub at a non-existent path to prove
        // lazy mkdirs.
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
        // OK result but empty achievementBlocks (Steam transiently returns these)
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

    private fun statFile(dir: File, name: String, value: Int, mtime: Long): File {
        val f = File(File(dir, "stats").also { it.mkdirs() }, name)
        f.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        f.setLastModified(mtime)
        return f
    }

    private fun statValue(f: File): Int = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun fold_keepsLegacyFileThatFailsToRead() {
        val legacy = tempFolder.newFolder("gse-saves")
        File(gseDir, "achievements.json").writeText("""{"A":{"earned":true,"earned_time":500}}""")
        // a directory in its place: exists, but reading it throws an IOException.
        File(legacy, "achievements.json").mkdirs()

        Html5AchievementSeed.foldLegacyIntoHome(listOf(gseDir, legacy))

        assertTrue(File(legacy, "achievements.json").exists())
        assertEquals("""{"A":{"earned":true,"earned_time":500}}""", File(gseDir, "achievements.json").readText())
    }

    @Test
    fun fold_dropsLegacyFileThatIsNotJson() {
        val legacy = tempFolder.newFolder("gse-saves")
        File(gseDir, "achievements.json").writeText("""{"A":{"earned":true,"earned_time":500}}""")
        File(legacy, "achievements.json").writeText("{\"A\":")

        Html5AchievementSeed.foldLegacyIntoHome(listOf(gseDir, legacy))

        assertFalse(File(legacy, "achievements.json").exists())
        assertEquals("""{"A":{"earned":true,"earned_time":500}}""", File(gseDir, "achievements.json").readText())
    }

    // offline first launch after the update: the migration did not run, and the legacy dir is the newer one.
    @Test
    fun seed_offline_foldsNewerLegacyDirBeforeReadingHome() = runBlocking {
        val legacy = tempFolder.newFolder("gse-saves")
        every { app.gamenative.service.SteamService.getGseSaveDirs(any(), any()) } returns listOf(legacy, gseDir)
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns 379210
        File(gseDir, "achievements.json").writeText("""{"X":{"earned":true,"earned_time":100},"Z":{"earned":false}}""")
        File(legacy, "achievements.json").writeText(
            """{"X":{"earned":true,"earned_time":200},"Y":{"earned":true,"earned_time":300},"Z":{"earned":false}}""",
        )
        statFile(gseDir, "kills", 5, 1_700_000_000_000)
        statFile(legacy, "kills", 9, 1_700_000_500_000)
        statFile(gseDir, "score", 50, 1_700_000_900_000)
        statFile(legacy, "score", 1, 1_700_000_100_000)
        statFile(legacy, "deaths", 2, 1_700_000_200_000)

        val result = Html5AchievementSeed.seed(context, 379210, webViewContainer) { null }

        assertTrue(result.wasOffline)
        assertEquals(mapOf("X" to true, "Y" to true), result.achievementsCache)
        assertEquals(200L, result.earnedTimes["X"])
        assertEquals(300L, result.earnedTimes["Y"])
        assertEquals(9, result.statsCache["kills"]?.toInt())
        assertEquals("an older legacy stat must not replace a newer home one", 50, result.statsCache["score"]?.toInt())
        assertEquals(2, result.statsCache["deaths"]?.toInt())
        assertEquals(1_700_000_500_000, File(gseDir, "stats/kills").lastModified())
        assertFalse(File(legacy, "achievements.json").exists())
        assertTrue(File(legacy, "stats").listFiles().isNullOrEmpty())
    }

    // online, but the stats fetch throws after the schema was generated: nothing Steam-only may be left in the
    // legacy dir for the next migration to move over home.
    @Test
    fun seed_fetchFailure_leavesNoGeneratedLegacyCopy() {
        val legacy = tempFolder.newFolder("gse-saves")
        every { app.gamenative.service.SteamService.getGseSaveDirs(any(), any()) } returns listOf(legacy, gseDir)
        every { app.gamenative.service.SteamService.cachedAchievementsAppId } returns null
        File(gseDir, "achievements.json").writeText("""{"TOP_SPEED":{"earned":true,"earned_time":1800000000}}""")
        // what generateAchievements does with the dirs it is given: seed Steam's (locked) state there.
        coEvery { app.gamenative.service.SteamService.generateAchievements(any(), any(), any()) } answers {
            val dirs = thirdArg<List<File>?>() ?: listOf(legacy, gseDir)
            dirs.forEach { d ->
                val f = File(d, "achievements.json")
                val json = if (f.exists()) JSONObject(f.readText()) else JSONObject()
                if (!json.has("TOP_SPEED")) json.put("TOP_SPEED", JSONObject().put("earned", false).put("earned_time", 0))
                f.writeText(json.toString())
            }
        }

        val failed = runCatching {
            runBlocking { Html5AchievementSeed.seed(context, 379210, webViewContainer) { error("stats fetch failed") } }
        }
        assertTrue(failed.isFailure)
        coVerify(exactly = 1) { app.gamenative.service.SteamService.generateAchievements(379210, any(), listOf(gseDir)) }
        assertFalse(File(legacy, "achievements.json").exists())

        val result = Html5AchievementSeed.fromDisk(context, 379210)
        assertEquals(true, result.achievementsCache["TOP_SPEED"])
        assertEquals(1800000000L, result.earnedTimes["TOP_SPEED"])
    }

    @Test
    fun fromDisk_withBothDirs_unionsUnlocksAndKeepsNewerStats() {
        val legacy = tempFolder.newFolder("gse-saves")
        every { app.gamenative.service.SteamService.getGseSaveDirs(any(), any()) } returns listOf(legacy, gseDir)
        File(gseDir, "achievements.json").writeText("""{"A":{"earned":true,"earned_time":500}}""")
        File(legacy, "achievements.json").writeText("""{"A":{"earned":true,"earned_time":400},"B":{"earned":true,"earned_time":450}}""")
        statFile(gseDir, "kills", 7, 1_700_000_900_000)
        statFile(legacy, "kills", 3, 1_700_000_100_000)

        val result = Html5AchievementSeed.fromDisk(context, 379210)

        assertEquals(mapOf("A" to true, "B" to true), result.achievementsCache)
        assertEquals(500L, result.earnedTimes["A"])
        assertEquals(450L, result.earnedTimes["B"])
        assertEquals(7, result.statsCache["kills"]?.toInt())
        assertEquals(7, statValue(File(gseDir, "stats/kills")))
        assertFalse(File(legacy, "achievements.json").exists())
        assertFalse(File(legacy, "stats/kills").exists())
    }

    @Test
    fun unionUnlocks_earnedAnywhereWithNewestTime() {
        val (state, times) = Html5AchievementSeed.unionUnlocks(
            listOf(
                mapOf("X" to true, "Y" to false) to mapOf("X" to 10L, "Y" to 99L),
                mapOf("X" to true, "Y" to true, "W" to false) to mapOf("X" to 20L, "Y" to 5L),
            ),
        )
        assertEquals(mapOf("X" to true, "Y" to true), state)
        assertEquals(mapOf("X" to 20L, "Y" to 5L), times)
    }
}
