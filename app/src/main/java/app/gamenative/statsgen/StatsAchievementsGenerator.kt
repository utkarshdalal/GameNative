package app.gamenative.statsgen

import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.AchievementBlocks
import java.io.File
import timber.log.Timber

class StatsAchievementsGenerator {
    private val vdfParser = VdfParser()

    private fun escapeUnicode(text: String): String {
        val sb = StringBuilder()
        for (char in text) {
            when {
                char.code < 32 || char.code > 126 -> {
                    sb.append(String.format("\\u%04x", char.code))
                }
                char == '\\' -> sb.append("\\")
                char == '"' -> sb.append("\\\"")
                else -> sb.append(char)
            }
        }
        return sb.toString()
    }

    fun parseSchema(schema: ByteArray): ProcessingResult {
        val parsedSchema = vdfParser.binaryLoads(schema)
        val achievementsOut = mutableListOf<Achievement>()
        val statsOut = mutableListOf<Stat>()
        val nameToBlockBit = mutableMapOf<String, Pair<Int, Int>>()

        for ((_, appData) in parsedSchema) {
            if (appData !is Map<*, *>) continue
            val sch = appData as Map<String, Any>
            val statInfo = sch["stats"] as? Map<String, Any> ?: continue

            for ((statKey, statData) in statInfo) {
                if (statData !is Map<*, *>) continue
                val stat = statData as Map<String, Any>
                val statType = stat["type"]?.toString() ?: continue

                if (statType == StatType.STAT_TYPE_BITS || statType == StatType.ACHIEVEMENTS) {
                    val bits = stat["bits"] as? Map<String, Any> ?: continue
                    for ((achNumKey, achData) in bits) {
                        if (achData !is Map<*, *>) continue
                        val ach = achData as Map<String, Any>
                        val display = ach["display"] as? Map<String, Any> ?: emptyMap()

                        val achievementBuilder = mutableMapOf<String, Any>()
                        achievementBuilder["hidden"] = 0

                        for ((displayKey, displayValue) in display) {
                            when (displayKey.lowercase()) {
                                "name" -> {
                                    if (displayValue is Map<*, *>) {
                                        val langMap = mutableMapOf<String, String>()
                                        for ((lang, text) in displayValue) {
                                            langMap[lang.toString()] = text.toString()
                                        }
                                        achievementBuilder["displayName"] = langMap
                                    } else {
                                        achievementBuilder["displayName"] = mapOf("english" to displayValue.toString())
                                    }
                                }
                                "desc" -> {
                                    if (displayValue is Map<*, *>) {
                                        val langMap = mutableMapOf<String, String>()
                                        for ((lang, text) in displayValue) {
                                            langMap[lang.toString()] = text.toString()
                                        }
                                        achievementBuilder["description"] = langMap
                                    } else {
                                        achievementBuilder["description"] = mapOf("english" to displayValue.toString())
                                    }
                                }
                                "hidden" -> {
                                    val value = try {
                                        displayValue.toString().toInt()
                                    } catch (e: NumberFormatException) {
                                        displayValue
                                    }
                                    achievementBuilder["hidden"] = value
                                }
                                else -> {
                                    achievementBuilder[displayKey] = displayValue
                                }
                            }
                        }

                        achievementBuilder["name"] = ach["name"] ?: ""
                        val achName = ach["name"]?.toString() ?: ""
                        if (achName.isNotEmpty()) {
                            val blockId = statKey.toIntOrNull()
                            val bitIndex = achNumKey.toIntOrNull()
                            if (blockId != null && bitIndex != null) {
                                nameToBlockBit[achName] = Pair(blockId, bitIndex)
                            }
                        }
                        if (ach.containsKey("progress")) {
                            achievementBuilder["progress"] = ach["progress"] as Any
                        }

                        achievementsOut.add(
                            Achievement(
                                name = achievementBuilder["name"]?.toString() ?: "",
                                displayName = achievementBuilder["displayName"] as? Map<String, String>,
                                description = achievementBuilder["description"] as? Map<String, String>,
                                hidden = (achievementBuilder["hidden"] as? Number)?.toInt() ?: 0,
                                icon = achievementBuilder["icon"]?.toString(),
                                iconGray = achievementBuilder["icon_gray"]?.toString(),
                                icongray = achievementBuilder["icongray"]?.toString(),
                                progress = achievementBuilder["progress"] as? Map<String, Any>,
                            )
                        )
                    }
                } else {
                    val statBuilder = mutableMapOf<String, Any>()
                    statBuilder["id"] = statKey
                    statBuilder["default"] = "0"
                    statBuilder["global"] = "0"
                    statBuilder["name"] = stat["name"] ?: ""

                    if (stat.containsKey("min")) {
                        statBuilder["min"] = stat["min"] as Any
                    }

                    when (statType) {
                        StatType.INT -> statBuilder["type"] = "int"
                        StatType.FLOAT -> statBuilder["type"] = "float"
                        StatType.AVGRATE -> statBuilder["type"] = "avgrate"
                        StatType.STAT_TYPE_INT -> statBuilder["type"] = "int"
                        StatType.STAT_TYPE_FLOAT -> statBuilder["type"] = "float"
                        StatType.STAT_TYPE_AVGRATE -> statBuilder["type"] = "avgrate"
                    }

                    if (stat.containsKey("Default")) {
                        statBuilder["default"] = stat["Default"] as Any
                    } else if (stat.containsKey("default")) {
                        statBuilder["default"] = stat["default"] as Any
                    }

                    statsOut.add(
                        Stat(
                            id = statBuilder["id"]?.toString() ?: "",
                            name = statBuilder["name"]?.toString() ?: "",
                            type = statBuilder["type"]?.toString() ?: "int",
                            default = statBuilder["default"]?.toString() ?: "0",
                            global = statBuilder["global"]?.toString() ?: "0",
                            min = statBuilder["min"]?.toString(),
                        )
                    )
                }
            }
        }

        return ProcessingResult(
            achievements = achievementsOut,
            stats = statsOut,
            copyDefaultUnlockedImg = achievementsOut.any { it.icon.isNullOrEmpty() },
            copyDefaultLockedImg = achievementsOut.any { it.iconGray.isNullOrEmpty() },
            nameToBlockBit = nameToBlockBit,
        )
    }

