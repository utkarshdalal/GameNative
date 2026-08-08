package app.gamenative.html5.shim

import android.content.Context
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import timber.log.Timber

// eager pre-launch seed for HTML5 achievement state. mirrors XServerScreen ordering:
// generate (cache-hit-skip) → fetch user stats → decode bitmask → write achievements.json
// + stat files. AchievementWatcher.start() snapshots AFTER this completes -- prior unlocks
// don't fire as new notifications.

// failure-soft: when fetcher returns null (offline / disconnect), existing on-disk state is
// preserved. same on the transient empty case (OK result + empty achievementBlocks): we keep
// prior file IF it exists.

// extracted from inline WebViewScreen wiring for testability -- wires the call site.
object Html5AchievementSeed {

    data class SeedResult(
        val gseDirs: List<File>,
        val configDir: String?,
        val achievementsCache: Map<String, Boolean>,
        val earnedTimes: Map<String, Long>,
        val statsCache: Map<String, Number>,
        val statTypes: Map<String, String>,
        val wasOffline: Boolean = false,
    )

    /**
     * Default fetcher delegates to SteamService.fetchUserStatsForApp. Tests inject a
     * fake fetcher to avoid touching JavaSteam runtime. The wrapper itself returns null when
     * offline / not connected, so the seed path naturally falls into the "preserve on-disk"
     * branch.
     */
    suspend fun seed(
        context: Context,
        appId: Int,
        container: WebViewContainer,
        userStatsFetcher: suspend (Int) -> UserStatsCallback? = SteamService::fetchUserStatsForApp,
    ): SeedResult {
        val gseDirs = SteamService.getGseSaveDirs(context, appId)
        require(gseDirs.isNotEmpty()) { "no GSE save dirs for appId=$appId" }
        // lazy-mkdirs for non-Wine pack:c3 / pack:rmmv per Wine path pre-creates via
        // ContainerManager; HTML5 path needs us to create it.
        val gseDir = gseDirs[0].also { it.mkdirs() }
        // configDir falls back to gseDir.absolutePath so AchievementWatcher (downstream
        // consumer of SeedResult.configDir) gets a non-null path and upload proceeds. without
        // fallback, HTML5 titles whose Wine container has no canonical steam_settings/ write
        // achievements.json to disk but skip Steam upload entirely.
        // generateAchievements writes achievement_name_to_block.json into the same dir we return.
        val configDir = SteamService.findSteamSettingsDir(context, appId) ?: gseDir.absolutePath

        // step 1: populate cachedAchievements + write nameToBlockBit json (skip on cache hit).
        if (SteamService.cachedAchievementsAppId != appId) {
            SteamService.generateAchievements(appId, configDir)
        }

        // step 2: fetch user stats. null OR non-OK = offline/transient → preserve on-disk state.
        val userStats = userStatsFetcher(appId)
        if (userStats == null || userStats.result != EResult.OK) {
            Timber.tag(TAG).i("fetcher returned null/non-OK for appId=$appId; preserving on-disk state")
            return loadFromDisk(gseDirs, configDir, wasOffline = true)
        }

        // step 3: decode achievementBlocks × nameToBlockBit (from on-disk JSON written by
        // generateAchievements ) → cache + JSON file
        val nameToBlockBit = readNameToBlockBitMap(configDir)
        val (achState, achTimes) = decodeAchievementBlocks(userStats, nameToBlockBit)

        // step 4: decode stat values + types from schemaKeyValues VDF
        val (statValues, statTypes) = decodeStats(userStats)

        if (shouldWriteAch(gseDir, achState, nameToBlockBit)) {
            GoldbergSaveFiles.writeAchievementsJsonAtomic(gseDir, achState, achTimes)
        }
        writeStatFiles(gseDir, statValues, statTypes)

        return SeedResult(
            gseDirs = gseDirs,
            configDir = configDir,
            achievementsCache = achState,
            earnedTimes = achTimes,
            statsCache = statValues,
            statTypes = statTypes,
            wasOffline = false,
        )
    }

    /**
     * Cold-start: read whatever exists on disk into a SeedResult.
     * Used by WebViewScreen on seed failure (fallback) and by the offline
     * branch in seed() above.
     */
    fun fromDisk(context: Context, appId: Int): SeedResult {
        val gseDirs = SteamService.getGseSaveDirs(context, appId)
        // same gseDir.absolutePath fallback as seed() so cold-start path (when seed
        // failed entirely) still gives the watcher a non-null configDirectory, allowing upload
        // once a fresh achievement fires. mkdirs() so the watcher's mapping read doesn't ENOENT.
        val configDir = SteamService.findSteamSettingsDir(context, appId)
            ?: gseDirs.firstOrNull()?.also { it.mkdirs() }?.absolutePath
        return loadFromDisk(gseDirs, configDir, wasOffline = false)
    }

