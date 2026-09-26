package app.gamenative.filedetect

internal object RequiredLiteral {
    fun of(regex: String): String? {
        var best = ""
        val run = StringBuilder()
        var depth = 0
        var i = 0
        val n = regex.length

        fun flush(dropLast: Boolean) {
            if (dropLast && run.isNotEmpty()) run.setLength(run.length - 1)
            if (run.length > best.length) best = run.toString()
            run.setLength(0)
        }

        while (i < n) {
            val c = regex[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= n) return null
                    val e = regex[i + 1]
                    i += 2
                    if (depth == 0) {
                        if (e.isLetterOrDigit()) flush(false) else appendLiteral(run, e, regex, i) { flush(true) }
                    }
                    continue
                }
                c == '[' -> {
                    if (depth == 0) flush(false)
                    i = skipClass(regex, i)
                    if (i < 0) return null
                    continue
                }
                c == '(' -> {
                    if (depth == 0) flush(false)
                    depth++
                }
                c == ')' -> {
                    depth--
                    if (depth < 0) return null
                    if (depth == 0 && i + 1 < n && regex[i + 1] in "?*{") {
                        i++
                    }
                }
                c == '|' -> {
                    if (depth == 0) return null
                }
                depth == 0 -> {
                    when (c) {
                        '^', '$' -> flush(false)
                        '.', '?', '*', '+', '{', '}' -> flush(false)
                        else -> appendLiteral(run, c, regex, i + 1) { flush(true) }
                    }
                }
            }
            i++
        }
        flush(false)
        return best.takeIf { it.isNotEmpty() }
    }

    private inline fun appendLiteral(run: StringBuilder, c: Char, regex: String, next: Int, onQuantified: () -> Unit) {
        run.append(c)
        if (next < regex.length && regex[next] in "?*{") onQuantified()
    }

    private fun skipClass(regex: String, start: Int): Int {
        var i = start + 1
        if (i < regex.length && regex[i] == '^') i++
        if (i < regex.length && regex[i] == ']') i++
        while (i < regex.length) {
            when (regex[i]) {
                '\\' -> i += 2
                ']' -> return i + 1
                else -> i++
            }
        }
        return -1
    }
}
