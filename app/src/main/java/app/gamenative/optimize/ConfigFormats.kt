package app.gamenative.optimize

import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.Charset
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

class FormatException(message: String) : Exception(message)

/** Key-level reads and edits of a settings file held as text. Unknown content is kept as it was. */
interface ConfigFormat {
    fun read(text: String, op: FileOp): String?

    fun apply(text: String, ops: List<FileOp>): String
}

object ConfigFormats {
    fun forName(name: String): ConfigFormat? = when (name.lowercase()) {
        "ini" -> IniFormat
        "json" -> JsonFormat
        "xml" -> XmlFormat
        "cfg" -> CfgFormat
        else -> null
    }

    internal fun eolOf(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

    /** Applies [ops], then reads every set key back; throws when one does not hold its new value. */
    fun applyVerified(format: ConfigFormat, text: String, ops: List<FileOp>): String {
        val out = format.apply(text, ops)
        val finalOps = ops.associateBy { it.section to it.key }
        for (op in finalOps.values) {
            val now = format.read(out, op)
            if (op.isDelete) {
                if (now != null) throw FormatException("${op.key} is still present")
            } else if (now != op.value) {
                throw FormatException("${op.key} reads back as $now")
            }
        }
        return out
    }
}

object IniFormat : ConfigFormat {

    private fun header(line: String): String? {
        val t = line.trim()
        if (!t.startsWith("[")) return null
        val end = t.indexOf(']')
        if (end < 1) return null
        return t.substring(1, end).trim()
    }

    private fun keyOf(line: String): String? {
        val t = line.trimStart()
        if (t.isEmpty() || t[0] == ';' || t[0] == '#' || t[0] == '[') return null
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        return line.substring(0, eq).trim().takeIf { it.isNotEmpty() }
    }

    private fun valueOf(line: String): String = line.substring(line.indexOf('=') + 1).trim()

    private fun sectionRange(lines: List<String>, section: String): IntRange? {
        if (section.isEmpty()) {
            val first = lines.indexOfFirst { header(it) != null }
            return 0 until (if (first < 0) lines.size else first)
        }
        val at = lines.indexOfFirst { header(it)?.equals(section, ignoreCase = true) == true }
        if (at < 0) return null
        var end = at + 1
        while (end < lines.size && header(lines[end]) == null) end++
        return (at + 1) until end
    }

    private fun find(lines: List<String>, range: IntRange, key: String): Int? =
        range.firstOrNull { keyOf(lines[it])?.equals(key, ignoreCase = true) == true }

    private fun replaceValue(line: String, value: String): String {
        val eq = line.indexOf('=')
        val after = line.substring(eq + 1)
        val lead = after.takeWhile { it == ' ' || it == '\t' }
        val cr = if (line.endsWith("\r")) "\r" else ""
        return line.substring(0, eq + 1) + lead + value + cr
    }

    override fun read(text: String, op: FileOp): String? {
        val lines = text.split(ConfigFormats.eolOf(text))
        val range = sectionRange(lines, op.section) ?: return null
        return find(lines, range, op.key)?.let { valueOf(lines[it]).removeSuffix("\r").trim() }
    }

