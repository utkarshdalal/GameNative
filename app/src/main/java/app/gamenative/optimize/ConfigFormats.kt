package app.gamenative.optimize

import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * A pattern whose \w, \s and \d cover Unicode on every runtime. The desktop JVM, where the unit tests run,
 * needs the flag for that. Android always matches that way and refuses the flag, inline or as an argument.
 */
private fun unicodeRegex(pattern: String): Regex = try {
    Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS)
} catch (e: IllegalArgumentException) {
    Pattern.compile(pattern)
}.toRegex()

class FormatException(message: String) : Exception(message)

/**
 * Key-level reads and edits of a settings file held as text. Every format flattens the file into a map of keys to
 * values ([entries]); an op addresses one of those keys. Edits replace only the characters of the value, so the rest of
 * the file keeps its bytes.
 */
interface ConfigFormat {
    fun read(text: String, op: FileOp): String?

    /** Applies [ops] in order; an op that cannot be written is left out (see [canApply]). */
    fun apply(text: String, ops: List<FileOp>): String

    /** Every key of [text] with its value. */
    fun entries(text: String): Map<String, String>

    /** The [entries] key [op] addresses in [text]. */
    fun entryKey(text: String, op: FileOp): String

    /** Every [entries] key a write of [op] to [text] changes. */
    fun entryKeys(text: String, op: FileOp): Set<String> = setOf(entryKey(text, op))

    /** Ops with the same group are only writable together, such as the attributes of one element still to be added. */
    fun groupOf(op: FileOp): String = op.key

    /**
     * False when [op] cannot be written to [text]: its key is not in the file and cannot be added where the game reads
     * it, or the value cannot be stored there without changing other keys.
     */
    fun canApply(text: String, op: FileOp): Boolean = try {
        ConfigFormats.applyVerified(this, text, listOf(op))
        true
    } catch (e: FormatException) {
        false
    }
}

object ConfigFormats {
    fun forName(name: String): ConfigFormat? = when (name.lowercase()) {
        "ini" -> IniFormat
        "json" -> JsonFormat
        "xml" -> XmlFormat
        "cfg" -> CfgFormat
        else -> null
    }

    /**
     * The ops of [ops] that can be written to [text]: each one that can be written on its own, then each group of the
     * rest ([ConfigFormat.groupOf]) that can be written together with those.
     */
    fun writableOps(format: ConfigFormat, text: String, ops: List<FileOp>): List<FileOp> {
        val alone = ops.map { format.canApply(text, it) }
        val kept = ops.filterIndexed { i, _ -> alone[i] }.toMutableList()
        val rest = ops.filterIndexed { i, _ -> !alone[i] }.groupBy { format.groupOf(it) }
        for (group in rest.values) {
            if (group.size < 2) continue
            val ok = try {
                applyVerified(format, text, kept + group)
                true
            } catch (e: FormatException) {
                false
            }
            if (ok) kept += group
        }
        return ops.filter { op -> kept.any { it === op } }
    }

    internal fun eolOf(text: String): String = when {
        text.contains("\r\n") -> "\r\n"
        text.contains('\n') -> "\n"
        text.contains('\r') -> "\r"
        else -> "\n"
    }

    /**
     * Applies [ops], then reads every key back: each set key must hold its new value, each deleted one must be gone,
     * and every other key must be unchanged. Throws otherwise.
     */
    fun applyVerified(format: ConfigFormat, text: String, ops: List<FileOp>): String {
        val out = format.apply(text, ops)
        val before = format.entries(text)
        val after = format.entries(out)
        val touched = HashSet<String>()
        for (op in ops.associateBy { it.section to it.key }.values) {
            val now = format.read(out, op)
            if (op.isDelete) {
                if (now != null) throw FormatException("${op.key} is still present")
            } else if (now != op.value) {
                throw FormatException("${op.key} reads back as $now")
            }
            touched += format.entryKeys(text, op)
            touched += format.entryKeys(out, op)
        }
        for ((k, v) in before) if (k !in touched && after[k] != v) throw FormatException("$k changed")
        for (k in after.keys) if (k !in before && k !in touched) throw FormatException("$k appeared")
        return out
    }
}

internal class Line(val start: Int, val end: Int, val breakEnd: Int)

internal object TextLines {
    fun of(text: String): List<Line> {
        val out = ArrayList<Line>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                val br = if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
                out += Line(start, i, br)
                start = br
                i = br
            } else {
                i++
            }
        }
        out += Line(start, text.length, text.length)
        return out
    }

    /** The [start, end) range of [text] between [from] and [to] with surrounding whitespace removed. */
    fun trimmed(text: String, from: Int, to: Int): IntRange {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return s until e
    }

    fun splice(text: String, start: Int, end: Int, with: String): String = text.substring(0, start) + with + text.substring(end)

    /** Removes the line [line] together with one line break: the one before it, else the one after it. */
    fun removeLine(text: String, lines: List<Line>, index: Int): String {
        val line = lines[index]
        return if (index > 0) splice(text, lines[index - 1].end, line.end, "") else splice(text, line.start, line.breakEnd, "")
    }
}

/**
 * `key=value` lines under `[Section]` headers. A key before the first header has an empty section; an op's section
 * and key together address `Section.key`. Lines starting with `;`, `#` or `//` are comments. When a key appears more
 * than once the last occurrence is the one read and written. Keys and sections match exactly, else ignoring case.
 * The section [ANY_SECTION] addresses the key in whatever section holds it: every occurrence is written, the last one
 * is read, and a key the file does not have is never added.
 */
object IniFormat : ConfigFormat {

    const val ANY_SECTION = "*"

    private val HEADER = Regex("^\\[([^\\]]+)]$")
    private val PAIR = Regex("^([^=]+?)\\s*=\\s*(.*)$")

    internal class Entry(val flat: String, val section: String?, val name: String, val line: Int, val valueStart: Int, val valueEnd: Int, val value: String)

    internal class Header(val name: String, val line: Int)

    internal class Doc(val text: String, val lines: List<Line>, val entries: List<Entry>, val headers: List<Header>)

    internal fun parse(text: String, strict: Boolean): Doc? {
        val lines = TextLines.of(text)
        val entries = ArrayList<Entry>()
        val headers = ArrayList<Header>()
        var section: String? = null
        for ((i, l) in lines.withIndex()) {
            val r = TextLines.trimmed(text, l.start, l.end)
            if (r.isEmpty()) continue
            val t = text.substring(r.first, r.last + 1)
            if (t.startsWith(";") || t.startsWith("#") || t.startsWith("//")) continue
            val h = HEADER.matchEntire(t)
            if (h != null) {
                section = h.groupValues[1]
                headers += Header(h.groupValues[1], i)
                continue
            }
            val m = PAIR.matchEntire(t)
            if (m == null || t.startsWith("<") || t.startsWith("{") || t.startsWith("\"")) {
                if (strict) return null
                continue
            }
            val name = m.groupValues[1].trim()
            val g = m.groups[2]!!.range
            val v = TextLines.trimmed(text, r.first + g.first, r.first + g.last + 1)
            val flat = section?.let { "$it.$name" } ?: name
            entries += Entry(flat, section, name, i, v.first, v.last + 1, text.substring(v.first, v.last + 1))
        }
        return Doc(text, lines, entries, headers)
    }

