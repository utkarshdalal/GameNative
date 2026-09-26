package app.gamenative.filedetect

import java.io.File
import java.net.URI

object Fixtures {
    private fun url(name: String) = Fixtures::class.java.getResource("/fdr/$name")
        ?: error("Missing resource /fdr/$name")

    val iniText: String by lazy { url("rules.ini").readText() }
    val ruleSet: RuleSet by lazy { RuleSet.parse(iniText) }
    val detector: FileDetector by lazy { FileDetector(ruleSet) }

    fun dir(name: String): List<File> {
        val d = File(URI(url("tests/$name").toString()))
        return d.listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }
    }

    fun lines(f: File): List<String> = f.readLines().filter { it.isNotEmpty() }
}
