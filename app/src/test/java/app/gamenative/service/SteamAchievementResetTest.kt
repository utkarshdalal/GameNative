package app.gamenative.service

import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.AchievementBlocks
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.Stats
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteamAchievementResetTest {

    // block 1: A earned, B reset on Steam (timestamp kept, bit cleared), C never earned on Steam.
    private val mapping = mapOf("A" to (1 to 0), "B" to (1 to 1), "C" to (1 to 2))
    private val resetAt = 1714000002L
    private val userStats = mockk<UserStatsCallback>(relaxed = true).also {
        every { it.stats } returns listOf(Stats(statId = 1, statValue = 0b001))
        every { it.achievementBlocks } returns
            listOf(AchievementBlocks(achievementId = 1, unlockTime = listOf(1714000001, resetAt.toInt(), 0)))
    }

    private fun push(local: Map<String, Long>) = SteamService.achievementPushBitmasks(userStats, mapping, local)

    @Test
    fun detectsReset() {
        assertEquals(mapOf("B" to resetAt), SteamService.achievementResetTimes(userStats, mapping))
    }

    @Test
    fun seedsFromLiveBitsNotTimestamps() {
        assertEquals(mapOf(1 to 0b001), push(emptyMap()))
    }

    // offline launch: seeding never ran, so B's pre-reset flag is still on disk.
    @Test
    fun dropsPreResetUnlock() {
        assertEquals(mapOf(1 to 0b001), push(mapOf("A" to 1714000001L, "B" to resetAt)))
    }

    @Test
    fun keepsOfflineReEarn() {
        assertEquals(mapOf(1 to 0b011), push(mapOf("B" to resetAt + 1)))
    }

    @Test
    fun keepsOfflineUnlockSteamNeverSaw() {
        assertEquals(mapOf(1 to 0b101), push(mapOf("C" to 1800000000L)))
    }
}