    private fun flatOf(op: FileOp) = if (op.section.isEmpty()) op.key else "${op.section}.${op.key}"

    private fun matches(doc: Doc, op: FileOp): List<Entry> {
        if (op.section == ANY_SECTION) {
            return doc.entries.filter { it.name == op.key }.ifEmpty { doc.entries.filter { it.name.equals(op.key, ignoreCase = true) } }
        }
        val flat = flatOf(op)
        return doc.entries.filter { it.flat == flat }.ifEmpty { doc.entries.filter { it.flat.equals(flat, ignoreCase = true) } }
    }

    private fun doc(text: String) = parse(text, strict = false)!!

    override fun entries(text: String): Map<String, String> = LinkedHashMap<String, String>().apply { doc(text).entries.forEach { put(it.flat, it.value) } }

    override fun entryKey(text: String, op: FileOp): String = matches(doc(text), op).lastOrNull()?.flat ?: flatOf(op)

    override fun entryKeys(text: String, op: FileOp): Set<String> =
        if (op.section == ANY_SECTION) matches(doc(text), op).mapTo(LinkedHashSet()) { it.flat } else setOf(entryKey(text, op))

    override fun read(text: String, op: FileOp): String? = matches(doc(text), op).lastOrNull()?.value

    private fun insertAfter(text: String, doc: Doc, line: Int, newLine: String): String {
        val at = doc.lines[line].end
        return TextLines.splice(text, at, at, ConfigFormats.eolOf(text) + newLine)
    }

    private fun lastContentLine(doc: Doc, from: Int, until: Int): Int? = (until - 1 downTo from).firstOrNull { doc.lines[it].let { l -> l.end > l.start && doc.text.substring(l.start, l.end).isNotBlank() } }

    private fun separator(doc: Doc, entry: Entry?): String {
        entry ?: return "="
        val line = doc.lines[entry.line]
        val lineText = doc.text.substring(line.start, entry.valueStart)
        val eq = lineText.indexOf('=')
        val keyEnd = lineText.substring(0, eq).trimEnd().length
        return lineText.substring(keyEnd)
    }

    private fun create(text: String, doc: Doc, op: FileOp, value: String): String? {
        if (op.section.isEmpty()) {
            val firstHeader = doc.headers.firstOrNull()?.line ?: doc.lines.size
            val preamble = doc.entries.filter { it.section == null }
            if (doc.headers.isNotEmpty() && preamble.isEmpty()) return null
            val line = "${op.key}${separator(doc, preamble.lastOrNull())}$value"
            val after = lastContentLine(doc, 0, firstHeader) ?: return if (text.isEmpty()) line else line + ConfigFormats.eolOf(text) + text
            return insertAfter(text, doc, after, line)
        }
        val header = doc.headers.firstOrNull { it.name == op.section } ?: doc.headers.firstOrNull { it.name.equals(op.section, ignoreCase = true) }
        if (header == null) {
            val eol = ConfigFormats.eolOf(text)
            val block = "[${op.section}]$eol${op.key}=$value"
            val after = lastContentLine(doc, 0, doc.lines.size) ?: return if (text.isEmpty()) block + eol else block + eol + text
            return insertAfter(text, doc, after, eol + block)
        }
        val next = doc.headers.firstOrNull { it.line > header.line }?.line ?: doc.lines.size
        val inSection = doc.entries.filter { it.line in header.line until next }
        val after = lastContentLine(doc, header.line + 1, next) ?: header.line
        return insertAfter(text, doc, after, "${op.key}${separator(doc, inSection.lastOrNull())}$value")
    }

    private fun applyOne(text: String, op: FileOp): String? {
        val doc = doc(text)
        if (op.section == ANY_SECTION) return applyEverywhere(text, doc, op)
        val found = matches(doc, op).lastOrNull()
        if (op.isDelete) return found?.let { TextLines.removeLine(text, doc.lines, it.line) } ?: text
        val value = op.value ?: return null
        if (value.contains('\n') || value.contains('\r') || value != value.trim()) return null
        found ?: return create(text, doc, op, value)
        return TextLines.splice(text, found.valueStart, found.valueEnd, value)
    }

    private fun applyEverywhere(text: String, doc: Doc, op: FileOp): String? {
        val found = matches(doc, op).sortedByDescending { it.line }
        if (op.isDelete) return found.fold(text) { t, e -> TextLines.removeLine(t, TextLines.of(t), e.line) }
        val value = op.value ?: return null
        if (found.isEmpty() || value.contains('\n') || value.contains('\r') || value != value.trim()) return null
        return found.fold(text) { t, e -> TextLines.splice(t, e.valueStart, e.valueEnd, value) }
    }

    override fun apply(text: String, ops: List<FileOp>): String = ops.fold(text) { t, op -> applyOne(t, op) ?: t }
}

/**
 * JSON keys are member paths: object members joined with `.`, array items as `[i]` (`video.presets[0].shadows`). A
 * string keeps its quotes; a number, boolean or null is replaced by the value when it is a JSON literal, else it
 * becomes a string. A file that is not strict JSON is addressed the way [CfgFormat] addresses structured text.
 *
 * Selector keys find a value by content instead of position: `**` is any depth (the node itself included), `[*]` any
 * item of an array, and `[field=text]` the objects (or the array items) whose `field` member is the string `text`. A
 * selector key addresses the first node it reaches in document order, and is never added to a file that lacks it.
 *
 * `path|list|position` addresses a settings entry: `path` ends at the entry's value member, `list` and `position`
 * name the entry's members that hold its allowed values and the value's place in that list (either may be empty).
 * Such a key reads and writes the value in ladder terms: with a list, the value's position in it (the value is
 * written as the list item, and the position member, when present, as the position); without one, `0`/`1` for a
 * boolean and the number itself for a number. A position outside the list, a value of another type, and a string
 * value without a list are not written.
 */
object JsonFormat : ConfigFormat {

    internal sealed class Node(val start: Int, val end: Int)
    internal class Prim(start: Int, end: Int, val string: Boolean, val value: String) : Node(start, end)
    internal class Member(val nameStart: Int, val name: String, val nameEnd: Int, val value: Node)
    internal class Obj(start: Int, end: Int, val members: List<Member>) : Node(start, end)
    internal class Arr(start: Int, end: Int, val items: List<Node>) : Node(start, end)

    private class Parser(val s: String) {
        var i = 0

        fun fail(): Nothing = throw FormatException("not valid json at $i")

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(): Node {
            ws()
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> {
                    val st = i
                    val v = string()
                    Prim(st, i, true, v)
                }
                else -> literal()
            }
        }