    /**
     * Merges earned state from the raw [achievementBlocks] (from [UserStatsCallback.achievementBlocks])
     * into the parsed [result] using the [ProcessingResult.nameToBlockBit] map produced by [parseSchema].
     *
     * Each achievement's (blockId, bitIndex) is looked up in the raw blocks to retrieve its
     * individual unlock timestamp, avoiding the bitIndex=0 bug present in getExpandedAchievements().
     */
    fun applyEarnedState(
        result: ProcessingResult,
        achievementBlocks: List<AchievementBlocks>,
    ): ProcessingResult {
        if (achievementBlocks.isEmpty()) return result

        // blockId -> ordered list of unlock timestamps (one per bit, 0 = locked)
        val blockUnlockTimes: Map<Int, List<Int>> = achievementBlocks.associate { block ->
            block.achievementId to block.unlockTime
        }

        val enriched = result.achievements.map { ach ->
            val (blockId, bitIndex) = result.nameToBlockBit[ach.name] ?: return@map ach
            val unlockTimes = blockUnlockTimes[blockId] ?: return@map ach.copy(unlocked = false)
            val timestamp = unlockTimes.getOrElse(bitIndex) { 0 }
            if (timestamp != 0) {
                ach.copy(unlocked = true, unlockTimestamp = timestamp)
            } else {
                ach.copy(unlocked = false)
            }
        }
        return result.copy(achievements = enriched)
    }

