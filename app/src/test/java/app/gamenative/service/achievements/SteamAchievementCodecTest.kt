package app.gamenative.service.achievements

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.AchievementBlocks
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.Stats
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// the load-bearing cases are the reset ones: unlockTime[] is sticky, so anything that reads it as
// earned state brings a reset back.
class SteamAchievementCodecTest {

    // block 0: A earned live, B reset (timestamp kept, bit cleared), C never earned.
    private fun resetFixture() = fakeUserStats(
        achievementBlocks = listOf(AchievementBlocks(achievementId = 0, unlockTime = listOf(1714000001, 1714000002, 0))),
        stats = listOf(Stats(statId = 0, statValue = 0b001)),
    )

    private val resetMapping = mapOf("A" to (0 to 0), "B" to (0 to 1), "C" to (0 to 2))

    @Test
    fun resetAchievements_reportsStickyTimestampWithClearBit() {
        val resets = SteamAchievementCodec.resetAchievements(resetFixture(), resetMapping)
        assertEquals(mapOf("B" to 1714000002L), resets)
    }

    @Test
    fun resetAchievements_skipsBlocksWithNoLiveStatRow() {
        val stats = fakeUserStats(
            achievementBlocks = listOf(AchievementBlocks(achievementId = 0, unlockTime = listOf(1714000001))),
            stats = emptyList(),
        )
        assertTrue(SteamAchievementCodec.resetAchievements(stats, mapOf("A" to (0 to 0))).isEmpty())
    }

    // seeding B as earned from Steam's timestamp would push the reset back.
    @Test
    fun seedEarnedState_resetClearsThePreResetLocalUnlock() {
        val out = SteamAchievementCodec.seedEarnedState(
            localEarned = true, localTime = 1714000002L, steamEarned = false, steamTime = 0L, resetAt = 1714000002L,
        )
        assertEquals(false to 0L, out)
    }

    @Test
    fun seedEarnedState_resetKeepsAnUnlockEarnedAgainSince() {
        val out = SteamAchievementCodec.seedEarnedState(
            localEarned = true, localTime = 1800000000L, steamEarned = false, steamTime = 0L, resetAt = 1714000002L,
        )
        assertEquals(true to 1800000000L, out)
    }

    @Test
    fun seedEarnedState_keepsOfflineUnlockSteamNeverSaw() {
        val out = SteamAchievementCodec.seedEarnedState(
            localEarned = true, localTime = 1800000000L, steamEarned = false, steamTime = 0L, resetAt = null,
        )
        assertEquals(true to 1800000000L, out)
    }

    @Test
    fun seedEarnedState_takesSteamUnlockWhenLocalHasNone() {
        val out = SteamAchievementCodec.seedEarnedState(
            localEarned = false, localTime = 0L, steamEarned = true, steamTime = 1714000001L, resetAt = null,
        )
        assertEquals(true to 1714000001L, out)
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
        val (state, times) = SteamAchievementCodec.decodeAchievementBlocks(mock, mapping)
        assertEquals(true, state["A"])
        assertNull("locked bit should not be earned=true", state["B"])
        assertEquals(true, state["C"])
        assertEquals(true, state["D"])
        assertEquals(1714000001L, times["A"])
        assertEquals(1714000003L, times["C"])
        assertEquals(1714000004L, times["D"])
    }

    @Test
    fun encodeUnlockBitmasks_seedsFromLiveStats_notTimestamps() {
        // block 0 already has bit 2 earned live; unlocking bit 0 must OR in WITHOUT dropping bit 2.
        val live = mapOf(0 to 0b100, 1 to 0)
        val mapping = mapOf("A" to (0 to 0), "B" to (0 to 1), "D" to (1 to 0))
        val out = SteamAchievementCodec.encodeUnlockBitmasks(live, mapping, setOf("A"))
        assertEquals("bit 0 OR'd in, live bit 2 preserved", 0b101, out[0])
        assertEquals("untouched block carries live value", 0, out[1])
    }

    @Test
    fun encodeUnlockBitmasks_multipleUnlocksSameBlock_orTogether() {
        val out = SteamAchievementCodec.encodeUnlockBitmasks(
            liveStats = emptyMap(),
            nameToBlockBit = mapOf("A" to (0 to 0), "B" to (0 to 1), "C" to (0 to 3)),
            unlockedNames = setOf("A", "B", "C"),
        )
        assertEquals(0b1011, out[0])
    }

    @Test
    fun encodeUnlockBitmasks_seedWithNoLiveEntry_startsFromZero() {
        // block has no live stat row -> base 0, unlock sets only its bit.
        val out = SteamAchievementCodec.encodeUnlockBitmasks(emptyMap(), mapOf("D" to (5 to 2)), setOf("D"))
        assertEquals(0b100, out[5])
    }

