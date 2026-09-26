package app.gamenative.filedetect

class Fixture(val name: String, val lines: List<String>)

object Fixtures {
    private fun text(name: String) = Fixtures::class.java.getResource("/fdr/$name")?.readText()
        ?: error("Missing resource /fdr/$name")

    val iniText: String by lazy { text("rules.ini") }
    val ruleSet: RuleSet by lazy { RuleSet.parse(iniText) }
    val detector: FileDetector by lazy { FileDetector(ruleSet) }

    fun set(name: String): List<Fixture> {
        val result = ArrayList<Fixture>()
        var current: String? = null
        val lines = ArrayList<String>()
        fun flush() {
            current?.let { result.add(Fixture(it, lines.toList())) }
            lines.clear()
        }
        for (line in text("$name.txt").lineSequence()) {
            when {
                line.startsWith("== ") -> {
                    flush()
                    current = line.removePrefix("== ")
                }
                line.isNotEmpty() -> lines.add(line)
            }
        }
        flush()
        return result
    }
}
