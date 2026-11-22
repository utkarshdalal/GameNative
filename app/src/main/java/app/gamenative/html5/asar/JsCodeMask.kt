package app.gamenative.html5.asar

// false inside comments, string/template literals and regex literals. shared by preload and entry discovery,
// which both read js source and must not take a commented-out call or a string value for code.
// regex literals matter because minified mains carry things like /"/g: read as a string opener, that quote would
// mask the rest of the file and hide the real loadFile call.
private const val CODE: Byte = 0
private const val COMMENT: Byte = 1
private const val LITERAL: Byte = 2

internal fun jsCodeMask(src: String): BooleanArray {
    // comments and literals are both masked, but only comments are invisible to the regex-vs-division check:
    // a string or regex literal ends an expression, so a `/` after it divides.
    val kind = ByteArray(src.length) // CODE
    var i = 0
    while (i < src.length) {
        val c = src[i]
        val next = if (i + 1 < src.length) src[i + 1] else ' '
        var spanKind = LITERAL
        val end = when {
            c == '/' && next == '/' -> {
                spanKind = COMMENT
                lineEnd(src, i)
            }
            c == '/' && next == '*' -> {
                spanKind = COMMENT
                src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
            }
            c == '/' && regexAllowedAt(src, kind, i) -> regexLiteralEnd(src, i)
            c == '\'' || c == '"' || c == '`' -> {
                var j = i + 1
                while (j < src.length && src[j] != c) {
                    if (src[j] == '\\') j++
                    j++
                }
                minOf(j + 1, src.length)
            }
            else -> -1
        }
        if (end < 0) {
            i++
        } else {
            for (k in i until end) kind[k] = spanKind
            i = end
        }
    }
    return BooleanArray(src.length) { kind[it] == CODE }
}

// js ends a line comment at LF, CR, U+2028 and U+2029, not just LF.
private fun lineEnd(src: String, from: Int): Int {
    var j = from
    while (j < src.length) {
        when (src[j]) {
            '\n', '\r', '\u2028', '\u2029' -> return j
        }
        j++
    }
    return src.length
}

// keywords after which `/` starts a regex rather than dividing.
private val REGEX_PRECEDING_WORDS = setOf(
    "return", "typeof", "case", "do", "else", "in", "of", "new", "delete", "void", "throw", "instanceof",
    "yield", "await",
)

// the usual tokenizer rule: `/` opens a regex when the previous code token can't end an expression.
private fun regexAllowedAt(src: String, kind: ByteArray, slash: Int): Boolean {
    var k = slash - 1
    while (k >= 0 && (kind[k] == COMMENT || (kind[k] == CODE && src[k].isWhitespace()))) k--
    if (k < 0) return true
    if (kind[k] == LITERAL) return false
    val prev = src[k]
    if (prev in "(,=:[!&|?{};+-*%<>~^") return true
    if (prev == '}') return true
    if (prev.isLetter() || prev == '_' || prev == '$') {
        var s = k
        while (s > 0 && (src[s - 1].isLetterOrDigit() || src[s - 1] == '_' || src[s - 1] == '$')) s--
        return src.substring(s, k + 1) in REGEX_PRECEDING_WORDS
    }
    return false
}

// end (exclusive) of the regex literal starting at `slash`, flags included. a newline before the closing slash
// means it wasn't a regex after all; only the lone `/` is masked, which can't hide a call.
private fun regexLiteralEnd(src: String, slash: Int): Int {
    var j = slash + 1
    var inClass = false
    while (j < src.length) {
        when (src[j]) {
            '\\' -> j++
            '[' -> inClass = true
            ']' -> inClass = false
            '/' -> if (!inClass) {
                j++
                while (j < src.length && src[j].isLetter()) j++
                return j
            }
            '\n', '\r', '\u2028', '\u2029' -> return slash + 1
        }
        j++
    }
    return slash + 1
}