    @Test
    fun encodeUnlockBitmasks_unknownName_ignored() {
        val live = mapOf(0 to 0b1)
        val out = SteamAchievementCodec.encodeUnlockBitmasks(live, mapOf("A" to (0 to 0)), setOf("UNKNOWN"))
        assertEquals("unknown unlock changes nothing", 0b1, out[0])
        assertEquals(1, out.size)
    }

    @Test
    fun encodeUnlockBitmasks_noUnlocks_returnsLiveUnchanged() {
        val live = mapOf(0 to 0b101, 2 to 7)
        val out = SteamAchievementCodec.encodeUnlockBitmasks(live, mapOf("A" to (0 to 0)), emptySet())
        assertEquals(live, out)
    }

    @Test
    fun achievementsToPush_steamKnown_subtractsAlreadyEarned() {
        assertEquals(setOf("C"), SteamAchievementCodec.achievementsToPush(setOf("A", "B", "C"), setOf("A", "B")))
    }

    @Test
    fun achievementsToPush_steamHasAll_pushesNothing() {
        assertEquals(emptySet<String>(), SteamAchievementCodec.achievementsToPush(setOf("A", "B"), setOf("A", "B", "C")))
    }

    @Test
    fun achievementsToPush_fetchFailed_fallsBackToAdditive() {
        // null steamEarned = offline / fetch failed -> push everything earned on disk so genuinely
        // new offline unlocks aren't dropped.
        val disk = setOf("A", "B", "C")
        assertEquals(disk, SteamAchievementCodec.achievementsToPush(disk, null))
    }

    @Test
    fun dropResetUnlocks_dropsThePreResetUnlock() {
        // the game launched offline so launch seeding never cleared it; the close-time push must not
        // resurrect it.
        assertEquals(
            setOf("B"),
            SteamAchievementCodec.dropResetUnlocks(
                diskUnlocked = setOf("A", "B"),
                localUnlockTimes = mapOf("A" to 1000L, "B" to 1000L),
                resets = mapOf("A" to 1000L),
            ),
        )
    }

    @Test
    fun dropResetUnlocks_keepsAnUnlockEarnedAgainAfterTheReset() {
        assertEquals(
            setOf("A"),
            SteamAchievementCodec.dropResetUnlocks(
                diskUnlocked = setOf("A"),
                localUnlockTimes = mapOf("A" to 2000L),
                resets = mapOf("A" to 1000L),
            ),
        )
    }

    @Test
    fun dropResetUnlocks_missingLocalTimeCountsAsPreReset() {
        assertEquals(
            emptySet<String>(),
            SteamAchievementCodec.dropResetUnlocks(
                diskUnlocked = setOf("A"),
                localUnlockTimes = emptyMap(),
                resets = mapOf("A" to 1000L),
            ),
        )
    }

    // mirrors what storeAchievementUnlocks does for a game played OFFLINE: launch seeding never ran, so B's
    // stale flag is still on disk. B's live bit is clear, so without the drop it gets encoded back into the
    // bitmask and the user's reset is undone. this is the choke point BOTH push paths share (close-time sync
    // and AchievementWatcher's real-time upload).
    @Test
    fun storeUnlocksPipeline_offlineLaunch_doesNotResurrectAReset() {
        val stats = resetFixture()
        val diskUnlocked = setOf("A", "B")
        val liveStats = stats.stats.associate { it.statId to it.statValue }

        // without the drop: B's bit is set again.
        val unfiltered = SteamAchievementCodec.encodeUnlockBitmasks(liveStats, resetMapping, diskUnlocked)
        assertEquals(0b011, unfiltered[0])

        val pushNames = SteamAchievementCodec.dropResetUnlocks(
            diskUnlocked,
            localUnlockTimes = mapOf("A" to 1714000001L, "B" to 1714000002L),
            resets = SteamAchievementCodec.resetAchievements(stats, resetMapping),
        )
        assertEquals(setOf("A"), pushNames)
        // with it: B stays cleared, A (genuinely earned) survives.
        assertEquals(0b001, SteamAchievementCodec.encodeUnlockBitmasks(liveStats, resetMapping, pushNames)[0])
    }

    @Test
    fun dropResetUnlocks_noResets_passesEverythingThrough() {
        val disk = setOf("A", "B")
        assertEquals(disk, SteamAchievementCodec.dropResetUnlocks(disk, mapOf("A" to 5L), emptyMap()))
    }

    private fun fakeUserStats(
        result: EResult = EResult.OK,
        achievementBlocks: List<AchievementBlocks> = emptyList(),
        stats: List<Stats> = emptyList(),
    ): UserStatsCallback {
        val mock = mockk<UserStatsCallback>(relaxed = true)
        every { mock.result } returns result
        every { mock.achievementBlocks } returns achievementBlocks
        every { mock.stats } returns stats
        return mock
    }
}
