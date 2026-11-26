package app.gamenative.html5.shim

import android.content.Context
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONException
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
        val gseDirs = SteamService.html5SaveDirs(context, appId)
        require(gseDirs.isNotEmpty()) { "no GSE save dirs for appId=$appId" }
        // the wine path gets this from ContainerManager; html5 has to create it.
        val gseDir = gseDirs[0].also { it.mkdirs() }
        foldLegacyIntoHome(gseDirs)
        // fallback so AchievementWatcher always gets a configDir -- without one, titles with no
        // steam_settings/ still write achievements.json but never upload to Steam.
        val configDir = SteamService.findSteamSettingsDir(context, appId)
            ?: fallbackConfigDir(gseDir).also { it.mkdirs() }.absolutePath

        // the launch migration can move the mapping away while the cache still says this app.
        if (SteamService.cachedAchievementsAppId != appId || !File(configDir, NAME_TO_BLOCK_FILE).exists()) {
            // home only: a Steam-state copy in the legacy dir would be moved over home by the next migration.
            SteamService.generateAchievements(appId, configDir, seedDirs = listOf(gseDir))
        }

        val userStats = userStatsFetcher(appId)
        if (userStats == null || userStats.result != EResult.OK) {
            Timber.tag(TAG).i("fetcher returned null/non-OK for appId=$appId; preserving on-disk state")
            return loadFromDisk(gseDirs, configDir, wasOffline = true, statTypeHints = Html5StatTypeCache.read(context, appId))
        }

        val nameToBlockBit = readNameToBlockBitMap(configDir)
        val (steamState, steamTimes) = decodeAchievementBlocks(userStats, nameToBlockBit)
        // keep offline unlocks Steam hasn't seen unless Steam reset them since (same rule as wine).
        val (achState, achTimes) = mergeLocalUnlocks(
            gseDirs.map { File(it, "achievements.json") },
            nameToBlockBit,
            steamState,
            steamTimes,
            SteamService.achievementResetTimes(userStats, nameToBlockBit),
        )

        val (statValues, statTypes) = decodeStats(userStats)

        Timber.tag(TAG).i(
            "appId=%d steam earned=%s local only=%s steam stats=%s",
            appId,
            steamState.keys.sorted(),
            (achState.keys - steamState.keys).sorted(),
            statValues.toSortedMap(),
        )

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
        val gseDirs = SteamService.html5SaveDirs(context, appId)
        foldLegacyIntoHome(gseDirs)
        // same configDir fallback as seed(); mkdirs so the watcher's mapping read doesn't ENOENT.
        val configDir = SteamService.findSteamSettingsDir(context, appId)
            ?: gseDirs.firstOrNull()?.let { fallbackConfigDir(it) }?.also { it.mkdirs() }?.absolutePath
        return loadFromDisk(gseDirs, configDir, wasOffline = false, statTypeHints = Html5StatTypeCache.read(context, appId))
    }

    // same dir SteamService.findSteamSettingsDir falls back to, so close-time sync finds the mapping.
    private fun fallbackConfigDir(gseDir: File): File = SteamService.gseSteamSettingsDir(gseDir)

    // name -> (blockId, bitIndex) from achievement_name_to_block.json.
    @JvmStatic
    internal fun readNameToBlockBitMap(configDir: String?): Map<String, Pair<Int, Int>> {
        if (configDir == null) return emptyMap()
        val file = File(configDir, NAME_TO_BLOCK_FILE)
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

    // earned state from the LIVE stat bitmask (unlockTime[] survives a reset); a set bit with no
    // timestamp falls back to now.
    @JvmStatic
    internal fun decodeAchievementBlocks(
        userStats: UserStatsCallback,
        nameToBlockBit: Map<String, Pair<Int, Int>>,
    ): Pair<Map<String, Boolean>, Map<String, Long>> {
        val liveStats = userStats.stats.associate { it.statId to it.statValue }
        val blocks = userStats.achievementBlocks.associateBy { it.achievementId }
        val state = mutableMapOf<String, Boolean>()
        val times = mutableMapOf<String, Long>()
        for ((name, blockBit) in nameToBlockBit) {
            val (blockId, bitIndex) = blockBit
            val live = liveStats[blockId] ?: continue
            if ((live and (1 shl bitIndex)) == 0) continue
            state[name] = true
            val unlockTime = blocks[blockId]?.unlockTime?.getOrNull(bitIndex) ?: 0
            times[name] = if (unlockTime > 0) unlockTime.toLong() else System.currentTimeMillis() / 1000
        }
        return state to times
    }

    // local unlocks from every save dir, newest time wins; an unreadable file is treated as empty.
    // only schema names count, except with no schema, where local unlocks are kept as they are.
    @JvmStatic
    internal fun mergeLocalUnlocks(
        achFiles: List<File>,
        nameToBlockBit: Map<String, Pair<Int, Int>>,
        steamState: Map<String, Boolean>,
        steamTimes: Map<String, Long>,
        resets: Map<String, Long>,
    ): Pair<Map<String, Boolean>, Map<String, Long>> {
        val localState = mutableMapOf<String, Boolean>()
        val localTimes = mutableMapOf<String, Long>()
        for (achFile in achFiles) {
            val (fileState, fileTimes) = achFile.takeIf { it.exists() }
                ?.let { runCatching { parseAchievementsJson(it) }.getOrNull() }
                ?: continue
            fileState.forEach { (name, earned) -> if (earned) localState[name] = true }
            fileTimes.forEach { (name, time) -> localTimes[name] = maxOf(localTimes[name] ?: 0L, time) }
        }
        val state = mutableMapOf<String, Boolean>()
        val times = mutableMapOf<String, Long>()
        val names = nameToBlockBit.keys.ifEmpty { localState.keys }
        for (name in names) {
            val localTime = localTimes[name] ?: 0L
            val steamEarned = steamState[name] == true
            if (!steamEarned && SteamService.isPreResetUnlock(localTime, resets[name])) continue
            if (!steamEarned && localState[name] != true) continue
            state[name] = true
            val time = maxOf(localTime, steamTimes[name] ?: 0L)
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
    internal fun parseAchievementsJson(file: File): Pair<Map<String, Boolean>, Map<String, Long>> =
        parseAchievementsJson(file.readText(Charsets.UTF_8))

    private fun parseAchievementsJson(text: String): Pair<Map<String, Boolean>, Map<String, Long>> {
        val json = JSONObject(text)
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
        if (nameToBlockBit.isNotEmpty() || !existingAchFile.exists()) return true
        // schema unknown: only to fold in unlocks home lacks (from the legacy dir).
        val onDisk = runCatching { parseAchievementsJson(existingAchFile).first.filterValues { it }.keys }
            .getOrDefault(emptySet())
        return !onDisk.containsAll(achState.filterValues { it }.keys)
    }

    // the launch migration moves the legacy GSE Saves dir over home with REPLACE_EXISTING, and an offline launch
    // skips it, so either side can be the newer one. folded here before anything reads home, online or not:
    // unlocks are the union (newest time per name), a stat file replaces home's only when home has none or it is
    // newer by mtime (the migration keeps mtimes). the legacy copies are then deleted, so the migration has
    // nothing left to move over home.
    @JvmStatic
    internal fun foldLegacyIntoHome(gseDirs: List<File>) {
        val home = gseDirs.firstOrNull() ?: return
        for (legacy in gseDirs.drop(1)) {
            runCatching { foldAchievements(home, legacy) }
                .onFailure { Timber.tag(TAG).w(it, "achievement fold failed from %s", legacy) }
            runCatching { foldStats(home, legacy) }
                .onFailure { Timber.tag(TAG).w(it, "stat fold failed from %s", legacy) }
        }
    }

    private fun foldAchievements(home: File, legacy: File) {
        val legacyFile = File(legacy, "achievements.json")
        if (!legacyFile.exists()) return
        // a file that isn't json holds nothing to keep; deleting it stops the migration moving it over home. one
        // that fails to read is left alone.
        val (legacyState, legacyTimes) = readForFold(legacyFile) ?: return
        val homeFile = File(home, "achievements.json")
        val (homeState, homeTimes) = if (homeFile.exists()) readForFold(homeFile) ?: return else NO_UNLOCKS
        val (state, times) = unionUnlocks(listOf(homeState to homeTimes, legacyState to legacyTimes))
        if (state != homeState.filterValues { it } || times != homeTimes.filterKeys { homeState[it] == true }) {
            GoldbergSaveFiles.writeAchievementsJsonAtomic(home, state, times)
            Timber.tag(TAG).i("folded legacy unlocks into home: %s", (state.keys - homeState.filterValues { it }.keys).sorted())
        }
        legacyFile.delete()
    }

    private val NO_UNLOCKS = emptyMap<String, Boolean>() to emptyMap<String, Long>()

    // null = read failed.
    private fun readForFold(file: File): Pair<Map<String, Boolean>, Map<String, Long>>? {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "can't read %s; fold skipped", file)
            return null
        }
        return try {
            parseAchievementsJson(text)
        } catch (e: JSONException) {
            NO_UNLOCKS
        }
    }

    // earned in any file = earned; time = newest earned time.
    @JvmStatic
    internal fun unionUnlocks(
        files: List<Pair<Map<String, Boolean>, Map<String, Long>>>,
    ): Pair<Map<String, Boolean>, Map<String, Long>> {
        val state = mutableMapOf<String, Boolean>()
        val times = mutableMapOf<String, Long>()
        for ((fileState, fileTimes) in files) {
            for ((name, earned) in fileState) {
                if (!earned) continue
                state[name] = true
                fileTimes[name]?.let { t -> times[name] = maxOf(times[name] ?: 0L, t) }
            }
        }
        return state to times
    }

    private fun foldStats(home: File, legacy: File) {
        val legacyStats = File(legacy, "stats")
        val files = legacyStats.listFiles()?.filter { it.isFile } ?: return
        val homeStats = File(home, "stats")
        for (file in files) {
            val target = File(homeStats, file.name)
            if (isNewerStat(file, target)) {
                homeStats.mkdirs()
                val tmp = File(homeStats, "${file.name}.fold")
                file.copyTo(tmp, overwrite = true)
                tmp.setLastModified(file.lastModified())
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    continue
                }
                Timber.tag(TAG).i("folded newer legacy stat %s into home", file.name)
            }
            file.delete()
        }
    }

    // legacy wins only when home has no such stat or the legacy file was written later.
    @JvmStatic
    internal fun isNewerStat(legacy: File, home: File): Boolean = !home.isFile || legacy.lastModified() > home.lastModified()

    private fun writeStatFiles(gseDir: File, statValues: Map<String, Number>, statTypes: Map<String, String>) {
        statValues.forEach { (name, value) ->
            val type = statTypes[name] ?: "int"
            GoldbergSaveFiles.writeStatFileAtomic(gseDir, name, value, type)
        }
    }

    private const val TAG = "Html5AchievementSeed"
    private const val NAME_TO_BLOCK_FILE = "achievement_name_to_block.json"
}
