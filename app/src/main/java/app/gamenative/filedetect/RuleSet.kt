package app.gamenative.filedetect

import java.util.regex.Pattern

class Rule internal constructor(
    val type: String,
    val name: String,
    val source: String,
    val regex: String,
    val pattern: Pattern,
    val extensions: List<String>?,
    val hasCommonPrefix: Boolean,
    val requiredLiteral: String?,
    internal val sortLength: Int,
    internal val index: Int,
) {
    val fullName: String get() = "$type.$name"
}

class RuleGroup internal constructor(val rules: List<Rule>)

class RuleSet private constructor(
    val rules: List<Rule>,
    val byExtension: Map<String, List<RuleGroup>>,
    val anyExtension: List<RuleGroup>,
) {
    companion object {
        const val NO_EXTENSION_KEY = "_%any%_"
        private const val COMMON_FOLDER_PREFIX = "(?:^|/)"
        private val SIMPLE_EXTENSION = Regex("""\\\.(?:(?<ext>\w+)|\(\?:(?<multi>[\w|]+)\))\$$""")

        fun parse(iniText: String): RuleSet = build(parseIni(iniText))

        fun parseIni(iniText: String): LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>> {
            val sections = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
            var current: LinkedHashMap<String, MutableList<String>>? = null
            for (rawLine in iniText.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    val name = line.substring(1, line.length - 1).trim()
                    current = sections.getOrPut(name) { LinkedHashMap() }
                    continue
                }
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val section = current ?: throw IllegalArgumentException("Key outside of a section: $line")
                var key = line.substring(0, eq).trim()
                var value = line.substring(eq + 1)
                val comment = value.indexOf(';')
                if (comment >= 0) value = value.substring(0, comment)
                value = value.trim()
                if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length - 1)
                }
                if (key.endsWith("[]")) {
                    key = key.substring(0, key.length - 2).trim()
                    section.getOrPut(key) { ArrayList() }.add(value)
                } else {
                    section[key] = mutableListOf(value)
                }
            }
            return sections
        }

        fun build(sections: Map<String, Map<String, List<String>>>): RuleSet {
            val all = ArrayList<Rule>()
            val byExtension = LinkedHashMap<String, MutableList<RuleGroup>>()
            val anyExtension = ArrayList<RuleGroup>()
            var markIndex = 0
            val errors = ArrayList<String>()

            for ((type, rules) in sections) {
                val prefixed = LinkedHashMap<String, MutableList<Rule>>()
                val unprefixed = LinkedHashMap<String, MutableList<Rule>>()

                for ((name, regexes) in rules) {
                    for (source in regexes) {
                        val regex = asciiLower(source)
                        val simple = SIMPLE_EXTENSION.find(regex)
                        val hasCommonPrefix = regex.startsWith(COMMON_FOLDER_PREFIX)
                        val body = if (hasCommonPrefix) regex.substring(COMMON_FOLDER_PREFIX.length) else regex
                        val sortLength = body.length + "(*:$markIndex)".length
                        val pattern = try {
                            Pattern.compile(regex)
                        } catch (e: Exception) {
                            errors.add("$type.$name: $source -> ${e.message?.lineSequence()?.firstOrNull()}")
                            markIndex++
                            continue
                        }
                        val extensions = when {
                            simple == null -> null
                            simple.groups["multi"] != null -> simple.groups["multi"]!!.value.split('|')
                            else -> listOf(simple.groups["ext"]!!.value)
                        }
                        val rule = Rule(type, name, source, regex, pattern, extensions, hasCommonPrefix, RequiredLiteral.of(regex), sortLength, all.size)
                        all.add(rule)
                        val target = if (hasCommonPrefix) prefixed else unprefixed
                        for (key in extensions ?: listOf(NO_EXTENSION_KEY)) {
                            target.getOrPut(key) { ArrayList() }.add(rule)
                        }
                        markIndex++
                    }
                }

                val keys = LinkedHashSet<String>().apply { addAll(prefixed.keys); addAll(unprefixed.keys) }
                for (key in keys) {
                    val groups = if (key == NO_EXTENSION_KEY) anyExtension else byExtension.getOrPut(key) { ArrayList() }
                    prefixed[key]?.let { groups.add(RuleGroup(it.sortedByDescending { r -> r.sortLength })) }
                    unprefixed[key]?.let { groups.add(RuleGroup(it.sortedByDescending { r -> r.sortLength })) }
                }
            }

            if (errors.isNotEmpty()) {
                throw IllegalArgumentException("Rules failed to compile:\n" + errors.joinToString("\n"))
            }
            return RuleSet(all, byExtension, anyExtension)
        }
    }
}
