package app.gamenative.theme

data class CssDecl(val property: String, val value: String, val important: Boolean)

/** A style rule after nesting is flattened: [selectors] are full selectors. */
data class CssRule(val selectors: List<String>, val decls: List<CssDecl>)

data class CssKeyframes(val name: String, val body: String)

data class CssSheet(
    val rules: List<CssRule>,
    val keyframes: List<CssKeyframes>,
    val fontFaces: List<List<CssDecl>>,
    val imports: List<String>,
    val nestedRules: Int,
)

/**
 * Small hand-written CSS parser: comments, strings, !important, block at-rules (@media, @supports, ...),
 * @keyframes, @font-face, @import and CSS nesting (`&`, implicit descendant). Nothing is executed.
 */
object CssParser {

    private val BLOCK_AT = setOf("media", "supports", "document", "-moz-document", "layer", "container", "scope")
    private val IMPORTANT_RE = Regex("!\\s*important\\s*$", RegexOption.IGNORE_CASE)

    fun parse(css: String): CssSheet {
        val text = stripComments(css)
        val st = State(text)
        st.parseBody(0, text.length, null)
        return CssSheet(st.rules.filterNotNull(), st.keyframes, st.fontFaces, st.imports, st.nested)
    }

    fun stripComments(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        var quote: Char? = null
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                out.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    out.append(s[i + 1]); i += 2; continue
                }
                if (c == quote || c == '\n') quote = null
                i++
            } else if (c == '"' || c == '\'') {
                quote = c; out.append(c); i++
            } else if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                val j = s.indexOf("*/", i + 2)
                val end = if (j >= 0) j + 2 else s.length
                for (k in i until end) if (s[k] == '\n') out.append('\n')
                out.append(' ')
                i = end
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /** Full selectors of a nested rule: `&` takes the parent, otherwise the child is a descendant. */
    fun nestSelectors(parents: List<String>?, children: List<String>): List<String> {
        if (parents == null) return children
        val out = ArrayList<String>()
        for (p in parents) for (c in children) {
            out.add(if (c.contains('&')) c.replace("&", p) else "$p $c")
        }
        return out
    }

    private class State(val text: String) {
        val rules = ArrayList<CssRule?>()
        val keyframes = ArrayList<CssKeyframes>()
        val fontFaces = ArrayList<List<CssDecl>>()
        val imports = ArrayList<String>()
        var nested = 0

        fun blockEnd(start: Int, limit: Int): Int {
            var depth = 1
            var quote: Char? = null
            var i = start
            while (i < limit) {
                val c = text[i]
                if (quote != null) {
                    if (c == '\\') { i += 2; continue }
                    if (c == quote) quote = null
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return limit
        }

        /** Parses [a, b). With [selectors] set, loose declarations belong to a rule with those selectors. */
        fun parseBody(a: Int, b: Int, selectors: List<String>?, collect: MutableList<CssDecl>? = null) {
            val slot = if (selectors != null && collect == null) rules.size.also { rules.add(null) } else -1
            val decls = collect ?: ArrayList()
            var i = a
            while (i < b) {
                while (i < b && (text[i].isWhitespace() || text[i] == ';')) i++
                if (i >= b) break
                var j = i
                var quote: Char? = null
                var depth = 0
                while (j < b) {
                    val c = text[j]
                    if (quote != null) {
                        if (c == '\\') { j += 2; continue }
                        if (c == quote) quote = null
                    } else if (c == '"' || c == '\'') {
                        quote = c
                    } else if (c == '(') {
                        depth++
                    } else if (c == ')') {
                        depth--
                    } else if (depth <= 0 && (c == '{' || c == ';' || c == '}')) {
                        break
                    }
                    j++
                }
                if (j > b) j = b
                val piece = text.substring(i, j).trim()
                if (j >= b || text[j] == '}' || text[j] == ';') {
                    if (piece.startsWith("@")) {
                        val name = piece.drop(1).substringBefore(' ').lowercase()
                        if (name == "import") imports.add(piece.drop(7).trim())
                    } else if (selectors != null) {
                        decl(piece)?.let(decls::add)
                    }
                    i = j + 1
                    continue
                }
                val end = blockEnd(j + 1, b)
                if (piece.startsWith("@")) {
                    val name = piece.drop(1).split(Regex("[\\s(]"), limit = 2)[0].lowercase()
                    val prelude = piece.drop(1 + name.length).trim()
                    when {
                        name in BLOCK_AT -> parseBody(j + 1, end, selectors)
                        name == "keyframes" || name == "-webkit-keyframes" ->
                            keyframes.add(CssKeyframes(prelude.trim('"', '\'', ' '), text.substring(j + 1, end)))
                        name == "font-face" -> {
                            val fd = ArrayList<CssDecl>()
                            parseBody(j + 1, end, listOf("@font-face"), fd)
                            fontFaces.add(fd)
                        }
                    }
                } else {
                    val own = CssValues.splitTop(piece, ',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (own.isNotEmpty()) {
                        if (selectors != null) nested++
                        parseBody(j + 1, end, nestSelectors(selectors, own))
                    }
                }
                i = end + 1
            }
            if (slot >= 0 && decls.isNotEmpty()) rules[slot] = CssRule(selectors!!, decls)
        }

        fun decl(piece: String): CssDecl? {
            val colon = piece.indexOf(':')
            if (colon <= 0) return null
            var prop = piece.substring(0, colon).trim()
            var value = piece.substring(colon + 1).trim()
            if (prop.isEmpty() || value.isEmpty() || prop.contains(' ')) return null
            var important = false
            IMPORTANT_RE.find(value)?.let {
                important = true
                value = value.substring(0, it.range.first).trim()
            }
            if (!prop.startsWith("--")) prop = prop.lowercase()
            return CssDecl(prop, value, important)
        }
    }
}
