package app.gamenative.statsgen

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    fun generateStatsAchievements(schema: ByteArray, configDirectory: String): ProcessingResult {
        val parsedSchema = vdfParser.binaryLoads(schema)
        val achievementsOut = mutableListOf<Achievement>()
        val statsOut = mutableListOf<Stat>()

        for ((appId, appData) in parsedSchema) {
            if (appData !is Map<*, *>) continue
            val sch = appData as Map<String, Any>
            val statInfo = sch["stats"] as? Map<String, Any> ?: continue

            for ((statKey, statData) in statInfo) {
                if (statData !is Map<*, *>) continue
                val stat = statData as Map<String, Any>
                val statType = stat["type"]?.toString() ?: continue

                if (statType == StatType.BITS) {
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
                        if (ach.containsKey("progress")) {
                            achievementBuilder["progress"] = ach["progress"] as Any
                        }

                        val achievement = Achievement(
                            name = achievementBuilder["name"]?.toString() ?: "",
                            displayName = achievementBuilder["displayName"] as? Map<String, String>,
                            description = achievementBuilder["description"] as? Map<String, String>,
                            hidden = (achievementBuilder["hidden"] as? Number)?.toInt() ?: 0,
                            icon = achievementBuilder["icon"]?.toString(),
                            iconGray = achievementBuilder["icon_gray"]?.toString(),
                            icongray = achievementBuilder["icongray"]?.toString(),
                            progress = achievementBuilder["progress"] as? Map<String, Any>
                        )
                        achievementsOut.add(achievement)
                    }
                } else {
                    val statBuilder = mutableMapOf<String, Any>()
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
                    }

                    if (stat.containsKey("Default")) {
                        statBuilder["default"] = stat["Default"] as Any
                    } else if (stat.containsKey("default")) {
                        statBuilder["default"] = stat["default"] as Any
                    }

                    val statObj = Stat(
                        name = statBuilder["name"]?.toString() ?: "",
                        type = statBuilder["type"]?.toString() ?: "int",
                        default = statBuilder["default"]?.toString() ?: "0",
                        global = statBuilder["global"]?.toString() ?: "0",
                        min = statBuilder["min"]?.toString()
                    )
                    statsOut.add(statObj)
                }
            }
        }

        var copyDefaultUnlockedImg = false
        var copyDefaultLockedImg = false
        val outputAchievements = mutableListOf<Map<String, Any>>()

        for (ach in achievementsOut) {
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
                copyDefaultUnlockedImg = true
            }

            val iconGray = ach.iconGray
            if (!iconGray.isNullOrEmpty()) {
                outputAch["icon_gray"] = "img/$iconGray"
            } else {
                outputAch["icon_gray"] = "img/steam_default_icon_locked.jpg"
                copyDefaultLockedImg = true
            }

            val icongray = ach.icongray
            if (!icongray.isNullOrEmpty()) {
                outputAch["icongray"] = icongray
            }

            if (ach.progress != null) {
                outputAch["progress"] = ach.progress
            }

            outputAchievements.add(outputAch)
        }

        val outputStats = mutableListOf<Map<String, Any>>()
        for (stat in statsOut) {
            val outputStat = mutableMapOf<String, Any>()
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

        // Write achievements.json
        if (outputAchievements.isNotEmpty()) {
            val achievementsFile = File(configDir, "achievements.json")
            if (achievementsFile.exists()) {
                achievementsFile.delete()
            }

            val jsonBuilder = StringBuilder()
            jsonBuilder.append("[\n")

            for ((index, ach) in outputAchievements.withIndex()) {
                if (index > 0) jsonBuilder.append(",\n")
                jsonBuilder.append("  {\n")

                // Define the desired order of properties
                val orderedKeys = listOf("hidden", "displayName", "description", "icon", "icon_gray", "name")
                val achMap = ach.toMap()
                
                for ((keyIndex, key) in orderedKeys.withIndex()) {
                    val value = achMap[key]
                    if (value != null) {
                        if (keyIndex > 0) jsonBuilder.append(",\n")

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
                            "hidden" -> {
                                jsonBuilder.append("    \"$key\": $value")
                            }
                            else -> {
                                jsonBuilder.append("    \"$key\": \"$value\"")
                            }
                        }
                    }
                }
                jsonBuilder.append("\n  }")
            }

            jsonBuilder.append("\n]")
            achievementsFile.writeText(jsonBuilder.toString(), Charsets.UTF_8)
        }

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

                // Define the desired order of properties
                val orderedKeys = listOf("default", "global", "name", "type")
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

        return ProcessingResult(
            achievements = achievementsOut,
            stats = statsOut,
            copyDefaultUnlockedImg = copyDefaultUnlockedImg,
            copyDefaultLockedImg = copyDefaultLockedImg
        )
    }
}
