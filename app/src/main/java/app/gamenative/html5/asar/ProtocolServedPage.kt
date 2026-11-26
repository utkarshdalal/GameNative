package app.gamenative.html5.asar

// an electron app whose main process answers a real https origin itself: `protocol.handle("https", fn)` serves a
// path prefix from a dir under process.resourcesPath (the entry page often from a string in main) and the window
// loads https://<host><prefix><entry>. the page has to run at that origin (first-party cookies, same-origin api
// calls, site checks in the bundle), so everything here is read from the installed main script at launch.
data class ProtocolServedPage(
    val host: String,
    val aliasHosts: Set<String>,
    // starts and ends with '/'.
    val prefix: String,
    val entryName: String,
    // null: the entry is a file under resourceDir like everything else.
    val entryHtml: String?,
    // relative to process.resourcesPath.
    val resourceDir: String,
    // what main appends to app.userAgentFallback.
    val userAgentSuffix: String?,
) {
    val origin: String get() = "https://$host"
    val entryUrl: String get() = "$origin$prefix$entryName"

    fun servesHost(h: String?): Boolean =
        h != null && (h.equals(host, ignoreCase = true) || aliasHosts.any { it.equals(h, ignoreCase = true) })

    // decoded path below the prefix, or null when the url is not one main serves locally.
    fun localPath(scheme: String?, h: String?, path: String?): String? {
        if (!"https".equals(scheme, ignoreCase = true) || !servesHost(h) || path == null) return null
        return path.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    // main only trusts documents under the prefix on the primary host (its will-navigate rule).
    fun isPageDocument(scheme: String?, h: String?, path: String?): Boolean =
        "https".equals(scheme, ignoreCase = true) && h.equals(host, ignoreCase = true) &&
            path?.startsWith(prefix) == true

    companion object {
        private val HANDLE_HTTPS = Regex("""protocol\.handle\(\s*(["'`])https\1\s*,""")
        private val RESOURCES_JOIN = Regex(
            """join\(\s*process\.resourcesPath((?:\s*,\s*(?:"[^"\\]*"|'[^'\\]*'))+)\s*\)""",
        )
        private val QUOTED_ARG = Regex(""""([^"\\]*)"|'([^'\\]*)'""")
        private val NEW_RESPONSE = Regex("""new\s+Response\(\s*(?=["'`])""")
        private val UA_FALLBACK = Regex("""userAgentFallback\s*=\s*(?=`)""")
        private val UA_TEMPLATE = Regex("""^\$\{[^}]*userAgentFallback\s*\}([\s\S]*)$""")
        private val ENTRY_URL = Regex(
            """^https://([a-z0-9.-]+)(/(?:[^/?#\s]+/)*)([^/?#\s]+\.html?)$""",
            RegexOption.IGNORE_CASE,
        )
        private val HTML_SCRIPT = Regex("""<script\b""", RegexOption.IGNORE_CASE)

        fun parse(mainScript: String): ProtocolServedPage? {
            val code = jsCodeMask(mainScript)
            if (HANDLE_HTTPS.findAll(mainScript).none { code[it.range.first] }) return null
            val literals = JsLiterals(mainScript, code)
            val values = literals.all.indices.map { literals.value(it) }

            val entry = pickEntryUrl(mainScript, literals, values) ?: return null
            val m = ENTRY_URL.matchEntire(entry) ?: return null
            val host = m.groupValues[1].lowercase()
            val aliasPattern = Regex("""^[a-z0-9-]+\.${Regex.escape(host)}$""", RegexOption.IGNORE_CASE)
            val aliases = values.filterNotNull().filter { aliasPattern.matches(it) }.map { it.lowercase() }.toSet()

            val resourceDir = RESOURCES_JOIN.findAll(mainScript).firstOrNull { code[it.range.first] }
                ?.let { join ->
                    QUOTED_ARG.findAll(join.groupValues[1]).map { it.groupValues[1] + it.groupValues[2] }.toList()
                }
                ?.let { normalizeRelative(it.joinToString("/")) }
                ?: return null

            val entryHtml = NEW_RESPONSE.findAll(mainScript).filter { code[it.range.first] }
                .mapNotNull { r -> literals.indexAt(r.range.last + 1)?.let { values[it] } }
                .firstOrNull { HTML_SCRIPT.containsMatchIn(it) }

            return ProtocolServedPage(
                host = host,
                aliasHosts = aliases - host,
                prefix = m.groupValues[2],
                entryName = m.groupValues[3],
                entryHtml = entryHtml,
                resourceDir = resourceDir,
                userAgentSuffix = userAgentSuffix(mainScript, code, literals),
            )
        }

        // one distinct https *.html url among main's strings is the window's url. with several, the ones main hands
        // to `url:` / loadURL(...) by name or literal win.
        private fun pickEntryUrl(src: String, literals: JsLiterals, values: List<String?>): String? {
            val candidates = values.indices.filter { values[it]?.let(ENTRY_URL::matches) == true }
            val distinct = candidates.mapNotNull { values[it] }.distinct()
            if (distinct.size <= 1) return distinct.firstOrNull()
            val loaded = candidates.filter { i ->
                val lit = literals.all[i]
                val before = src.substring(0, lit.start).trimEnd()
                val name = literals.bindingName(i)
                before.endsWith("loadURL(") || before.endsWith("url:") ||
                    (name != null && Regex("""(?:loadURL\(\s*|\burl\s*:\s*)${Regex.escape(name)}(?![\w$])""").containsMatchIn(src))
            }.mapNotNull { values[it] }.distinct()
            return loaded.singleOrNull()
        }

        private fun userAgentSuffix(src: String, code: BooleanArray, literals: JsLiterals): String? {
            val at = UA_FALLBACK.findAll(src).firstOrNull { code[it.range.first] } ?: return null
            val idx = literals.indexAt(at.range.last + 1) ?: return null
            val raw = literals.all[idx].body
            val rest = UA_TEMPLATE.matchEntire(raw)?.groupValues?.get(1) ?: return null
            if (rest.contains("\${")) return null
            return unescapeJs(rest).trim().takeIf { it.isNotEmpty() }
        }

        // "a/./b" -> "a/b"; null when it climbs out or is empty.
        private fun normalizeRelative(path: String): String? {
            val out = ArrayDeque<String>()
            for (seg in path.replace('\\', '/').split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> return null
                    else -> out.addLast(seg)
                }
            }
            return out.joinToString("/").takeIf { it.isNotEmpty() }
        }
    }
}