    override fun apply(text: String, ops: List<FileOp>): String {
        val eol = ConfigFormats.eolOf(text)
        val lines = text.split(eol).toMutableList()
        for (op in ops) {
            val range = sectionRange(lines, op.section)
            if (op.isDelete) {
                if (range == null) continue
                range.reversed().forEach { i -> if (keyOf(lines[i])?.equals(op.key, ignoreCase = true) == true) lines.removeAt(i) }
                continue
            }
            val value = op.value ?: continue
            if (value.contains('\n') || value.contains('\r')) throw FormatException("multi-line ini value")
            if (range != null) {
                val at = find(lines, range, op.key)
                if (at != null) {
                    lines[at] = replaceValue(lines[at], value)
                } else {
                    var insert = range.last + 1
                    while (insert > range.first && lines[insert - 1].isBlank()) insert--
                    lines.add(insert, "${op.key}=$value")
                }
            } else {
                var insert = lines.size
                while (insert > 0 && lines[insert - 1].isEmpty()) insert--
                val block = mutableListOf<String>()
                if (insert > 0) block += ""
                block += "[${op.section}]"
                block += "${op.key}=$value"
                lines.addAll(insert, block)
            }
        }
        return lines.joinToString(eol)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object JsonFormat : ConfigFormat {

    private sealed class Seg {
        data class Name(val name: String) : Seg()
        data class Index(val index: Int) : Seg()
    }

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    private fun path(key: String): List<Seg> {
        val out = mutableListOf<Seg>()
        for (part in key.split('.')) {
            val bracket = part.indexOf('[')
            val name = if (bracket < 0) part else part.substring(0, bracket)
            if (name.isNotEmpty()) out += Seg.Name(name)
            var rest = if (bracket < 0) "" else part.substring(bracket)
            while (rest.isNotEmpty()) {
                val close = rest.indexOf(']')
                if (!rest.startsWith("[") || close < 0) throw FormatException("bad json path $key")
                out += Seg.Index(rest.substring(1, close).toIntOrNull() ?: throw FormatException("bad json index in $key"))
                rest = rest.substring(close + 1)
            }
            if (name.isEmpty() && bracket < 0) throw FormatException("empty json path segment in $key")
        }
        if (out.isEmpty()) throw FormatException("empty json path")
        return out
    }

    private fun parse(text: String): JsonElement = try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw FormatException("not valid json: ${e.message}")
    }

    private fun get(root: JsonElement, segs: List<Seg>): JsonElement? {
        var cur: JsonElement = root
        for (seg in segs) {
            cur = when (seg) {
                is Seg.Name -> (cur as? JsonObject)?.get(seg.name)
                is Seg.Index -> (cur as? JsonArray)?.getOrNull(seg.index)
            } ?: return null
        }
        return cur
    }

    private fun typed(value: String, existing: JsonElement?): JsonElement {
        if (existing is JsonPrimitive && existing !is JsonNull && existing.isString) return JsonPrimitive(value)
        return when {
            value == "true" -> JsonPrimitive(true)
            value == "false" -> JsonPrimitive(false)
            NUMBER.matches(value) -> JsonUnquotedLiteral(value)
            else -> JsonPrimitive(value)
        }
    }

    private fun set(el: JsonElement?, segs: List<Seg>, i: Int, value: String): JsonElement {
        if (i == segs.size) return typed(value, el)
        return when (val seg = segs[i]) {
            is Seg.Name -> {
                val obj = when (el) {
                    null -> JsonObject(emptyMap())
                    is JsonObject -> el
                    else -> throw FormatException("${seg.name} is not inside an object")
                }
                val map = LinkedHashMap(obj)
                map[seg.name] = set(obj[seg.name], segs, i + 1, value)
                JsonObject(map)
            }
            is Seg.Index -> {
                val arr = el as? JsonArray ?: throw FormatException("index ${seg.index} is not inside an array")
                if (seg.index !in arr.indices) throw FormatException("index ${seg.index} is out of range")
                val list = arr.toMutableList()
                list[seg.index] = set(arr[seg.index], segs, i + 1, value)
                JsonArray(list)
            }
        }
    }

    private fun remove(el: JsonElement, segs: List<Seg>, i: Int): JsonElement {
        val seg = segs[i]
        val last = i == segs.size - 1
        return when (seg) {
            is Seg.Name -> {
                val obj = el as? JsonObject ?: return el
                val child = obj[seg.name] ?: return el
                val map = LinkedHashMap(obj)
                if (last) map.remove(seg.name) else map[seg.name] = remove(child, segs, i + 1)
                JsonObject(map)
            }
            is Seg.Index -> {
                val arr = el as? JsonArray ?: return el
                if (seg.index !in arr.indices) return el
                val list = arr.toMutableList()
                if (last) list.removeAt(seg.index) else list[seg.index] = remove(arr[seg.index], segs, i + 1)
                JsonArray(list)
            }
        }
    }

    private fun indentOf(text: String): String? {
        if (!text.trim().contains('\n')) return null
        val line = text.lines().drop(1).firstOrNull { it.isNotBlank() } ?: return null
        return line.takeWhile { it == ' ' || it == '\t' }.ifEmpty { "  " }
    }

    private fun print(root: JsonElement, indent: String?, eol: String, trailingNewline: Boolean): String {
        val json = if (indent == null) {
            Json
        } else {
            Json {
                prettyPrint = true
                prettyPrintIndent = indent
            }
        }
        var out = json.encodeToString(JsonElement.serializer(), root)
        if (eol == "\r\n") out = out.replace("\n", "\r\n")
        if (trailingNewline && !out.endsWith("\n")) out += eol
        return out
    }

    override fun read(text: String, op: FileOp): String? {
        val el = get(parse(text), path(op.key)) ?: return null
        return when (el) {
            is JsonNull -> "null"
            is JsonPrimitive -> el.content
            else -> el.toString()
        }
    }

    override fun apply(text: String, ops: List<FileOp>): String {
        var root = if (text.isBlank()) JsonObject(emptyMap()) else parse(text)
        for (op in ops) {
            val segs = path(op.key)
            root = if (op.isDelete) remove(root, segs, 0) else set(root, segs, 0, op.value ?: continue)
        }
        if (text.isBlank()) return print(root, "  ", "\n", trailingNewline = true)
        return print(root, indentOf(text), ConfigFormats.eolOf(text), text.endsWith("\n"))
    }
}

/**
 * XML keys are element paths from the root: `Settings/Setting[GrassDensity]@Value` is the Value attribute of the
 * Setting child whose Name attribute is GrassDensity. Without `@attr` the key is the element's text.
 */
object XmlFormat : ConfigFormat {

