package app.gamenative.service.achievements

import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import java.io.File
import org.json.JSONObject

// Steam stores achievements as bits in stat values: stats[i].statValue is the mask for block statId;
// achievement_name_to_block.json maps each name to (blockId, bitIndex).
//
// `achievementBlocks[].unlockTime[]` is STICKY -- Steam doesn't clear it on reset. only statValue reflects
// live earned state, so every read goes through stats; unlockTime[] is used only for timestamps.
object SteamAchievementCodec {

    // name -> (blockId, bitIndex).
    @JvmStatic
    fun readNameToBlockBitMap(configDir: String?): Map<String, Pair<Int, Int>> {
        if (configDir == null) return emptyMap()
        val file = File(configDir, "achievement_name_to_block.json")
        if (!file.exists()) return emptyMap()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val out = mutableMapOf<String, Pair<Int, Int>>()
            json.keys().forEach { name ->
                val arr = json.optJSONArray(name) ?: return@forEach
                if (arr.length() >= 2) out[name] = arr.getInt(0) to arr.getInt(1)
            }
            out
        }.getOrElse { emptyMap() }
    }

    // timestamps (seconds) fall back to now when Steam has none for a set bit.
    @JvmStatic
    fun decodeAchievementBlocks(
        userStats: UserStatsCallback,
        nameToBlockBit: Map<String, Pair<Int, Int>>,
    ): Pair<Map<String, Boolean>, Map<String, Long>> {
        val statByBlockId = userStats.stats.associateBy { it.statId }
        val blockById = userStats.achievementBlocks.associateBy { it.achievementId }
        val state = mutableMapOf<String, Boolean>()
        val times = mutableMapOf<String, Long>()
        for ((name, blockBit) in nameToBlockBit) {
            val (blockId, bitIndex) = blockBit
            val stat = statByBlockId[blockId] ?: continue
            val isEarned = (stat.statValue and (1 shl bitIndex)) != 0
            if (isEarned) {
                state[name] = true
                val unlockTime = blockById[blockId]?.unlockTime?.getOrNull(bitIndex) ?: 0
                times[name] = if (unlockTime > 0) unlockTime.toLong() else System.currentTimeMillis() / 1000
            }
        }
        return state to times
    }

    // seeds from the LIVE bitmasks, not sticky timestamps: a timestamp-seeded base would re-upload
    // reset achievements forever.
    @JvmStatic
    fun encodeUnlockBitmasks(
        liveStats: Map<Int, Int>,
        nameToBlockBit: Map<String, Pair<Int, Int>>,
        unlockedNames: Set<String>,
    ): Map<Int, Int> {
        val out = LinkedHashMap(liveStats)
        for (name in unlockedNames) {
            val (blockId, bitIndex) = nameToBlockBit[name] ?: continue
            out[blockId] = (out[blockId] ?: 0) or (1 shl bitIndex)
        }
        return out
    }

    // reset = sticky unlockTime[] entry whose live bit is clear; returns name -> discarded unlock time.
    // no timestamp means never earned on Steam, so a local flag is an offline unlock, not a reset.
    // blocks with no live stat row are skipped rather than read as reset.
    @JvmStatic
    fun resetAchievements(
        userStats: UserStatsCallback,
        nameToBlockBit: Map<String, Pair<Int, Int>>,
    ): Map<String, Long> {
        val statByBlockId = userStats.stats.associateBy { it.statId }
        val blockById = userStats.achievementBlocks.associateBy { it.achievementId }
        val out = mutableMapOf<String, Long>()
        for ((name, blockBit) in nameToBlockBit) {
            val (blockId, bitIndex) = blockBit
            val stat = statByBlockId[blockId] ?: continue
            val unlockTime = blockById[blockId]?.unlockTime?.getOrNull(bitIndex) ?: 0
            val isEarned = (stat.statValue and (1 shl bitIndex)) != 0
            if (unlockTime > 0 && !isEarned) out[name] = unlockTime.toLong()
        }
        return out
    }

    // a local unlock survives (may be offline, not yet pushed) unless Steam reset it and the local unlock is
    // no newer than the discarded time: that's the pre-reset unlock, and keeping it would re-push it at close.
    @JvmStatic
    fun seedEarnedState(
        localEarned: Boolean,
        localTime: Long,
        steamEarned: Boolean,
        steamTime: Long,
        resetAt: Long?,
    ): Pair<Boolean, Long> {
        if (resetAt != null && !steamEarned && localTime <= resetAt) return false to 0L
        return (localEarned || steamEarned) to maxOf(localTime, steamTime)
    }

    // null steamEarned (offline / fetch failed) pushes everything so offline unlocks aren't dropped.
    @JvmStatic
    fun achievementsToPush(diskUnlocked: Set<String>, steamEarned: Set<String>?): Set<String> =
        if (steamEarned != null) diskUnlocked - steamEarned else diskUnlocked
}