    private fun loadFromDisk(gseDirs: List<File>, configDir: String?, wasOffline: Boolean): SeedResult {
        if (gseDirs.isEmpty()) {
            return SeedResult(
                gseDirs = emptyList(),
                configDir = configDir,
                achievementsCache = emptyMap(),
                earnedTimes = emptyMap(),
                statsCache = emptyMap(),
                statTypes = emptyMap(),
                wasOffline = wasOffline,
            )
        }
        val gseDir = gseDirs[0]
        val achFile = File(gseDir, "achievements.json")
        val (achState, achTimes) = if (achFile.exists()) {
            parseAchievementsJson(achFile)
        } else {
            emptyMap<String, Boolean>() to emptyMap()
        }
        // type info unavailable without schema; default to "int" (parser 
        // reads as int regardless -- matches existing behavior).
        val statsDir = File(gseDir, "stats")
        val (statValues, statTypes) = if (statsDir.isDirectory) {
            readStatFilesAsInt(statsDir)
        } else {
            emptyMap<String, Number>() to emptyMap()
        }
        return SeedResult(
            gseDirs = gseDirs,
            configDir = configDir,
            achievementsCache = achState,
            earnedTimes = achTimes,
            statsCache = statValues,
            statTypes = statTypes,
            wasOffline = wasOffline,
        )
    }

    // ---------------- internals (visible-for-testing) ----------------

