package app.gamenative.utils

/** Launch arguments games use to start without a headset (-novr, -nonvrmode, -nohmd, -vrmode none…). */
object NonVrLaunchArgs {
    private val flag = Regex("""--?(no-?n?vr\w*|non-?vr\w*|no-?hmd|no-?steamvr|no-?openxr|disable-?vr)""", RegexOption.IGNORE_CASE)

    fun find(args: String): List<String> {
        val tokens = tokens(args)
        return matches(tokens).map { range -> tokens.slice(range).joinToString(" ") }
    }

    fun strip(args: String): String {
        val tokens = tokens(args)
        val removed = matches(tokens).flatten().toSet()
        return tokens.filterIndexed { index, _ -> index !in removed }.joinToString(" ")
    }

    private fun tokens(args: String) = args.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun matches(tokens: List<String>): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var i = 0
        while (i < tokens.size) {
            val size = when {
                flag.matches(tokens[i]) -> 1
                tokens[i].equals("-vrmode", ignoreCase = true) && tokens.getOrNull(i + 1).equals("none", ignoreCase = true) -> 2
                else -> 0
            }
            if (size > 0) result += i until i + size
            i += size.coerceAtLeast(1)
        }
        return result
    }
}