    private class Seg(val name: String, val selector: String?)

    private class Key(val segs: List<Seg>, val attr: String?)

    private val DECLARATION = Regex("^\\uFEFF?\\s*<\\?xml[^>]*\\?>[ \\t]*(\\r?\\n)?")

    private fun key(raw: String): Key {
        val lastSlash = raw.lastIndexOf('/')
        val at = raw.indexOf('@', lastSlash + 1)
        val path = if (at >= 0) raw.substring(0, at) else raw
        val attr = if (at >= 0) raw.substring(at + 1).takeIf { it.isNotEmpty() } ?: throw FormatException("empty attribute in $raw") else null
        val segs = path.split('/').filter { it.isNotEmpty() }.map { part ->
            val open = part.indexOf('[')
            if (open < 0) {
                Seg(part, null)
            } else {
                if (!part.endsWith("]")) throw FormatException("bad selector in $raw")
                Seg(part.substring(0, open), part.substring(open + 1, part.length - 1))
            }
        }
        return Key(segs, attr)
    }

    private fun parse(text: String): Document = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(text.removePrefix("\uFEFF"))))
    } catch (e: Exception) {
        throw FormatException("not valid xml: ${e.message}")
    }

    private fun matches(el: Element, seg: Seg): Boolean =
        el.tagName.equals(seg.name, ignoreCase = true) && (seg.selector == null || el.getAttribute("Name") == seg.selector)

    private fun children(el: Element): List<Element> {
        val nodes = el.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun find(doc: Document, key: Key, create: Boolean): Element? {
        var cur = doc.documentElement ?: return null
        var start = 0
        if (key.segs.isNotEmpty() && matches(cur, key.segs[0])) start = 1
        for (seg in key.segs.drop(start)) {
            cur = children(cur).firstOrNull { matches(it, seg) } ?: if (create) {
                doc.createElement(seg.name).also { child ->
                    seg.selector?.let { child.setAttribute("Name", it) }
                    cur.appendChild(child)
                }
            } else {
                return null
            }
        }
        return cur
    }

    override fun read(text: String, op: FileOp): String? {
        val k = key(op.key)
        val el = find(parse(text), k, create = false) ?: return null
        return if (k.attr != null) el.getAttributeNode(k.attr)?.value else el.textContent
    }

    override fun apply(text: String, ops: List<FileOp>): String {
        val doc = parse(text)
        for (op in ops) {
            val k = key(op.key)
            if (op.isDelete) {
                val el = find(doc, k, create = false) ?: continue
                if (k.attr != null) {
                    el.removeAttribute(k.attr)
                } else {
                    if (el === doc.documentElement) throw FormatException("cannot delete the root element")
                    el.parentNode.removeChild(el)
                }
            } else {
                val value = op.value ?: continue
                val el = find(doc, k, create = true) ?: throw FormatException("no element for ${op.key}")
                if (k.attr != null) el.setAttribute(k.attr, value) else el.textContent = value
            }
        }
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        val eol = ConfigFormats.eolOf(text)
        val declaration = DECLARATION.find(text)?.value?.removePrefix("\uFEFF").orEmpty()
        var out = declaration + writer.toString().trimStart()
        if (eol == "\r\n") out = out.replace(Regex("(?<!\r)\n"), "\r\n")
        if (text.endsWith("\n") && !out.endsWith("\n")) out += eol
        return out
    }
}