    fun writeConfigFiles(result: ProcessingResult, configDirectory: String) {
        val outputAchievements = mutableListOf<Map<String, Any>>()

        for (ach in result.achievements) {
            val outputAch = mutableMapOf<String, Any>()
            outputAch["name"] = ach.name
            outputAch["displayName"] = ach.displayName ?: emptyMap<String, String>()
            outputAch["description"] = ach.description ?: emptyMap<String, String>()
            outputAch["hidden"] = ach.hidden

            val icon = ach.icon
            if (!icon.isNullOrEmpty()) {
                outputAch["icon"] = "img/$icon"
            } else {
                outputAch["icon"] = "img/steam_default_icon_unlocked.jpg"
            }

            val iconGray = ach.iconGray
            if (!iconGray.isNullOrEmpty()) {
                outputAch["icon_gray"] = "img/$iconGray"
            } else {
                outputAch["icon_gray"] = "img/steam_default_icon_locked.jpg"
            }

            val icongray = ach.icongray
            if (!icongray.isNullOrEmpty()) {
                outputAch["icongray"] = icongray
            }

            if (ach.progress != null) {
                outputAch["progress"] = ach.progress
            }

            ach.unlocked?.let { outputAch["earned"] = it }
            ach.unlockTimestamp?.let { outputAch["earned_time"] = it }
            ach.formattedUnlockTime?.let { outputAch["formattedUnlockTime"] = it }

            outputAchievements.add(outputAch)
        }

        val outputStats = mutableListOf<Map<String, Any>>()
        for (stat in result.stats) {
            val outputStat = mutableMapOf<String, Any>()
            outputStat["id"] = stat.id
            outputStat["name"] = stat.name
            outputStat["type"] = stat.type

            var defaultNum: String
            var globalNum: String

            if (stat.type.lowercase() == "int") {
                try {
                    val defaultInt = stat.default.toInt()
                    val globalInt = stat.global.toInt()
                    defaultNum = defaultInt.toString()
                    globalNum = globalInt.toString()
                } catch (e: NumberFormatException) {
                    try {
                        val defaultFloat = stat.default.toFloat().toInt()
                        val globalFloat = stat.global.toFloat().toInt()
                        defaultNum = defaultFloat.toString()
                        globalNum = globalFloat.toString()
                    } catch (e2: NumberFormatException) {
                        if (!stat.min.isNullOrEmpty()) {
                            defaultNum = stat.min.toInt().toString()
                            globalNum = "0"
                        } else {
                            throw IllegalArgumentException("min not exist in stat and no way to get the data. please report with the appid")
                        }
                    }
                }
            } else {
                defaultNum = stat.default.toFloat().toString()
                globalNum = stat.global.toFloat().toString()
            }

            outputStat["default"] = defaultNum
            outputStat["global"] = globalNum
            outputStats.add(outputStat)
        }

        // Create output directory
        val configDir = File(configDirectory)
        if (!configDir.exists()) {
            configDir.mkdirs()
        }

        // Write achievements.json (always; use [] when empty)
        val achievementsFile = File(configDir, "achievements.json")
        if (achievementsFile.exists()) {
            achievementsFile.delete()
        }
        val achievementsJsonContent = if (outputAchievements.isNotEmpty()) {
            val jsonBuilder = StringBuilder()
            jsonBuilder.append("[\n")

            val orderedKeys = listOf(
                "hidden", "displayName", "description", "icon", "icon_gray", "name",
                "earned", "earned_time", "formattedUnlockTime",
            )

            for ((index, ach) in outputAchievements.withIndex()) {
                if (index > 0) jsonBuilder.append(",\n")
                jsonBuilder.append("  {\n")

                val achMap = ach.toMap()
                var firstKey = true

                for (key in orderedKeys) {
                    val value = achMap[key]
                    if (value != null) {
                        if (!firstKey) jsonBuilder.append(",\n")
                        firstKey = false

                        when (key) {
                            "displayName", "description" -> {
                                jsonBuilder.append("    \"$key\": ")
                                if (value is Map<*, *>) {
                                    jsonBuilder.append("{\n")
                                    val langEntries = value.entries.toList()
                                    for ((langIndex, langEntry) in langEntries.withIndex()) {
                                        if (langIndex > 0) jsonBuilder.append(",\n")
                                        val escapedText = escapeUnicode(langEntry.value.toString())
                                        jsonBuilder.append("      \"${langEntry.key}\": \"$escapedText\"")
                                    }
                                    jsonBuilder.append("\n    }")
                                } else {
                                    val escapedText = escapeUnicode(value.toString())
                                    jsonBuilder.append("\"$escapedText\"")
                                }
                            }
                            "hidden", "earned_time" -> {
                                jsonBuilder.append("    \"$key\": $value")
                            }
                            "earned" -> {
                                jsonBuilder.append("    \"$key\": ${value.toString().lowercase()}")
                            }
                            else -> {
                                jsonBuilder.append("    \"$key\": \"${escapeUnicode(value.toString())}\"")
                            }
                        }
                    }
                }
                jsonBuilder.append("\n  }")
            }

            jsonBuilder.append("\n]")
            jsonBuilder.toString()
        } else {
            "[]"
        }
        achievementsFile.writeText(achievementsJsonContent, Charsets.UTF_8)

        // Write stats.json
        if (outputStats.isNotEmpty()) {
            val statsFile = File(configDir, "stats.json")
            if (statsFile.exists()) {
                statsFile.delete()
            }

            val jsonBuilder = StringBuilder()
            jsonBuilder.append("[\n")

            for ((index, stat) in outputStats.withIndex()) {
                if (index > 0) jsonBuilder.append(",\n")
                jsonBuilder.append("  {\n")

                val orderedKeys = listOf("id", "default", "global", "name", "type")
                val statMap = stat.toMap()

                for ((keyIndex, key) in orderedKeys.withIndex()) {
                    val value = statMap[key]
                    if (value != null) {
                        if (keyIndex > 0) jsonBuilder.append(",\n")
                        jsonBuilder.append("    \"$key\": \"$value\"")
                    }
                }
                jsonBuilder.append("\n  }")
            }

            jsonBuilder.append("\n]")
            statsFile.writeText(jsonBuilder.toString(), Charsets.UTF_8)
        }
    }

