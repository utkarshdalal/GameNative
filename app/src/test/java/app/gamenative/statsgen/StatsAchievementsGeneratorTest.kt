package app.gamenative.statsgen

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.Stats
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatsAchievementsGeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Steam can set an achievement's live bit without sending its achievement block, so JavaSteam has
    // no expanded entry for it. the live bit alone must still seed it as earned.
    @Test
    fun liveBitWithoutAnAchievementBlockStillSeedsEarned() {
        val userStats = mockk<UserStatsCallback>(relaxed = true)
        every { userStats.result } returns EResult.OK
        every { userStats.stats } returns listOf(Stats(statId = 1, statValue = 0b01))
        every { userStats.achievementBlocks } returns emptyList()

        val result = StatsAchievementsGenerator().generateStatsAchievements(
            schema(),
            userStats,
            tempFolder.newFolder("steam_settings").absolutePath,
        )

        val earned = result.achievements.single { it.name == "ACH_EARNED" }
        assertEquals(true, earned.unlocked)
        assertNotNull("an earned achievement needs an unlock time", earned.unlockTimestamp)
        assertNotEquals(0, earned.unlockTimestamp)
        val locked = result.achievements.single { it.name == "ACH_LOCKED" }
        assertNotEquals(true, locked.unlocked)
    }

    // binary VDF: { "480": { "stats": { "1": { "type": "4", "bits": { "0": ACH_EARNED, "1": ACH_LOCKED } } } } }
    private fun schema(): ByteArray {
        val out = ByteArrayOutputStream()
        fun key(k: String) = out.write(k.toByteArray() + 0.toByte())
        fun open(k: String) = out.write(0x00).also { key(k) }
        fun string(k: String, v: String) {
            out.write(0x01)
            key(k)
            key(v)
        }
        fun close() = out.write(0x08)
        fun achievement(bit: String, name: String) {
            open(bit)
            string("name", name)
            open("display")
            open("name")
            string("english", name)
            close()
            close()
            close()
        }

        open("480")
        open("stats")
        open("1")
        string("type", "4")
        open("bits")
        achievement("0", "ACH_EARNED")
        achievement("1", "ACH_LOCKED")
        close()
        close()
        close()
        close()
        close()
        return out.toByteArray()
    }
}