// string and template literals of a script, with `${name}` substitutions resolved against the nearest earlier
// `name = <literal>` binding. minifiers reuse short names across scopes, so "nearest earlier" rather than "any".
internal class JsLiterals(private val src: String, code: BooleanArray) {
    // body is the raw text between the quotes.
    class Lit(val start: Int, val quote: Char, val body: String)

    val all: List<Lit>
    private val names: List<String?>
    // literal indices bound to each name, in source order.
    private val byName: Map<String, List<Int>>
    private val memo = HashMap<Int, String?>()

    init {
        val lits = mutableListOf<Lit>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if ((c == '"' || c == '\'' || c == '`') && !code[i] && (i == 0 || code[i - 1])) {
                val end = literalEnd(i)
                lits += Lit(i, c, src.substring(i + 1, maxOf(i + 1, end - 1)))
                i = end
            } else {
                i++
            }
        }
        all = lits
        names = lits.map { bindingNameAt(it.start) }
        byName = names.indices.filter { names[it] != null }.groupBy { names[it]!! }
    }

    fun indexAt(pos: Int): Int? = all.indexOfFirst { it.start == pos }.takeIf { it >= 0 }

    fun bindingName(index: Int): String? = names[index]

    fun value(index: Int, depth: Int = 0): String? {
        if (depth > 8) return null
        memo[index]?.let { return it }
        if (memo.containsKey(index)) return null
        // a binding that reaches itself resolves to null.
        memo[index] = null
        val lit = all[index]
        val result = if (lit.quote != '`') unescapeJs(lit.body) else resolveTemplate(lit.body, lit.start, depth)
        memo[index] = result
        return result
    }

    private fun resolveTemplate(body: String, at: Int, depth: Int): String? {
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            if (body[i] == '\\' && i + 1 < body.length) {
                out.append(body, i, i + 2)
                i += 2
                continue
            }
            if (body[i] == '$' && i + 1 < body.length && body[i + 1] == '{') {
                val close = body.indexOf('}', i + 2)
                if (close < 0) return null
                val name = body.substring(i + 2, close).trim()
                if (!IDENT.matches(name)) return null
                val bound = byName[name]?.lastOrNull { all[it].start < at } ?: return null
                val v = value(bound, depth + 1) ?: return null
                // already cooked; re-escape so the final unescape leaves it alone.
                out.append(v.replace("\\", "\\\\"))
                i = close + 1
                continue
            }
            out.append(body[i])
            i++
        }
        return unescapeJs(out.toString())
    }

    // `name = "..."` / `name="..."`; not `==`, `=>`, `<=` etc, and not a member (`a.b = "..."`).
    private fun bindingNameAt(litStart: Int): String? {
        var k = litStart - 1
        while (k >= 0 && src[k].isWhitespace()) k--
        if (k < 0 || src[k] != '=') return null
        if (k > 0 && src[k - 1] in "=!<>+-*/%&|^?") return null
        k--
        while (k >= 0 && src[k].isWhitespace()) k--
        val end = k + 1
        while (k >= 0 && (src[k].isLetterOrDigit() || src[k] == '_' || src[k] == '$')) k--
        val name = src.substring(k + 1, end)
        if (!IDENT.matches(name)) return null
        if (k >= 0 && src[k] == '.') return null
        return name
    }

    private fun literalEnd(start: Int): Int {
        val quote = src[start]
        var j = start + 1
        while (j < src.length && src[j] != quote) {
            if (src[j] == '\\') j++
            j++
        }
        return minOf(j + 1, src.length)
    }

    private companion object {
        val IDENT = Regex("""[A-Za-z_$][\w$]*""")
    }
}

// cooked value of a js string literal body.
internal fun unescapeJs(s: String): String {
    if (!s.contains('\\')) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i + 1 >= s.length) {
            out.append(c)
            i++
            continue
        }
        val n = s[i + 1]
        i += 2
        when (n) {
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'v' -> out.append('\u000B')
            '0' -> out.append('\u0000')
            '\n' -> Unit
            '\r' -> if (i < s.length && s[i] == '\n') i++
            'x' -> {
                val hex = s.substring(i, minOf(i + 2, s.length))
                hex.toIntOrNull(16)?.takeIf { hex.length == 2 }?.let { out.append(it.toChar()); i += 2 } ?: out.append('x')
            }
            'u' -> {
                if (i < s.length && s[i] == '{') {
                    val close = s.indexOf('}', i)
                    val cp = if (close > i) s.substring(i + 1, close).toIntOrNull(16) else null
                    if (cp != null) {
                        out.appendCodePoint(cp)
                        i = close + 1
                    } else {
                        out.append('u')
                    }
                } else {
                    val hex = s.substring(i, minOf(i + 4, s.length))
                    hex.toIntOrNull(16)?.takeIf { hex.length == 4 }?.let { out.append(it.toChar()); i += 4 } ?: out.append('u')
                }
            }
            else -> out.append(n)
        }
    }
    return out.toString()
}
