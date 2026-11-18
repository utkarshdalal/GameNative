package app.gamenative.filedetect

import java.util.regex.Matcher

class FileDetector(private val ruleSet: RuleSet) {

    fun matches(paths: List<String>, filterEvidence: Boolean = true): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        val matchers = arrayOfNulls<Matcher>(ruleSet.rules.size)

        fun run(groups: List<RuleGroup>, path: String) {
            for (group in groups) {
                var best: Rule? = null
                var bestStart = Int.MAX_VALUE
                for (rule in group.rules) {
                    val literal = rule.requiredLiteral
                    if (literal != null && !path.contains(literal)) continue
                    val m = matchers[rule.index] ?: rule.pattern.matcher("").also { matchers[rule.index] = it }
                    m.reset(path)
                    if (m.find()) {
                        val start = m.start()
                        if (start < bestStart) {
                            best = rule
                            bestStart = start
                            if (start == 0) break
                        }
                    }
                }
                if (best != null) counts.merge(best.fullName, 1, Int::plus)
            }
        }

        for (original in paths) {
            val path = asciiLower(original)
            ruleSet.byExtension[phpExtension(path)]?.let { run(it, path) }
            run(ruleSet.anyExtension, path)
        }

        if (counts.isNotEmpty()) {
            deduceEngine(paths, counts)?.let { counts[it] = 1 }
            if (filterEvidence) counts.keys.removeIf { it.startsWith("Evidence.") }
        }
        return counts
    }

    fun detect(paths: List<String>): Detection {
        val counts = matches(paths)
        fun names(type: String): List<String> {
            val prefix = "$type."
            return counts.entries
                .filter { it.key.startsWith(prefix) }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key.substring(prefix.length) }
        }
        return Detection(
            engines = names("Engine"),
            antiCheat = names("AntiCheat"),
            sdks = names("SDK"),
            launchers = names("Launcher"),
            emulators = names("Emulator"),
            containers = names("Container"),
        )
    }

    companion object {
        internal fun deduceEngine(files: List<String>, matches: Map<String, Int>): String? {
            fun has(m: String) = matches.containsKey(m)
            fun not(m: String) = !matches.containsKey(m)
            fun count(vararg search: String) = search.count { matches.containsKey(it) }

            if (has("Evidence.ARC") && has("Evidence.TAB")) return "Engine.ApexEngine"
            if (has("Evidence.RPF") && has("Evidence.METADATA_DAT")) return "Engine.RAGE"
            if (has("Evidence.HDLL") && not("Engine.Lime_OR_OpenFL")) return "Engine.Heaps"

            if (has("Emulator.DOSBOX")) {
                if (has("Evidence.Build")) return "Engine.Build"
                if (has("Evidence.VSWAP") || (has("Evidence.CFG") && has("Evidence.WAD"))) return "Engine.idTech"
            }

            if (has("Evidence.U") && not("Emulator.DOSBOX")) return "Engine.Unreal"

            if (count("Evidence.BIF", "Evidence.TLK") > 1) {
                if (has("Evidence.RIM") || has("Evidence.TGA")) return "Engine.Aurora"
                return "Engine.Infinity"
            }

            if (count("Evidence.OPTIONS_INI", "Evidence.DATA_WIN", "Evidence.SND_OGG") > 1) return "Engine.GameMaker"
            if (has("Evidence.SIERRA_EXE") && has("Evidence.SCR")) return "Engine.SCI"
            if (has("Evidence.PCK") && isEngineGodot(files)) return "Engine.Godot"
            if (has("Evidence.PK3")) return "Engine.idTech"
            return null
        }

        private val EXECUTABLE_EXTENSIONS = setOf("EXE", "X86", "X86_32", "X86_64")

        internal fun isEngineGodot(files: List<String>): Boolean {
            val pcks = ArrayList<String>()
            val exes = HashSet<String>()

            for (original in files) {
                var file = asciiUpper(original)
                val extension = phpExtension(file)
                if (extension == "PCK") {
                    pcks.add(file)
                    continue
                }
                if (extension.isEmpty() || extension == "0") {
                    if (phpDirname(file).endsWith("/MACOS")) {
                        file = file.replace("/MACOS/", "/RESOURCES/")
                    }
                    exes.add("$file.PCK")
                } else if (extension in EXECUTABLE_EXTENSIONS) {
                    exes.add(file.substring(0, file.length - extension.length) + "PCK")
                }
            }

            if (pcks.isNotEmpty()) {
                var onlyDataPck = true
                for (pck in pcks) {
                    if (phpBasename(pck) != "DATA.PCK") onlyDataPck = false
                    if (pck in exes) return true
                }
                if (onlyDataPck) return true
            }
            return false
        }
    }
}
