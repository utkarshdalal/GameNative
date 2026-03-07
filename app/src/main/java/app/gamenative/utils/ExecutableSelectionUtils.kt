package app.gamenative.utils

import java.io.File

object ExecutableSelectionUtils {

    private val ueShipping = Regex(
        ".*-win(32|64)(-shipping)?\\.exe$",
        RegexOption.IGNORE_CASE,
    )

    private val ueBinaries = Regex(
        ".*/binaries/win(32|64)/.*\\.exe$",
        RegexOption.IGNORE_CASE,
    )

    private val negativeKeywords = listOf(
        "crash", "handler", "viewer", "compiler", "tool",
        "setup", "unins", "eac", "launcher", "steam",
    )

    private val genericName = Regex("^[a-z]\\d{1,3}\\.exe$", RegexOption.IGNORE_CASE)

    private fun fuzzyMatch(a: String, b: String): Boolean {
        val cleanA = a.replace(Regex("[^a-z]"), "")
        val cleanB = b.replace(Regex("[^a-z]"), "")
        return cleanA.take(5) == cleanB.take(5)
    }

    private fun File.isLikelyStubExe(): Boolean {
        val n = name.lowercase()
        return genericName.matches(name) || negativeKeywords.any { it in n } || length() < 1_000_000L
    }

    private fun scoreExeOnDisk(file: File, gameName: String): Int {
        var score = 0
        val path = file.path.replace('\\', '/').lowercase()

        if (ueShipping.matches(path)) score += 300
        if (ueBinaries.containsMatchIn(path)) score += 250
        if (!path.contains('/')) score += 200
        if (path.contains(gameName) || fuzzyMatch(path, gameName)) score += 100
        if (negativeKeywords.any { it in path }) score -= 150
        if (genericName.matches(file.name)) score -= 200
        score += 50

        return score
    }

    /**
     * Returns the best candidate executable under [installDir], or null if none found.
     */
    fun choosePrimaryExeFromDisk(installDir: File, gameName: String = installDir.name): File? {
        if (!installDir.exists() || !installDir.isDirectory) return null

        val allExe = installDir.walk()
            .filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }
            .toList()

        if (allExe.isEmpty()) return null

        val pool = allExe.filterNot { it.isLikelyStubExe() }.ifEmpty { allExe }
        val normalizedGameName = gameName.lowercase()

        return pool.maxWithOrNull { a, b ->
            val sa = scoreExeOnDisk(a, normalizedGameName)
            val sb = scoreExeOnDisk(b, normalizedGameName)
            when {
                sa != sb -> sa - sb
                else -> (a.length() - b.length()).toInt()
            }
        }
    }
}