    /**
     * Merges earned state from [result] into each directory in [gseSaveDirs] as a
     * Goldberg-compatible achievements.json. Existing files are merged so that
     * locally-earned achievements are never downgraded (earned: true → false).
     */
    fun writeGseAchievementFiles(result: ProcessingResult, gseSaveDirs: List<File>) {
        for (gseDir in gseSaveDirs) {
            try {
                if (!gseDir.exists()) gseDir.mkdirs()
                val achFile = File(gseDir, "achievements.json")
                val existing = if (achFile.exists()) {
                    try {
                        org.json.JSONObject(achFile.readText(Charsets.UTF_8))
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to parse existing GSE achievements.json in ${gseDir.absolutePath}, starting fresh")
                        org.json.JSONObject()
                    }
                } else {
                    org.json.JSONObject()
                }
                for (ach in result.achievements) {
                    val entry = existing.optJSONObject(ach.name) ?: org.json.JSONObject()
                    if (!entry.optBoolean("earned", false)) {
                        entry.put("earned", ach.unlocked == true)
                        entry.put("earned_time", ach.unlockTimestamp?.toLong() ?: 0L)
                    }
                    existing.put(ach.name, entry)
                }
                achFile.writeText(existing.toString(2), Charsets.UTF_8)
                Timber.tag("GenerateAchievements").d("Wrote GSE save achievements.json to ${gseDir.absolutePath}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to write GSE save achievements.json to ${gseDir.absolutePath}")
            }
        }
    }

    fun generateStatsAchievements(schema: ByteArray, configDirectory: String, achievementBlocks: List<AchievementBlocks>): ProcessingResult {
        val parsedSchema = parseSchema(schema)
        val result = applyEarnedState(parsedSchema, achievementBlocks)
        writeConfigFiles(result, configDirectory)
        return result
    }
}
