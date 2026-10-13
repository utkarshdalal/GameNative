package app.gamenative.mods

import org.json.JSONArray
import org.json.JSONObject

enum class NexusCollectionInstallClassification {
    AUTO_INSTALLABLE,
    NEEDS_PLACEMENT,
    EXTERNAL_MANUAL,
    UNSUPPORTED,
}

data class NexusCollectionManifestStep(
    val title: String = "",
    val body: String = "",
    val url: String = "",
    val expectedDestination: String = "",
)

data class NexusCollectionRuleSummary(
    val pluginLoadOrder: List<String> = emptyList(),
    val fileConflictRules: List<String> = emptyList(),
    val unsupportedRules: List<String> = emptyList(),
    val ruleSources: List<NexusCollectionRuleSource> = emptyList(),
    val rawRuleCount: Int = 0,
)

data class NexusCollectionRuleSource(
    val path: String,
    val count: Int,
    val itemKeys: List<String> = emptyList(),
    val itemTypes: List<String> = emptyList(),
)

data class NexusCollectionManifestInfo(
    val manualSteps: List<NexusCollectionManifestStep> = emptyList(),
    val rules: NexusCollectionRuleSummary = NexusCollectionRuleSummary(),
) {
    companion object {
        val EMPTY = NexusCollectionManifestInfo()
    }
}

internal object NexusCollectionManifestParser {
    private val noteKeys = setOf(
        "instructions",
        "instruction",
        "notes",
        "note",
        "description",
        "comments",
        "installinstructions",
        "installerinstructions",
        "manualinstructions",
        "message",
    )
    private val destinationKeys = setOf(
        "destination",
        "destinationpath",
        "target",
        "targetpath",
        "installpath",
        "path",
        "folder",
        "directory",
    )
    private val urlKeys = setOf("url", "uri", "link", "externalurl", "website")
    private val manualKeywords = listOf(
        "manual",
        "copy to",
        "place in",
        "put in",
        "game folder",
        "game directory",
        "root folder",
        "data folder",
        "overwrite",
        "run nemesis",
        "run bodyslide",
        "dyndolod",
        "synthesis",
        "bethini",
        "loot",
    )
    private val externalKeywords = listOf(
        "external",
        "off-site",
        "offsite",
        "github",
        "google drive",
        "mega.nz",
        "not hosted",
        "download manually",
        "enb binaries",
    )
    private val unsupportedKeywords = listOf(
        "requires vortex",
        "vortex extension",
        "c# fomod",
        "scripted installer",
        "binary patch",
    )
    private val archiveExtensions = setOf("zip", "7z", "rar")
    private val installerExtensions = setOf("msi", "bat", "cmd", "ps1")

    fun manifestInfo(json: JSONObject): NexusCollectionManifestInfo =
        NexusCollectionManifestInfo(
            manualSteps = manualSteps(json),
            rules = ruleSummary(json),
        )

    fun classify(
        item: JSONObject,
        modId: Long,
        fileId: Long,
        modName: String,
        fileName: String,
    ): NexusCollectionFileManifestData {
        val source = item.optJSONObject("source") ?: JSONObject()
        val notes = collectNamedStrings(item, noteKeys)
            .map(::clean)
            .filter { it.length >= 3 }
            .distinct()
            .take(4)
        val destination = firstNamedString(item, destinationKeys)
            .ifBlank { firstNamedString(source, destinationKeys) }
            .let(::clean)
        val externalUrl = firstExternalUrl(item).ifBlank { firstExternalUrl(source) }
        val combined = (listOf(modName, fileName, destination, externalUrl) + notes)
            .joinToString(" ")
            .lowercase()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val hasNexusFile = modId > 0L && fileId > 0L
        val classification = when {
            unsupportedKeywords.any { it in combined } -> NexusCollectionInstallClassification.UNSUPPORTED
            !hasNexusFile || externalUrl.isNotBlank() || externalKeywords.any { it in combined } ->
                NexusCollectionInstallClassification.EXTERNAL_MANUAL
            extension == "exe" -> NexusCollectionInstallClassification.NEEDS_PLACEMENT
            extension in installerExtensions -> NexusCollectionInstallClassification.EXTERNAL_MANUAL
            extension.isNotBlank() && extension !in archiveExtensions -> NexusCollectionInstallClassification.NEEDS_PLACEMENT
            destination.isNotBlank() || manualKeywords.any { it in combined } ->
                NexusCollectionInstallClassification.NEEDS_PLACEMENT
            else -> NexusCollectionInstallClassification.AUTO_INSTALLABLE
        }
        return NexusCollectionFileManifestData(
            classification = classification,
            notes = notes,
            expectedDestination = destination,
            externalUrl = externalUrl,
        )
    }