    /**
     * Reads the achievement name → (blockId, bitIndex) map written by
     * SteamService.generateAchievements (JSONObject keyed by name, values are 2-element
     * JSONArray). Returns empty map if file missing or configDir null.
     */
    @JvmStatic
    internal fun readNameToBlockBitMap(configDir: String?): Map<String, Pair<Int, Int>> {
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

    /**
     * Decoder for current Steam-side earned state. Source of truth is `userStats.stats[i].statValue`
     * (the LIVE bitmask per achievementId block). `userStats.achievementBlocks` carries metadata
     * + historical `unlockTime[]` which is STICKY -- Steam does NOT clear unlockTime entries when
     * an achievement is reset (Steam Support, ClearAchievement RPC, etc.). Reading bitmasks from
     * the stats list is the only way to honor a reset.
     *
     * Prior implementation read `block.unlockTime[bit] > 0` and treated any non-zero timestamp
     * as earned. After a reset the timestamp lingered, so the seed wrote the achievement back as
     * earned, the bridge cache stayed populated, and close-time sync re-uploaded -- completely
     * undoing the reset round-trip.
     *
     * Timestamps are still pulled from `achievementBlocks.unlockTime[]` since that's the only
     * surface we have for them; if the timestamp is missing/zero we fall back to "now" so the
     * Goldberg-shape JSON has a sensible earned_time for any newly-detected unlocks.
     */
    @JvmStatic
    internal fun decodeAchievementBlocks(
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

    /**
     * Encoder inverse of [decodeAchievementBlocks] -- builds the per-block bitmask map to upload.
     * Seeds from the LIVE Steam stat values ([liveStats]: statId/blockId → bitmask) and OR-ins
     * the bit for every newly unlocked achievement. Seeding from live values (NOT from sticky
     * unlockTime timestamps) is what lets a user-initiated reset survive a close-time sync: a
     * timestamp-seeded base would carry prior earned bits forward and re-upload them forever.
     * Unlocked names absent from [nameToBlockBit] are ignored.
     */
    @JvmStatic
    internal fun encodeUnlockBitmasks(
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

    /**
     * Steam-aware filter for close-time achievement push. When [steamEarned] is known (Steam
     * fetch succeeded), only push achievements Steam doesn't already have -- subtracting prevents
     * re-uploading stale local state and undoing user-initiated resets. When it's null (offline /
     * fetch failed), fall back to the additive push of everything earned on disk so genuinely new
     * offline unlocks aren't dropped.
     */
    @JvmStatic
    internal fun achievementsToPush(diskUnlocked: Set<String>, steamEarned: Set<String>?): Set<String> =
        if (steamEarned != null) diskUnlocked - steamEarned else diskUnlocked

    /**
     * Concrete decoder for stat values + types using schemaKeyValues VDF. Mirrors
     * SteamService.storeAchievementUnlocks (lines 3057-3070) for the schema lookup, but inverts
     * direction: there it's name→id (for upload); here it's id→(name, type) (for read).
     *
     * Stat.statValue is raw 32-bit. For type=="float" or "avgrate", reinterpret bits via
     * Float.fromBits (matches Goldberg upstream's float-typed stat representation).
     *
     * Type discriminators in the binary VDF "type" key: "1" = STAT_TYPE_BITS (achievements;
     * skipped here), "2" = int, "3" = float, "4" = ACHIEVEMENTS (skip). If "type_kind" text
     * key exists (e.g., "avgrate"), prefer it.
     */
    @JvmStatic
    internal fun decodeStats(userStats: UserStatsCallback): Pair<Map<String, Number>, Map<String, String>> {
        val idToNameType = mutableMapOf<Int, Pair<String, String>>()
        runCatching {
            val statsKv = userStats.schemaKeyValues["stats"]
            if (statsKv != KeyValue.INVALID) {
                for (entry in statsKv.children) {
                    val type = entry["type"].value ?: continue
                    if (type == "1" || type == "4") continue // STAT_TYPE_BITS / ACHIEVEMENTS
                    val id = entry.name?.toIntOrNull() ?: continue
                    val name = entry["name"].value?.lowercase() ?: continue
                    val typeStr = entry["type_kind"].value ?: when (type) {
                        "2" -> "int"
                        "3" -> "float"
                        else -> "int"
                    }
                    idToNameType[id] = name to typeStr.lowercase()
                }
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "schemaKeyValues parse failed; stats will be empty")
        }

        val values = mutableMapOf<String, Number>()
        val types = mutableMapOf<String, String>()
        for (stat in userStats.stats) {
            val (name, type) = idToNameType[stat.statId] ?: continue
            values[name] = if (type == "float" || type == "avgrate") {
                Float.fromBits(stat.statValue)
            } else {
                stat.statValue
            }
            types[name] = type
        }
        return values to types
    }

    @JvmStatic
    internal fun parseAchievementsJson(file: File): Pair<Map<String, Boolean>, Map<String, Long>> {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val ach = mutableMapOf<String, Boolean>()
        val times = mutableMapOf<String, Long>()
        json.keys().forEach { name ->
            val entry = json.optJSONObject(name) ?: return@forEach
            ach[name] = entry.optBoolean("earned", false)
            entry.optLong("earned_time", 0L).takeIf { it > 0 }?.let { times[name] = it }
        }
        return ach to times
    }

    /**
     * Cold-start stat reader: reads each file in stats/ as 4-byte LE int32 (matches
     * SteamService.kt). Type info unavailable from disk alone -- caller treats all as
     * "int" until next online seed re-populates statTypes from schema.
     */
    @JvmStatic
    internal fun readStatFilesAsInt(statsDir: File): Pair<Map<String, Number>, Map<String, String>> {
        val values = mutableMapOf<String, Number>()
        val types = mutableMapOf<String, String>()
        for (file in statsDir.listFiles() ?: emptyArray()) {
            if (!file.isFile) continue
            val bytes = file.readBytes()
            if (bytes.size >= 4) {
                values[file.name] = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
                types[file.name] = "int"
            }
        }
        return values to types
    }

    // write-decision for achievements.json. preserve disk only when the schema is unknown
    // (nameToBlockBit empty = pre-fetch transient state). when the schema is loaded, Steam is
    // authoritative -- including an all-locked response. that handles the user-reset case (Steam
    // Support wipe, external reset tool): without this, the seed preserved the prior earned file,
    // bridge cache stayed populated, and re-firing the achievement in-game became a silent no-op
    // (no MODIFY → no watcher event → no notification → no live upload). close-time sync would
    // still upload the stale state, undoing the reset entirely.
    //
    // offline-earn protection still holds: when nameToBlockBit is empty (schema not yet fetched),
    // we preserve disk. close-time syncAchievementsFromGoldberg picks up offline-earned
    // achievements at app shutdown.
    private fun shouldWriteAch(
        gseDir: File,
        achState: Map<String, Boolean>,
        nameToBlockBit: Map<String, Pair<Int, Int>>,
    ): Boolean {
        val existingAchFile = File(gseDir, "achievements.json")
        val schemaKnown = nameToBlockBit.isNotEmpty()
        return achState.values.any { it } || !existingAchFile.exists() || schemaKnown
    }

    // write each stat (overwrite on-disk; numeric "empty" doesn't apply).
    private fun writeStatFiles(gseDir: File, statValues: Map<String, Number>, statTypes: Map<String, String>) {
        statValues.forEach { (name, value) ->
            val type = statTypes[name] ?: "int"
            GoldbergSaveFiles.writeStatFileAtomic(gseDir, name, value, type)
        }
    }

    private const val TAG = "Html5AchievementSeed"
}