        fun obj(): Obj {
            val st = i++
            val members = ArrayList<Member>()
            ws()
            if (i < s.length && s[i] == '}') return Obj(st, ++i, members)
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val ns = i
                val name = string()
                val ne = i
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                members += Member(ns, name, ne, value())
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == '}') return Obj(st, ++i, members)
                fail()
            }
        }

        fun arr(): Arr {
            val st = i++
            val items = ArrayList<Node>()
            ws()
            if (i < s.length && s[i] == ']') return Arr(st, ++i, items)
            while (true) {
                items += value()
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == ']') return Arr(st, ++i, items)
                fail()
            }
        }

        fun string(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                b.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail())
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    c < ' ' -> fail()
                    else -> b.append(c)
                }
            }
        }

        fun literal(): Prim {
            val st = i
            for (word in listOf("true", "false", "null", "NaN", "Infinity", "-Infinity")) {
                if (s.startsWith(word, i)) {
                    i += word.length
                    return Prim(st, i, false, word)
                }
            }
            val m = NUMBER_AT.matchAt(s, i) ?: fail()
            i = m.range.last + 1
            return Prim(st, i, false, m.value)
        }
    }

    private val NUMBER_AT = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private val LITERAL = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?|true|false|null")

    internal fun parse(text: String): Node? = try {
        val p = Parser(text)
        if (text.startsWith("\uFEFF")) p.i = 1
        val root = p.value()
        p.ws()
        if (p.i != text.length) null else root
    } catch (e: FormatException) {
        null
    }

    private fun flatten(node: Node, prefix: String, out: LinkedHashMap<String, Prim>) {
        when (node) {
            is Obj -> {
                val last = node.members.withIndex().associate { it.value.name to it.index }
                node.members.forEachIndexed { i, m -> if (last[m.name] == i) flatten(m.value, if (prefix.isEmpty()) m.name else "$prefix.${m.name}", out) }
            }
            is Arr -> node.items.forEachIndexed { i, item -> flatten(item, "$prefix[$i]", out) }
            is Prim -> out[prefix] = node
        }
    }

    private fun prims(root: Node) = LinkedHashMap<String, Prim>().also { flatten(root, "", it) }

    private sealed class Seg {
        data class Name(val name: String) : Seg()
        data class Index(val index: Int) : Seg()
        data class Match(val field: String, val text: String) : Seg()
        object AnyItem : Seg()
        object AnyDepth : Seg()
    }

    private const val ENTRY_SEPARATOR = '|'

    private fun isSelector(key: String): Boolean =
        ENTRY_SEPARATOR in key || "**" in key || "[*]" in key || SELECTOR_MATCH.containsMatchIn(key)

    private val SELECTOR_MATCH = Regex("\\[[^\\]]*=")

    /** The `.`-separated parts of [key], keeping a `.` inside `[...]`. */
    private fun parts(key: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in key.withIndex()) {
            when (c) {
                '[' -> depth++
                ']' -> depth--
                '.' -> if (depth == 0) {
                    out += key.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += key.substring(start)
        return out
    }

    private fun selectorPath(key: String): List<Seg>? {
        val out = mutableListOf<Seg>()
        for (whole in parts(key)) {
            var part = whole
            if (part.startsWith("**")) {
                out += Seg.AnyDepth
                part = part.substring(2)
                if (part.isEmpty()) continue
                if (!part.startsWith("[")) return null
            }
            val bracket = part.indexOf('[')
            val name = if (bracket < 0) part else part.substring(0, bracket)
            if (name.isNotEmpty()) out += Seg.Name(name) else if (bracket < 0) return null
            var rest = if (bracket < 0) "" else part.substring(bracket)
            while (rest.isNotEmpty()) {
                val close = rest.indexOf(']')
                if (!rest.startsWith("[") || close < 0) return null
                val inner = rest.substring(1, close)
                val eq = inner.indexOf('=')
                out += when {
                    inner == "*" -> Seg.AnyItem
                    eq > 0 -> Seg.Match(inner.substring(0, eq), inner.substring(eq + 1))
                    else -> Seg.Index(inner.toIntOrNull() ?: return null)
                }
                rest = rest.substring(close + 1)
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun member(node: Node?, name: String): Node? = (node as? Obj)?.members?.lastOrNull { it.name == name }?.value

    private fun descendants(node: Node, out: MutableList<Node>) {
        out += node
        when (node) {
            is Obj -> node.members.forEach { descendants(it.value, out) }
            is Arr -> node.items.forEach { descendants(it, out) }
            is Prim -> Unit
        }
    }

    private fun matchesField(node: Node, seg: Seg.Match): Boolean =
        (member(node, seg.field) as? Prim)?.let { it.string && it.value == seg.text } == true

    /** The first node [segs] reach from [root] in document order. */
    private fun resolve(root: Node, segs: List<Seg>): Node? {
        var cur = listOf(root)
        for (seg in segs) {
            cur = cur.flatMap { n ->
                when (seg) {
                    is Seg.Name -> listOfNotNull(member(n, seg.name))
                    is Seg.Index -> listOfNotNull((n as? Arr)?.items?.getOrNull(seg.index))
                    is Seg.AnyItem -> (n as? Arr)?.items.orEmpty()
                    is Seg.Match -> when (n) {
                        is Arr -> n.items.filter { matchesField(it, seg) }
                        is Obj -> listOf(n).filter { matchesField(it, seg) }
                        else -> emptyList()
                    }
                    is Seg.AnyDepth -> ArrayList<Node>().also { descendants(n, it) }
                }
            }.distinctBy { it.start }.sortedBy { it.start }
        }
        return cur.firstOrNull()
    }

    private class Entry(val value: Prim, val list: Arr?, val position: Prim?)

    /** The settings entry a `path|list|position` key addresses in [root]; null for other keys and when it is not there. */
    private fun entry(root: Node, key: String): Entry? {
        val fields = key.split(ENTRY_SEPARATOR)
        if (fields.size !in 2..3) return null
        val segs = selectorPath(fields[0]) ?: return null
        val valueName = (segs.last() as? Seg.Name)?.name ?: return null
        val owner = (if (segs.size == 1) root else resolve(root, segs.dropLast(1))) as? Obj ?: return null
        val value = member(owner, valueName) as? Prim ?: return null
        val list = fields[1].takeIf { it.isNotEmpty() }?.let { member(owner, it) as? Arr }?.takeIf { a -> a.items.all { it is Prim } }
        val position = fields.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { member(owner, it) as? Prim }
            ?.takeIf { !it.string && INTEGER.matches(it.value) }
        return Entry(value, list, position)
    }

    private val INTEGER = Regex("0|[1-9][0-9]*")

    private fun same(a: Prim, b: Prim): Boolean {
        if (a.string != b.string) return false
        if (a.value == b.value) return true
        if (a.string) return false
        val x = a.value.toDoubleOrNull() ?: return false
        return x == b.value.toDoubleOrNull()
    }

    private fun isBoolean(p: Prim) = !p.string && (p.value == "true" || p.value == "false")

    private fun isNumber(p: Prim) = !p.string && NUMBER_AT.matchEntire(p.value) != null

    private fun readEntry(e: Entry): String? = when {
        e.list != null -> e.list.items.indexOfFirst { same(it as Prim, e.value) }.takeIf { it >= 0 }?.toString()
        isBoolean(e.value) -> if (e.value.value == "true") "1" else "0"
        isNumber(e.value) -> e.value.value
        else -> null
    }

    private fun writeEntry(text: String, e: Entry, value: String): String? {
        val edits = ArrayList<Triple<Int, Int, String>>()
        when {
            e.list != null -> {
                val at = value.takeIf { INTEGER.matches(it) }?.toIntOrNull()?.takeIf { it in e.list.items.indices } ?: return null
                val item = e.list.items[at] as Prim
                edits += Triple(e.value.start, e.value.end, text.substring(item.start, item.end))
                e.position?.let { edits += Triple(it.start, it.end, at.toString()) }
            }
            isBoolean(e.value) -> {
                val literal = when (value) {
                    "1" -> "true"
                    "0" -> "false"
                    else -> return null
                }
                edits += Triple(e.value.start, e.value.end, literal)
            }
            isNumber(e.value) -> {
                if (NUMBER_AT.matchEntire(value) == null) return null
                edits += Triple(e.value.start, e.value.end, value)
            }
            else -> return null
        }
        return edits.sortedByDescending { it.first }.fold(text) { t, (s, end, v) -> TextLines.splice(t, s, end, v) }
    }

    /** The flat keys of [nodes] in [root]. */
    private fun flatKeys(root: Node, nodes: List<Prim>): Set<String> {
        val starts = nodes.map { it.start }.toSet()
        return prims(root).filterValues { it.start in starts }.keys
    }

    private fun selectorPrim(root: Node, key: String): Prim? = selectorPath(key)?.let { resolve(root, it) } as? Prim

    private fun path(key: String): List<Seg>? {
        val out = mutableListOf<Seg>()
        for (part in key.split('.')) {
            val bracket = part.indexOf('[')
            val name = if (bracket < 0) part else part.substring(0, bracket)
            if (name.isNotEmpty()) out += Seg.Name(name) else if (bracket < 0) return null
            var rest = if (bracket < 0) "" else part.substring(bracket)
            while (rest.isNotEmpty()) {
                val close = rest.indexOf(']')
                if (!rest.startsWith("[") || close < 0) return null
                out += Seg.Index(rest.substring(1, close).toIntOrNull() ?: return null)
                rest = rest.substring(close + 1)
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun quote(v: String): String {
        val b = StringBuilder("\"")
        for (c in v) {
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append("\\u%04x".format(c.code))
                else -> b.append(c)
            }
        }
        return b.append('"').toString()
    }

    private fun encode(value: String, existing: Prim?): String =
        if ((existing == null || !existing.string) && LITERAL.matches(value)) value else quote(value)

    private fun create(text: String, root: Node, key: String, value: String): String? {
        val segs = path(key) ?: return null
        var cur: Node = root
        for ((i, seg) in segs.withIndex()) {
            when (seg) {
                is Seg.Index -> cur = (cur as? Arr)?.items?.getOrNull(seg.index) ?: return null
                is Seg.Name -> {
                    val obj = cur as? Obj ?: return null
                    val m = obj.members.lastOrNull { it.name == seg.name }
                    if (m != null) {
                        cur = m.value
                        continue
                    }
                    val rest = segs.drop(i + 1)
                    if (rest.any { it !is Seg.Name }) return null
                    var inner = encode(value, null)
                    for (r in rest.reversed()) inner = "{${quote((r as Seg.Name).name)}: $inner}"
                    val last = obj.members.lastOrNull()
                    if (last == null) return TextLines.splice(text, obj.start + 1, obj.start + 1, "${quote(seg.name)}: $inner")
                    val before = obj.members.getOrNull(obj.members.size - 2)
                    val lead = text.substring(before?.value?.end?.let { text.indexOf(',', it) + 1 } ?: (obj.start + 1), last.nameStart)
                    val colon = text.substring(last.nameEnd, last.value.start)
                    return TextLines.splice(text, last.value.end, last.value.end, ",$lead${quote(seg.name)}$colon$inner")
                }
                else -> return null
            }
        }
        return null
    }

    private fun delete(text: String, root: Node, key: String): String {
        val segs = path(key) ?: return text
        var cur: Node = root
        for ((i, seg) in segs.withIndex()) {
            when (seg) {
                is Seg.Index -> cur = (cur as? Arr)?.items?.getOrNull(seg.index) ?: return text
                is Seg.Name -> {
                    val obj = cur as? Obj ?: return text
                    val at = obj.members.indexOfLast { it.name == seg.name }
                    if (at < 0) return text
                    if (i < segs.size - 1) {
                        cur = obj.members[at].value
                        continue
                    }
                    val m = obj.members[at]
                    return when {
                        at > 0 -> TextLines.splice(text, obj.members[at - 1].value.end, m.value.end, "")
                        obj.members.size > 1 -> TextLines.splice(text, m.nameStart, obj.members[1].nameStart, "")
                        else -> TextLines.splice(text, obj.start + 1, obj.end - 1, "")
                    }
                }
                else -> return text
            }
        }
        return text
    }

    override fun entries(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val root = parse(text) ?: return CfgFormat.entries(text)
        return prims(root).mapValuesTo(LinkedHashMap()) { it.value.value }
    }

    override fun entryKey(text: String, op: FileOp): String = when {
        text.isNotBlank() && parse(text) == null -> CfgFormat.entryKey(text, op)
        isSelector(op.key) -> entryKeys(text, op).first()
        else -> op.key
    }

    override fun entryKeys(text: String, op: FileOp): Set<String> {
        if (!isSelector(op.key)) return setOf(entryKey(text, op))
        val root = text.takeIf { it.isNotBlank() }?.let { parse(it) } ?: return setOf(op.key)
        val nodes = if (ENTRY_SEPARATOR in op.key) {
            entry(root, op.key)?.let { listOfNotNull(it.value, it.position) }
        } else {
            listOfNotNull(selectorPrim(root, op.key))
        }
        return nodes?.takeIf { it.isNotEmpty() }?.let { flatKeys(root, it) } ?: setOf(op.key)
    }

    override fun read(text: String, op: FileOp): String? {
        if (text.isBlank()) return null
        val root = parse(text) ?: return CfgFormat.read(text, op)
        if (isSelector(op.key)) {
            return if (ENTRY_SEPARATOR in op.key) entry(root, op.key)?.let { readEntry(it) } else selectorPrim(root, op.key)?.value
        }
        return prims(root)[op.key]?.value
    }

    private fun applySelector(text: String, root: Node, op: FileOp): String? {
        if (op.isDelete) return text
        val value = op.value ?: return null
        if (ENTRY_SEPARATOR in op.key) return entry(root, op.key)?.let { writeEntry(text, it, value) }
        val existing = selectorPrim(root, op.key) ?: return null
        return TextLines.splice(text, existing.start, existing.end, encode(value, existing))
    }

    private fun applyOne(text: String, op: FileOp): String? {
        val source = if (text.isBlank()) "{}" else text
        val root = parse(source) ?: return CfgFormat.apply(text, listOf(op))
        if (isSelector(op.key)) return applySelector(source, root, op)
        if (op.isDelete) return delete(source, root, op.key)
        val value = op.value ?: return null
        val existing = prims(root)[op.key] ?: return create(source, root, op.key, value)
        return TextLines.splice(source, existing.start, existing.end, encode(value, existing))
    }

    override fun apply(text: String, ops: List<FileOp>): String {
        val out = ops.fold(text) { t, op -> applyOne(t, op) ?: t }
        return if (text.isBlank() && out != text) out + "\n" else out
    }
}

/**
 * XML keys are element paths from the root element. A child with a `name`, `Name`, `id`, `Id`, `key` or `Key`
 * attribute (the first of those it has) is `tag[value]`; other children are counted per tag in document order, the
 * first as `tag` and the next as `tag[1]`, `tag[2]` and so on. `path@attr` is an attribute. `path` alone is the
 * element's value: its text when it has any, else its one attribute besides the keying one, else its `value`
 * attribute. Values are read and written as they appear in the file; when two elements share a path the last one
 * counts. A missing attribute is added to its element. A missing element is added, with every attribute the step
 * writes on it, when it is a keyed child (keyed by the attribute the file's other elements of that tag use) or the
 * first child of its tag; an element addressed only by its value, or by position, is never added.
 */
object XmlFormat : ConfigFormat {

    private val TAG = unicodeRegex("<(/?)([A-Za-z_][\\w:.\\-]*)((?:\\s+[\\w:.\\-]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'))*)\\s*(/?)>")
    private val ATTR = unicodeRegex("([\\w:.\\-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val HIDDEN = Regex("<\\?[^>]*\\?>|<!--[\\s\\S]*?-->")
    private val KEY_ATTRS = listOf("name", "Name", "id", "Id", "key", "Key")
    private val ENTITY = Regex("&(\\w+|#\\d+|#x[0-9a-fA-F]+);")
    private val SEGMENT = Regex("^([A-Za-z_][\\w:.\\-]*)(?:\\[(.*)])?$")

    internal class Attr(val name: String, val start: Int, val end: Int, val quote: Char, val value: String)

    internal class Element(
        val id: Int,
        val path: String,
        val tag: String,
        val parent: Element?,
        val start: Int,
        val tagEnd: Int,
        val attrsEnd: Int,
        val end: Int,
        val selfClosing: Boolean,
        val attrs: Map<String, Attr>,
        val ident: String?,
        val rest: Map<String, Attr>,
    ) {
        val children = ArrayList<Element>()
        var closeStart = -1
        var closeEnd = -1
    }

    internal sealed class Loc(val element: Int, val value: String)
    internal class AttrLoc(element: Int, val attr: Attr) : Loc(element, attr.value)
    internal class TextLoc(element: Int, value: String, val pieces: List<IntRange>) : Loc(element, value)

    internal class Doc(val entries: LinkedHashMap<String, MutableList<Loc>>, val elements: Map<String, List<Element>>, val all: List<Element>)

    internal fun parse(text: String): Doc {
        val keep = StringBuilder()
        val map = ArrayList<Int>()
        var from = 0
        for (h in HIDDEN.findAll(text)) {
            for (i in from until h.range.first) {
                keep.append(text[i])
                map += i
            }
            from = h.range.last + 1
        }
        for (i in from until text.length) {
            keep.append(text[i])
            map += i
        }
        map += text.length
        val t = keep.toString()

        class Frame(val path: String, val element: Element?) {
            val counts = HashMap<String, Int>()
            val pieces = ArrayList<IntRange>()
        }
        val entries = LinkedHashMap<String, MutableList<Loc>>()
        val elements = LinkedHashMap<String, MutableList<Element>>()
        val all = ArrayList<Element>()
        fun put(key: String, loc: Loc) {
            val list = entries.getOrPut(key) { mutableListOf() }
            list.removeAll { it.element == loc.element }
            list += loc
        }
        val stack = ArrayList<Frame>()
        stack += Frame("", null)
        var pos = 0
        for (m in TAG.findAll(t)) {
            if (stack.size > 1) {
                for (l in TextLines.of(t.substring(pos, m.range.first))) {
                    val r = TextLines.trimmed(t, pos + l.start, pos + l.end)
                    if (!r.isEmpty()) stack.last().pieces += r
                }
            }
            pos = m.range.last + 1
            if (m.groupValues[1].isNotEmpty()) {
                if (stack.size > 1) {
                    val f = stack.removeAt(stack.size - 1)
                    f.element!!.closeStart = map[m.range.first]
                    f.element.closeEnd = map[m.range.last + 1]
                    if (f.pieces.isNotEmpty()) {
                        val ranges = f.pieces.map { map[it.first] until map[it.last + 1] }
                        put(f.path, TextLoc(f.element.id, f.pieces.joinToString("") { t.substring(it.first, it.last + 1) }, ranges))
                    }
                }
                continue
            }
            val tag = m.groupValues[2]
            val g3 = m.groups[3]
            val attrs = LinkedHashMap<String, Attr>()
            if (g3 != null && g3.value.isNotEmpty()) {
                for (a in ATTR.findAll(g3.value)) {
                    val vg = a.groups[2] ?: a.groups[3]!!
                    val vs = g3.range.first + vg.range.first
                    val ve = vs + vg.value.length
                    attrs[a.groupValues[1]] = Attr(a.groupValues[1], map[vs], map[ve], t[vs - 1], vg.value)
                }
            }
            val parent = stack.last()
            val ident = KEY_ATTRS.firstOrNull { it in attrs }
            val seg = if (ident != null) {
                "$tag[${attrs[ident]!!.value}]"
            } else {
                val n = parent.counts.getOrDefault(tag, 0)
                parent.counts[tag] = n + 1
                if (n == 0) tag else "$tag[$n]"
            }
            val p = if (parent.path.isEmpty()) seg else "${parent.path}/$seg"
            val rest = attrs.filterKeys { it != ident }
            val tagEnd = map[m.groups[2]!!.range.last + 1]
            val attrsEnd = if (g3 != null && g3.value.isNotEmpty()) map[g3.range.last + 1] else tagEnd
            val selfClosing = m.groupValues[4].isNotEmpty()
            val el = Element(all.size, p, tag, parent.element, map[m.range.first], tagEnd, attrsEnd, map[m.range.last + 1], selfClosing, attrs, ident, rest)
            all += el
            parent.element?.children?.add(el)
            elements.getOrPut(p) { mutableListOf() } += el
            when {
                ident != null && rest.size == 1 -> put(p, AttrLoc(el.id, rest.values.first()))
                ident != null && "value" in rest -> {
                    put(p, AttrLoc(el.id, rest["value"]!!))
                    for ((k, a) in rest) if (k != "value") put("$p@$k", AttrLoc(el.id, a))
                }
                else -> for ((k, a) in rest) put("$p@$k", AttrLoc(el.id, a))
            }
            if (!selfClosing) stack += Frame(p, el)
        }
        return Doc(entries, elements, all)
    }

    override fun entries(text: String): Map<String, String> = parse(text).entries.mapValuesTo(LinkedHashMap()) { it.value.last().value }

    override fun entryKey(text: String, op: FileOp): String = op.key

    override fun read(text: String, op: FileOp): String? = parse(text).entries[op.key]?.lastOrNull()?.value

    /** Ops on attributes of one element that does not exist yet are written together, as one new element. */
    override fun groupOf(op: FileOp): String = op.key.substringBeforeLast('@')

    private fun writable(value: String, quote: Char?): Boolean {
        if (value.contains('<') || value.contains('\n') || value.contains('\r')) return false
        if (quote != null && value.contains(quote)) return false
        return ENTITY.replace(value, "").none { it == '&' }
    }

    /** The element path and attribute of an `path@attr` key; null for value keys. */
    private fun splitAttr(key: String): Pair<String, String>? {
        val at = key.lastIndexOf('@')
        if (at <= 0 || key.indexOf(']', at) >= 0 || key.indexOf('/', at) >= 0) return null
        return key.substring(0, at) to key.substring(at + 1)
    }

    private fun createAttr(text: String, el: Element, name: String, value: String): String? {
        if (name.isEmpty() || name in el.attrs) return null
        if (el.ident != null && (el.rest.size < 2 && "value" !in el.rest || name == "value")) return null
        val quote = el.attrs.values.firstOrNull()?.quote ?: '"'
        if (!writable(value, quote)) return null
        return TextLines.splice(text, el.attrsEnd, el.attrsEnd, " $name=$quote$value$quote")
    }

    /** Path segments, with selectors that may hold a `/`. */
    private fun segments(path: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in path.withIndex()) {
            when (c) {
                '[' -> depth++
                ']' -> depth--
                '/' -> if (depth == 0) {
                    out += path.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += path.substring(start)
        return out
    }

    private fun lineIndent(text: String, at: Int): String? {
        var i = at
        while (i > 0 && text[i - 1] != '\n' && text[i - 1] != '\r') i--
        val lead = text.substring(i, at)
        return lead.takeIf { it.isBlank() }
    }

    private fun indentUnit(text: String, doc: Doc): String {
        for (el in doc.all) {
            val parent = el.parent ?: continue
            val c = lineIndent(text, el.start) ?: continue
            val p = lineIndent(text, parent.start) ?: continue
            if (c.length > p.length && c.startsWith(p)) return c.substring(p.length)
        }
        return "\t"
    }

    /**
     * Adds the element [path] with [attrs] (and any missing elements above it) as the last child of its deepest
     * existing ancestor, indented like the file.
     */
    private fun createElement(text: String, doc: Doc, path: String, attrs: List<Pair<String, String>>): String? {
        if (attrs.isEmpty()) return null
        val segs = segments(path)
        var parent: Element? = null
        var at = 0
        while (at < segs.size) {
            val p = segs.subList(0, at + 1).joinToString("/")
            parent = doc.elements[p]?.singleOrNull() ?: if (doc.elements.containsKey(p)) return null else break
            at++
        }
        if (at == segs.size) return null
        val host = parent ?: return null
        if (host.selfClosing || host.closeStart < 0) return null
        val quote = doc.all.flatMap { it.attrs.values }.firstOrNull()?.quote ?: '"'
        val missing = segs.subList(at, segs.size).mapIndexed { i, s ->
            val first = i == 0
            val m = SEGMENT.matchEntire(s) ?: return null
            val tag = m.groupValues[1]
            val selector = m.groups[2]?.value
            val keyAttr = if (selector == null) {
                if (first && host.children.any { it.tag == tag && it.ident == null }) return null
                null
            } else {
                val siblings = if (first) host.children.filter { it.tag == tag } else emptyList()
                val keyed = (siblings.ifEmpty { doc.all.filter { it.tag == tag } }).mapNotNull { it.ident }
                keyed.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
            }
            if (selector != null && !writable(selector, quote)) return null
            Triple(tag, keyAttr, selector)
        }
        if (attrs.any { !writable(it.second, quote) || it.first.isEmpty() }) return null
        val eol = ConfigFormats.eolOf(text)
        val unit = indentUnit(text, doc)
        val base = host.children.lastOrNull()?.let { lineIndent(text, it.start) } ?: ((lineIndent(text, host.start) ?: "") + unit)
        fun open(i: Int): String {
            val (tag, keyAttr, selector) = missing[i]
            val b = StringBuilder("<").append(tag)
            if (keyAttr != null) b.append(" $keyAttr=$quote$selector$quote")
            if (i == missing.lastIndex) for ((k, v) in attrs) b.append(" $k=$quote$v$quote")
            return b.toString()
        }
        val block = StringBuilder()
        for (i in missing.indices) {
            val indent = base + unit.repeat(i)
            block.append(eol).append(indent).append(open(i)).append(if (i == missing.lastIndex) " />" else ">")
        }
        for (i in missing.lastIndex - 1 downTo 0) block.append(eol).append(base + unit.repeat(i)).append("</${missing[i].first}>")
        val insertAt = host.children.lastOrNull()?.let { if (it.selfClosing) it.end else it.closeEnd } ?: host.end
        var out = TextLines.splice(text, insertAt, insertAt, block.toString())
        if (host.children.isEmpty()) {
            val close = host.closeStart + block.length
            val between = out.substring(insertAt + block.length, close)
            if (between.isBlank()) out = TextLines.splice(out, insertAt + block.length, close, eol + (lineIndent(text, host.start) ?: ""))
        }
        return out
    }

    private fun applyOne(text: String, op: FileOp): String? {
        val doc = parse(text)
        val locs = doc.entries[op.key]?.takeLast(1)
        if (op.isDelete) {
            locs ?: return text
            val loc = locs.single()
            val r = when (loc) {
                is AttrLoc -> {
                    val el = doc.all[loc.element]
                    val prevEnd = el.attrs.values.filter { it.end < loc.attr.start }.maxOfOrNull { it.end + 1 } ?: el.tagEnd
                    prevEnd until loc.attr.end + 1
                }
                is TextLoc -> loc.pieces.first().first until loc.pieces.last().last + 1
            }
            return TextLines.splice(text, r.first, r.last + 1, "")
        }
        val value = op.value ?: return null
        if (locs == null) {
            val (path, name) = splitAttr(op.key) ?: return null
            val el = doc.elements[path]?.singleOrNull() ?: return createElement(text, doc, path, listOf(name to value))
            return createAttr(text, el, name, value)
        }
        return when (val loc = locs.single()) {
            is AttrLoc -> if (writable(value, loc.attr.quote)) TextLines.splice(text, loc.attr.start, loc.attr.end, value) else null
            is TextLoc -> {
                if (loc.pieces.size != 1 || value.isEmpty() || value != value.trim() || !writable(value, null)) return null
                val r = loc.pieces[0]
                if (text.substring(r.first, r.last + 1) != loc.value) return null
                TextLines.splice(text, r.first, r.last + 1, value)
            }
        }
    }

    override fun apply(text: String, ops: List<FileOp>): String {
        var out = text
        val done = BooleanArray(ops.size)
        for ((i, op) in ops.withIndex()) {
            if (done[i]) continue
            done[i] = true
            val split = splitAttr(op.key)
            if (!op.isDelete && op.value != null && split != null) {
                val doc = parse(out)
                if (!doc.elements.containsKey(split.first)) {
                    val group = ops.withIndex().filter { (j, o) -> !done[j] && !o.isDelete && o.value != null && splitAttr(o.key)?.first == split.first }
                    group.forEach { done[it.index] = true }
                    val attrs = (listOf(split.second to op.value) + group.map { splitAttr(it.value.key)!!.second to it.value.value!! })
                        .associate { it }.toList()
                    createElement(out, doc, split.first, attrs)?.let { out = it }
                    continue
                }
            }
            applyOne(out, op)?.let { out = it }
        }
        return out
    }
}

/**
 * Structured text: `name value`, `name "value"`, `name = value`, `"name" "value"`, `name: value`, `seta name "value"`,
 * `Name(value)`, `bind KEY "command"` and `-flag value` entries, with `{ }` blocks. An entry inside named blocks is
 * `Block.Inner.name`; a block opened without a name is `_N`, N being the number of entries above it. A name that
 * repeats is `name#2`, `name#3` and so on from its second occurrence. A block holding a `setting = "X"` entry is keyed
 * by X. Unkeyed lines (`_line_N`) and flags without a value are never written. A key that is not a full path matches
 * the one entry whose path ends with it. A missing key is added only to a file without blocks or `[Section]` lines
 * (at its end it would land in the last section).
 */
object CfgFormat : ConfigFormat {

    private val KV = listOf(
        unicodeRegex("(?i)^(?:seta|set|uset|cvaradd|sv|setb|seti|setf)\\s+\"?([^\\s\"=]+)\"?\\s+\"?(.*?)\"?\\s*;?$"),
        unicodeRegex("^\\+([^\\s\"]+)\\s+\"?(.*?)\"?$"),
        unicodeRegex("^\"([^\"]+)\"\\s*:\\s*(.*?)\\s*,?$"),
        unicodeRegex("^([^=\\s\"<>{}][^=<>{}]*?)\\s*=\\s*(.*?)\\s*[,;]?$"),
        unicodeRegex("^\"([^\"]+)\"\\s+\"(.*)\"$"),
        unicodeRegex("^([A-Za-z_]\\w*)\\s*\\((.*)\\)\\s*;?$"),
        unicodeRegex("^([A-Za-z_][\\w.\\-\\[\\]/:@]*)\\s+\"(.*)\"\\s*;?$"),
        unicodeRegex("^([A-Za-z_][\\w.\\-\\[\\]/:@]*)\\s+([^\\s\"{}=]+)\\s*;?$"),
    )
    private val DECLARATION = unicodeRegex("^!\\w+\\(")
    private val OPEN_NAMED = unicodeRegex("^(?:\"([^\"]+)\"|\\[?\"?([^\\s\"=:{}\\[\\]]+)\"?]?)\\s*[:=]?\\s*\\{$")
    private val PENDING = unicodeRegex("^(?:\"([^\"]+)\"|(\\[[^\\]]+])|([A-Za-z_]\\w*))\\s*[:=]?\\s*$")
    private val BARE = unicodeRegex("^[A-Za-z_]\\w*$")
    private val FLAG = unicodeRegex("^-\\w")
    private val BIND = unicodeRegex("^(bind)\\s+\"?([^\"\\s]+)\"?\\s+\"?(.*?)\"?$")
    private val INDEXED = Regex("(^|\\.)\\[\\d+]\\.")
    private val CLOSERS = setOf("}", "]", "})", "];", "},")
    private val SECTION_LINE = Regex("(?m)^[ \t]*\\[[^\\]\r\n]+][ \t]*\r?$")

    /** [start, end) of the value in the text; null for entries that are never written. */
    internal class Slot(val value: String, val line: Int, val start: Int, val end: Int, val keyStart: Int, val keyEnd: Int, val lineEnd: Int, val writable: Boolean)

    internal class Doc(val lines: List<Line>, val entries: LinkedHashMap<String, Slot>, val blocks: Boolean)

    private fun stripComment(text: String, start: Int, end: Int): Int {
        var e = end
        for (c in listOf(" //", "\t//", " --", "\t--")) {
            val k = text.substring(start, e).indexOf(c)
            if (k > 0 && text.substring(start, start + k).count { it == '"' } % 2 == 0) e = TextLines.trimmed(text, start, start + k).let { if (it.isEmpty()) start else it.last + 1 }
        }
        return e
    }

    private fun shrink(text: String, r: IntRange): IntRange = TextLines.trimmed(text, r.first, r.last + 1)

    internal fun parse(text: String): Doc {
        val lines = TextLines.of(text)
        val d = LinkedHashMap<String, Slot>()
        val stack = ArrayList<String>()
        var pending: String? = null
        var pos = 0
        var blocks = false
        fun put(k: String, slot: Slot) {
            var key = (stack.filter { it.isNotEmpty() } + k).joinToString(".")
            if (key in d) {
                var j = 2
                while ("$key#$j" in d) j++
                key = "$key#$j"
            }
            d[key] = slot
        }
        for ((li, l) in lines.withIndex()) {
            val r0 = TextLines.trimmed(text, l.start, l.end)
            if (r0.isEmpty()) continue
            val s = r0.first
            val first = text.substring(s, r0.last + 1)
            if (first.startsWith("//") || first.startsWith("--") || first.startsWith(";") || first.startsWith("#")) continue
            if (first.startsWith("!") && DECLARATION.containsMatchIn(first)) continue
            val e = stripComment(text, s, r0.last + 1)
            val line = text.substring(s, e)
            if (line == "{" || line == "[" || line.trimEnd(',') == "{" || line.trimEnd(',') == "[") {
                stack += pending ?: if (d.isEmpty() && stack.isEmpty()) "" else "_${d.size}"
                pending = null
                blocks = true
                continue
            }
            if (line.trimEnd(',', ';') in CLOSERS) {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                continue
            }
            val om = OPEN_NAMED.find(line)
            if (om != null) {
                stack += om.groups[1]?.value ?: om.groupValues[2]
                blocks = true
                continue
            }
            val pm = PENDING.find(line)
            if (pm != null && (!BARE.containsMatchIn(line) || line.startsWith("\""))) {
                pending = pm.groups[1]?.value ?: pm.groups[2]?.value ?: pm.groupValues[3]
                continue
            }
            if (line.startsWith("-") && FLAG.containsMatchIn(line)) {
                val toks = Regex("\\S+").findAll(line).toList()
                var i = 0
                while (i < toks.size) {
                    val tk = toks[i]
                    if (tk.value.startsWith("-") && i + 1 < toks.size && !toks[i + 1].value.startsWith("-")) {
                        val v = toks[i + 1]
                        put(tk.value, Slot(v.value, li, s + v.range.first, s + v.range.last + 1, s + tk.range.first, s + tk.range.last + 1, e, true))
                        i += 2
                    } else {
                        put(tk.value, Slot("present", li, 0, 0, s + tk.range.first, s + tk.range.last + 1, e, false))
                        i += 1
                    }
                }
                continue
            }
            val bm = BIND.find(line)
            if (bm != null) {
                val g = bm.groups[3]!!.range
                val ks = bm.groups[2]!!.range
                put("bind ${bm.groupValues[2]}", Slot(bm.groupValues[3], li, s + g.first, s + g.last + 1, s + ks.first, s + ks.last + 1, e, true))
                continue
            }
            var matched = false
            for (rx in KV) {
                val m = rx.find(line) ?: continue
                val kr = shrink(line, m.groups[1]!!.range)
                if (kr.isEmpty()) continue
                var vr = shrink(line, m.groups[2]!!.range).let { if (it.isEmpty()) m.groups[2]!!.range.first until m.groups[2]!!.range.first else it }
                fun v() = line.substring(vr.first, vr.last + 1)
                if (v().length >= 2 && v().startsWith("\"") && v().endsWith("\"") && v().count { it == '"' } == 2) vr = vr.first + 1 until vr.last
                if (v().endsWith(",")) vr = shrink(line, vr.first until vr.last).let { if (it.isEmpty()) vr.first until vr.first else it }
                if (v().length > 1 && v().startsWith("\"") && v().endsWith("\"")) vr = vr.first + 1 until vr.last
                var ks = kr.first
                var ke = kr.last + 1
                while (ks < ke && line[ks] == '"') ks++
                while (ke > ks && line[ke - 1] == '"') ke--
                put(line.substring(ks, ke), Slot(v(), li, s + vr.first, s + vr.last + 1, s + ks, s + ke, e, true))
                matched = true
                break
            }
            if (matched) continue
            put("_line_$pos", Slot(line, li, s, e, s, s, e, false))
            pos++
        }
        var out = d
        val groups = LinkedHashMap<String, String>()
        for ((k, v) in out) if (k.endsWith(".setting")) groups[k.removeSuffix(".setting")] = v.value
        if (groups.isNotEmpty()) {
            val nd = LinkedHashMap<String, Slot>()
            for ((k, v) in out) {
                val g = if ('.' in k) k.substringBeforeLast('.') else null
                if (g != null && g in groups) {
                    if (k.endsWith(".setting")) continue
                    val parent = if ('.' in g) g.substringBeforeLast('.') else ""
                    val nk = (if (parent.isNotEmpty()) "$parent." else "") + groups[g]
                    nd[if (k.substringAfterLast('.').startsWith("variant")) nk else "$nk.${k.substringAfterLast('.')}"] = v
                } else {
                    nd[k] = v
                }
            }
            out = nd
            val nd2 = LinkedHashMap<String, Slot>()
            for ((k, v) in out) {
                val nk = INDEXED.replace(k, "$1")
                nd2[if (nk !in out && nk !in nd2) nk else k] = v
            }
            out = nd2
        }
        return Doc(lines, out, blocks || groups.isNotEmpty())
    }

    private fun locate(doc: Doc, key: String): Pair<String, Slot>? {
        doc.entries[key]?.let { return key to it }
        val ends = doc.entries.entries.filter { it.key.endsWith(".$key") }
        return ends.singleOrNull()?.let { it.key to it.value }
    }

    override fun entries(text: String): Map<String, String> = parse(text).entries.mapValuesTo(LinkedHashMap()) { it.value.value }

    override fun entryKey(text: String, op: FileOp): String = locate(parse(text), op.key)?.first ?: op.key

    override fun read(text: String, op: FileOp): String? = locate(parse(text), op.key)?.second?.value

    private fun create(text: String, doc: Doc, key: String, value: String): String? {
        if (doc.blocks || SECTION_LINE.containsMatchIn(text)) return null
        if (key.startsWith("_line_") || key.startsWith("bind ") || key.startsWith("-") || Regex("#\\d+$").containsMatchIn(key)) return null
        val sample = doc.entries.values.firstOrNull { it.writable && it.start >= it.keyEnd }
        val newLine = if (sample == null) {
            "$key \"$value\""
        } else {
            val l = doc.lines[sample.line]
            text.substring(l.start, sample.keyStart) + key + text.substring(sample.keyEnd, sample.start) + value + text.substring(sample.end, sample.lineEnd)
        }
        val last = (doc.lines.size - 1 downTo 0).firstOrNull { doc.lines[it].let { l -> text.substring(l.start, l.end).isNotBlank() } }
            ?: return if (text.isEmpty()) newLine else newLine + ConfigFormats.eolOf(text) + text
        val at = doc.lines[last].end
        return TextLines.splice(text, at, at, ConfigFormats.eolOf(text) + newLine)
    }

    private fun applyOne(text: String, op: FileOp): String? {
        if (op.key.startsWith("_line_")) return null
        val doc = parse(text)
        val found = locate(doc, op.key)
        if (op.isDelete) {
            val slot = found?.second ?: return text
            return TextLines.removeLine(text, doc.lines, slot.line)
        }
        val value = op.value ?: return null
        if (value.contains('\n') || value.contains('\r')) return null
        val slot = found?.second ?: return create(text, doc, op.key, value)
        if (!slot.writable) return null
        val quoted = slot.start > 0 && text[slot.start - 1] == '"' && slot.end < text.length && text[slot.end] == '"'
        if (quoted && value.contains('"')) return null
        return TextLines.splice(text, slot.start, slot.end, value)
    }

    override fun apply(text: String, ops: List<FileOp>): String = ops.fold(text) { t, op -> applyOne(t, op) ?: t }
}

class TextCodec(val charset: Charset, val bom: ByteArray, val lfOnly: Boolean)

object TextCodecs {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private fun detected(bytes: ByteArray): TextCodec? = when {
        startsWith(bytes, UTF8_BOM) -> TextCodec(Charsets.UTF_8, UTF8_BOM, false)
        startsWith(bytes, UTF16LE_BOM) -> TextCodec(Charsets.UTF_16LE, UTF16LE_BOM, false)
        startsWith(bytes, UTF16BE_BOM) -> TextCodec(Charsets.UTF_16BE, UTF16BE_BOM, false)
        else -> null
    }

    private fun check(encoding: String) {
        if (encoding !in setOf("default", "UTF8UnixLF", "UTF16", "UTF16LE", "UTF16UnixLE")) {
            throw FormatException("unknown encoding $encoding")
        }
    }

    fun codecFor(bytes: ByteArray, encoding: String): TextCodec {
        check(encoding)
        val bom = detected(bytes)
        return when (encoding) {
            "UTF8UnixLF" -> TextCodec(Charsets.UTF_8, if (bom?.charset == Charsets.UTF_8) UTF8_BOM else ByteArray(0), true)
            "UTF16" -> if (bom != null && bom.charset == Charsets.UTF_16BE) {
                bom
            } else {
                TextCodec(Charsets.UTF_16LE, if (bom?.charset == Charsets.UTF_16LE) UTF16LE_BOM else ByteArray(0), false)
            }
            "UTF16LE" -> TextCodec(Charsets.UTF_16LE, if (bom?.charset == Charsets.UTF_16LE) UTF16LE_BOM else ByteArray(0), false)
            "UTF16UnixLE" -> TextCodec(Charsets.UTF_16LE, if (bom?.charset == Charsets.UTF_16LE) UTF16LE_BOM else ByteArray(0), true)
            else -> bom ?: TextCodec(Charsets.UTF_8, ByteArray(0), false)
        }
    }

    fun forNewFile(encoding: String): TextCodec {
        check(encoding)
        return when (encoding) {
            "UTF16" -> TextCodec(Charsets.UTF_16LE, UTF16LE_BOM, false)
            "UTF16LE" -> TextCodec(Charsets.UTF_16LE, ByteArray(0), false)
            "UTF16UnixLE" -> TextCodec(Charsets.UTF_16LE, ByteArray(0), true)
            "UTF8UnixLF" -> TextCodec(Charsets.UTF_8, ByteArray(0), true)
            else -> TextCodec(Charsets.UTF_8, ByteArray(0), false)
        }
    }

    fun decode(bytes: ByteArray, codec: TextCodec): String {
        val skip = if (startsWith(bytes, codec.bom) && codec.bom.isNotEmpty()) codec.bom.size else 0
        return String(bytes, skip, bytes.size - skip, codec.charset)
    }

    fun encode(text: String, codec: TextCodec): ByteArray {
        val body = (if (codec.lfOnly) text.replace("\r\n", "\n") else text).toByteArray(codec.charset)
        return codec.bom + body
    }
}