    private fun manualSteps(json: JSONObject): List<NexusCollectionManifestStep> {
        val arrays = findArrays(json) { key ->
            val normalized = key.lowercase()
            "instruction" in normalized || "manual" in normalized || normalized == "steps"
        }
        return arrays.flatMap { array ->
            buildList {
                for (i in 0 until array.length()) {
                    when (val value = array.opt(i)) {
                        is JSONObject -> {
                            val body = firstNamedString(value, noteKeys).ifBlank { value.optString("text") }.let(::clean)
                            val title = firstNamedString(value, setOf("title", "name", "label")).let(::clean)
                            val url = firstExternalUrl(value)
                            val destination = firstNamedString(value, destinationKeys).let(::clean)
                            if (body.isNotBlank() || title.isNotBlank() || url.isNotBlank()) {
                                add(NexusCollectionManifestStep(title, body, url, destination))
                            }
                        }
                        is String -> add(NexusCollectionManifestStep(body = clean(value)))
                    }
                }
            }
        }.distinctBy { listOf(it.title, it.body, it.url, it.expectedDestination).joinToString("|").lowercase() }
    }

    private fun ruleSummary(json: JSONObject): NexusCollectionRuleSummary {
        val ruleArrays = findArrayMatches(json) { key ->
            val normalized = key.lowercase()
            "rule" in normalized || "conflict" in normalized || "loadorder" in normalized || "plugin" in normalized
        }
        val arrays = ruleArrays.map { it.array }
        val pluginOrder = arrays.flatMap(::pluginNames).distinctBy { it.lowercase() }
        val conflictRules = arrays.flatMap(::conflictRuleLabels).distinct()
        val unsupported = arrays.flatMap(::unsupportedRuleLabels).distinct()
        val rawCount = arrays.sumOf { it.length() }
        return NexusCollectionRuleSummary(
            pluginLoadOrder = pluginOrder,
            fileConflictRules = conflictRules,
            unsupportedRules = unsupported,
            ruleSources = ruleArrays.map(::ruleSource).distinctBy { it.path },
            rawRuleCount = rawCount,
        )
    }

    private fun pluginNames(array: JSONArray): List<String> = buildList {
        for (i in 0 until array.length()) {
            when (val value = array.opt(i)) {
                is String -> value.takeIf(::isPluginName)?.let { add(clean(it)) }
                is JSONObject -> {
                    val direct = firstNamedString(value, setOf("plugin", "pluginname", "filename", "name"))
                    if (isPluginName(direct)) add(clean(direct))
                    collectNamedStrings(value, setOf("plugin", "pluginname", "filename"))
                        .filter(::isPluginName)
                        .forEach { add(clean(it)) }
                }
            }
        }
    }

    private fun conflictRuleLabels(array: JSONArray): List<String> = buildList {
        for (i in 0 until array.length()) {
            val value = array.optJSONObject(i) ?: continue
            val type = firstNamedString(value, setOf("type", "rule", "action")).lowercase()
            val source = firstNamedString(value, setOf("source", "from", "mod", "modname", "winner", "before")).let(::clean)
            val target = firstNamedString(value, setOf("target", "to", "other", "loser", "after")).let(::clean)
            if (listOf(type, source, target).any { "conflict" in it.lowercase() || "before" in it.lowercase() || "after" in it.lowercase() || "load" in it.lowercase() } ||
                (source.isNotBlank() && target.isNotBlank())
            ) {
                add(listOf(source, target).filter { it.isNotBlank() }.joinToString(" over ").ifBlank { clean(type) })
            }
        }
    }

