package app.gamenative.html5.shim

import android.content.Context
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import app.gamenative.service.achievements.SteamAchievementCodec
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import timber.log.Timber

// pre-launch achievement/stat seed, same ordering as the wine path. AchievementWatcher MUST
// snapshot AFTER this completes, or prior unlocks fire as new notifications.
// offline / transient failures preserve the on-disk state.
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

    suspend fun seed(
        context: Context,
        appId: Int,
        container: WebViewContainer,
        userStatsFetcher: suspend (Int) -> UserStatsCallback? = SteamService::fetchUserStatsForApp,
    ): SeedResult {
        val gseDirs = SteamService.getGseSaveDirs(context, appId)
        require(gseDirs.isNotEmpty()) { "no GSE save dirs for appId=$appId" }
        // the wine path gets this from ContainerManager; html5 has to create it.
        val gseDir = gseDirs[0].also { it.mkdirs() }
        // fallback so AchievementWatcher always gets a configDir -- without one, titles with no
        // steam_settings/ still write achievements.json but never upload to Steam.
        val configDir = SteamService.findSteamSettingsDir(context, appId)
            ?: fallbackConfigDir(gseDir).also { it.mkdirs() }.absolutePath

        if (SteamService.cachedAchievementsAppId != appId) {
            SteamService.generateAchievements(appId, configDir)
        }

        val userStats = userStatsFetcher(appId)
        if (userStats == null || userStats.result != EResult.OK) {
            Timber.tag(TAG).i("fetcher returned null/non-OK for appId=$appId; preserving on-disk state")
            return loadFromDisk(gseDirs, configDir, wasOffline = true, statTypeHints = Html5StatTypeCache.read(context, appId))
        }

        val nameToBlockBit = SteamAchievementCodec.readNameToBlockBitMap(configDir)
        val (steamState, steamTimes) = SteamAchievementCodec.decodeAchievementBlocks(userStats, nameToBlockBit)
        // keep offline unlocks Steam hasn't seen unless Steam reset them since (same rule as wine).
        val (achState, achTimes) = mergeLocalUnlocks(
            File(gseDir, "achievements.json"),
            nameToBlockBit,
            steamState,
            steamTimes,
            SteamAchievementCodec.resetAchievements(userStats, nameToBlockBit),
        )

        val (statValues, statTypes) = decodeStats(userStats)

        if (shouldWriteAch(gseDir, achState, nameToBlockBit)) {
            GoldbergSaveFiles.writeAchievementsJsonAtomic(gseDir, achState, achTimes)
        }
        writeStatFiles(gseDir, statValues, statTypes)
        Html5StatTypeCache.write(context, appId, statTypes)

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

    fun fromDisk(context: Context, appId: Int): SeedResult {
        val gseDirs = SteamService.getGseSaveDirs(context, appId)
        // same configDir fallback as seed(); mkdirs so the watcher's mapping read doesn't ENOENT.
        val configDir = SteamService.findSteamSettingsDir(context, appId)
            ?: gseDirs.firstOrNull()?.let { fallbackConfigDir(it) }?.also { it.mkdirs() }?.absolutePath
        return loadFromDisk(gseDirs, configDir, wasOffline = false, statTypeHints = Html5StatTypeCache.read(context, appId))
    }

    // same dir SteamService.findSteamSettingsDir falls back to, so close-time sync finds the mapping.
    private fun fallbackConfigDir(gseDir: File): File = SteamService.gseSteamSettingsDir(gseDir)

    // only schema names count; an unreadable local file is treated as empty.
    @JvmStatic
    internal fun mergeLocalUnlocks(
        achFile: File,
        nameToBlockBit: Map<String, Pair<Int, Int>>,
        steamState: Map<String, Boolean>,
        steamTimes: Map<String, Long>,
        resets: Map<String, Long>,
    ): Pair<Map<String, Boolean>, Map<String, Long>> {
        val (localState, localTimes) = achFile.takeIf { it.exists() }
            ?.let { runCatching { parseAchievementsJson(it) }.getOrNull() }
            ?: (emptyMap<String, Boolean>() to emptyMap<String, Long>())
        val state = mutableMapOf<String, Boolean>()
        val times = mutableMapOf<String, Long>()
        for (name in nameToBlockBit.keys) {
            val (earned, time) = SteamAchievementCodec.seedEarnedState(
                localEarned = localState[name] == true,
                localTime = localTimes[name] ?: 0L,
                steamEarned = steamState[name] == true,
                steamTime = steamTimes[name] ?: 0L,
                resetAt = resets[name],
            )
            if (!earned) continue
            state[name] = true
            if (time > 0) times[name] = time
        }
        return state to times
    }

    private fun loadFromDisk(
        gseDirs: List<File>,
        configDir: String?,
        wasOffline: Boolean,
        statTypeHints: Map<String, String> = emptyMap(),
    ): SeedResult {
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
        val statsDir = File(gseDir, "stats")
        val (statValues, statTypes) = if (statsDir.isDirectory) {
            readStatFiles(statsDir, statTypeHints)
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

    // schema VDF "type": 2 = int, 3 = float; 1/4 are achievement bits (skipped). a "type_kind"
    // text key (e.g. "avgrate") wins when present. float values are raw bits -> Float.fromBits.
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

    // stat files are untagged, so types come from Html5StatTypeCache; no hint = "int".
    @JvmStatic
    internal fun readStatFiles(
        statsDir: File,
        typeHints: Map<String, String> = emptyMap(),
    ): Pair<Map<String, Number>, Map<String, String>> {
        val values = mutableMapOf<String, Number>()
        val types = mutableMapOf<String, String>()
        // stat file names are lowercase; schema names keep their case.
        val hints = typeHints.mapKeys { (name, _) -> name.lowercase() }
        for (file in statsDir.listFiles() ?: emptyArray()) {
            if (!file.isFile) continue
            val bytes = file.readBytes()
            if (bytes.size >= 4) {
                val type = hints[file.name.lowercase()] ?: "int"
                val raw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                values[file.name] = if (type == "float" || type == "avgrate") raw.float else raw.int
                types[file.name] = type
            }
        }
        return values to types
    }

    // preserve disk only while the schema is unknown. once known, write the merged state even when
    // all-locked: after a Steam-side reset, keeping the stale earned file makes re-earning in-game a
    // silent no-op (no file change -> no watcher event -> no upload).
    private fun shouldWriteAch(
        gseDir: File,
        achState: Map<String, Boolean>,
        nameToBlockBit: Map<String, Pair<Int, Int>>,
    ): Boolean {
        val existingAchFile = File(gseDir, "achievements.json")
        val schemaKnown = nameToBlockBit.isNotEmpty()
        return achState.values.any { it } || !existingAchFile.exists() || schemaKnown
    }

    private fun writeStatFiles(gseDir: File, statValues: Map<String, Number>, statTypes: Map<String, String>) {
        statValues.forEach { (name, value) ->
            val type = statTypes[name] ?: "int"
            GoldbergSaveFiles.writeStatFileAtomic(gseDir, name, value, type)
        }
    }

    private const val TAG = "Html5AchievementSeed"
}