/**
 * Console-variable files: `name value`, `name "value"`, `name = value` and `seta name "value"` lines. `key#N` is the
 * N-th occurrence of key counting from 0. `_line_N` keys are refused, and a missing key is never added to a file that
 * has nested blocks.
 */
object CfgFormat : ConfigFormat {

    private val LINE = Regex("^(\\s*)((?:seta|setu|sets|setc|set)\\s+)?(\"?)([^\\s=\"{}]+)\\3(\\s*=\\s*|\\s+)(.*)$")

    private class Entry(
        val index: Int,
        val indent: String,
        val setWord: String,
        val nameQuote: String,
        val name: String,
        val sep: String,
        val value: String,
        val quoted: Boolean,
        val suffix: String,
    )

    private class Target(val name: String, val occurrence: Int)

    private fun target(key: String): Target {
        if (key.startsWith("_line_")) throw FormatException("line-number keys are not supported")
        val hash = key.lastIndexOf('#')
        if (hash <= 0) return Target(key, 0)
        val n = key.substring(hash + 1).toIntOrNull() ?: return Target(key, 0)
        return Target(key.substring(0, hash), n)
    }

    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("#") || t.startsWith(";")
    }

    private fun parseLine(index: Int, rawLine: String): Entry? {
        val line = rawLine.removeSuffix("\r")
        if (line.isBlank() || isComment(line)) return null
        val m = LINE.matchEntire(line) ?: return null
        val rest = m.groupValues[6]
        if (rest.startsWith("{")) return null
        val sep = m.groupValues[5]
        val quoted = rest.startsWith("\"")
        val value: String
        val suffix: String
        if (quoted) {
            val close = rest.indexOf('"', 1)
            if (close < 0) return null
            value = rest.substring(1, close)
            suffix = rest.substring(close + 1)
        } else if (sep.contains('=')) {
            val comment = rest.indexOf("//")
            val body = if (comment >= 0) rest.substring(0, comment) else rest
            value = body.trimEnd()
            suffix = rest.substring(value.length)
        } else {
            value = rest.takeWhile { !it.isWhitespace() }
            suffix = rest.substring(value.length)
        }
        return Entry(index, m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], sep, value, quoted, suffix)
    }

    private fun entries(lines: List<String>): List<Entry> = lines.mapIndexedNotNull { i, line -> parseLine(i, line) }

    private fun find(lines: List<String>, t: Target): Entry? =
        entries(lines).filter { it.name.equals(t.name, ignoreCase = true) }.getOrNull(t.occurrence)

    private fun render(e: Entry, name: String, value: String, cr: String): String {
        val quote = e.quoted || value.any { it.isWhitespace() } || value.isEmpty()
        val v = if (quote) "\"$value\"" else value
        return e.indent + e.setWord + e.nameQuote + name + e.nameQuote + e.sep + v + e.suffix + cr
    }

    override fun read(text: String, op: FileOp): String? =
        find(text.split(ConfigFormats.eolOf(text)), target(op.key))?.value

    override fun apply(text: String, ops: List<FileOp>): String {
        val eol = ConfigFormats.eolOf(text)
        val lines = text.split(eol).toMutableList()
        for (op in ops) {
            val t = target(op.key)
            val existing = find(lines, t)
            if (op.isDelete) {
                existing?.let { lines.removeAt(it.index) }
                continue
            }
            val value = op.value ?: continue
            if (value.contains('"') || value.contains('\n') || value.contains('\r')) throw FormatException("unsupported cfg value")
            if (existing != null) {
                val cr = if (lines[existing.index].endsWith("\r")) "\r" else ""
                lines[existing.index] = render(existing, existing.name, value, cr)
                continue
            }
            if (t.occurrence > 0) throw FormatException("cannot add occurrence ${t.occurrence} of ${t.name}")
            if (lines.any { it.contains('{') }) throw FormatException("${t.name} is missing from a file with nested blocks")
            val style = entries(lines).firstOrNull() ?: Entry(0, "", "", "", t.name, " ", "", true, "")
            var insert = lines.size
            while (insert > 0 && lines[insert - 1].isEmpty()) insert--
            lines.add(insert, render(Entry(0, "", style.setWord, style.nameQuote, t.name, style.sep, "", style.quoted, ""), t.name, value, ""))
        }
        return lines.joinToString(eol)
    }
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