    private fun unsupportedRuleLabels(array: JSONArray): List<String> = buildList {
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            val text = when (value) {
                is JSONObject -> value.toString()
                is String -> value
                else -> continue
            }
            val normalized = text.lowercase()
            if (unsupportedKeywords.any { it in normalized } || "extension" in normalized || "tool" in normalized) {
                add(clean(text).take(140))
            }
        }
    }

    private data class ArrayMatch(
        val path: String,
        val array: JSONArray,
    )

    private fun findArrays(value: Any?, keyMatches: (String) -> Boolean): List<JSONArray> =
        findArrayMatches(value, keyMatches).map { it.array }

    private fun findArrayMatches(value: Any?, keyMatches: (String) -> Boolean): List<ArrayMatch> {
        val result = mutableListOf<ArrayMatch>()
        fun visit(current: Any?, key: String, path: String, depth: Int) {
            if (depth > 6) return
            when (current) {
                is JSONObject -> current.keys().forEach { childKey ->
                    val childPath = if (path.isBlank()) childKey else "$path.$childKey"
                    visit(current.opt(childKey), childKey, childPath, depth + 1)
                }
                is JSONArray -> {
                    if (keyMatches(key)) result += ArrayMatch(path.ifBlank { key.ifBlank { "$" } }, current)
                    for (i in 0 until current.length()) visit(current.opt(i), key, "$path[$i]", depth + 1)
                }
            }
        }
        visit(value, "", "", 0)
        return result
    }

    private fun ruleSource(match: ArrayMatch): NexusCollectionRuleSource {
        val keys = linkedSetOf<String>()
        val types = linkedSetOf<String>()
        for (i in 0 until minOf(match.array.length(), 6)) {
            when (val value = match.array.opt(i)) {
                is JSONObject -> {
                    types += "object"
                    value.keys().forEach { keys += it }
                }
                is JSONArray -> types += "array"
                is String -> types += "string"
                is Number -> types += "number"
                is Boolean -> types += "boolean"
            }
        }
        return NexusCollectionRuleSource(
            path = match.path,
            count = match.array.length(),
            itemKeys = keys.take(24),
            itemTypes = types.take(8),
        )
    }

    private fun collectNamedStrings(value: Any?, names: Set<String>): List<String> = buildList {
        fun visit(current: Any?, key: String, depth: Int) {
            if (depth > 5) return
            when (current) {
                is JSONObject -> current.keys().forEach { childKey -> visit(current.opt(childKey), childKey, depth + 1) }
                is JSONArray -> for (i in 0 until current.length()) visit(current.opt(i), key, depth + 1)
                is String -> if (key.normalizedKey() in names && current.isNotBlank()) add(current)
            }
        }
        visit(value, "", 0)
    }

    private fun firstNamedString(value: Any?, names: Set<String>): String =
        collectNamedStrings(value, names).firstOrNull().orEmpty()

    private fun firstExternalUrl(value: Any?): String =
        collectNamedStrings(value, urlKeys)
            .map(::clean)
            .firstOrNull { it.startsWith("http", ignoreCase = true) && "nexusmods.com" !in it.lowercase() }
            .orEmpty()

    private fun isPluginName(value: String): Boolean {
        val lower = value.lowercase()
        return lower.endsWith(".esp") || lower.endsWith(".esm") || lower.endsWith(".esl")
    }

    private fun String.normalizedKey(): String =
        filter { it.isLetterOrDigit() }.lowercase()

    private fun clean(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()
}

data class NexusCollectionFileManifestData(
    val classification: NexusCollectionInstallClassification = NexusCollectionInstallClassification.AUTO_INSTALLABLE,
    val notes: List<String> = emptyList(),
    val expectedDestination: String = "",
    val externalUrl: String = "",
)
