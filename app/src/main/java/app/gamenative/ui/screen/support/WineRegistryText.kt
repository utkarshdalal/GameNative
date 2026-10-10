package app.gamenative.ui.screen.support

import java.util.Locale

object WineRegistryText {

    data class Change(val name: String, val raw: String?)

    data class Prior(val name: String, val lines: List<String>?)

    data class Merged(val text: String, val sectionExisted: Boolean, val priors: List<Prior>)

    private class Entry(val name: String, val start: Int, val end: Int, val dataAt: Int)

    private class Section(val header: Int, val end: Int)

    private const val ESCAPES = ".......abtnvfr.............e...."
    private const val FILETIME_UNIX_EPOCH = 116444736000000000L

    fun escape(value: String, specials: String): String {
        val out = StringBuilder(value.length + 8)
        for (i in value.indices) {
            val c = value[i]
            val next = value.getOrNull(i + 1)
            when {
                c.code > 127 -> out.append(
                    if (next != null && next.isHexDigit()) String.format(Locale.ROOT, "\\x%04x", c.code) else String.format(Locale.ROOT, "\\x%x", c.code),
                )
                c.code < 32 -> {
                    val e = ESCAPES[c.code]
                    out.append(
                        when {
                            e != '.' -> "\\$e"
                            next != null && next in '0'..'7' -> String.format(Locale.ROOT, "\\%03o", c.code)
                            else -> String.format(Locale.ROOT, "\\%o", c.code)
                        },
                    )
                }
                else -> {
                    if (c == '\\' || specials.indexOf(c) >= 0) out.append('\\')
                    out.append(c)
                }
            }
        }
        return out.toString()
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private class Parsed(val text: String, val end: Int)

    private fun parseString(s: String, from: Int, terminator: Char): Parsed? {
        val out = StringBuilder()
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == terminator) return Parsed(out.toString(), i)
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            i++
            if (i >= s.length) return null
            when (val e = s[i]) {
                'a' -> { out.append('\u0007'); i++ }
                'b' -> { out.append('\b'); i++ }
                'e' -> { out.append('\u001b'); i++ }
                'f' -> { out.append('\u000c'); i++ }
                'n' -> { out.append('\n'); i++ }
                'r' -> { out.append('\r'); i++ }
                't' -> { out.append('\t'); i++ }
                'v' -> { out.append('\u000b'); i++ }
                'x' -> {
                    i++
                    var n = 0
                    var digits = 0
                    while (digits < 4 && i < s.length && s[i].isHexDigit()) {
                        n = n * 16 + Character.digit(s[i], 16)
                        i++
                        digits++
                    }
                    if (digits == 0) out.append('x') else out.append(n.toChar())
                }
                in '0'..'7' -> {
                    var n = 0
                    var digits = 0
                    while (digits < 3 && i < s.length && s[i] in '0'..'7') {
                        n = n * 8 + (s[i] - '0')
                        i++
                        digits++
                    }
                    out.append(n.toChar())
                }
                else -> { out.append(e); i++ }
            }
        }
        return null
    }

    fun encodeName(name: String): String = if (name.isEmpty()) "@" else "\"" + escape(name, "\"") + "\""

    fun encodeValue(type: String, data: String): String = when (type) {
        "dword" -> String.format(Locale.ROOT, "dword:%08x", data.toLong(16))
        "qword" -> {
            val v = java.lang.Long.parseUnsignedLong(data, 16)
            "hex(b):" + (0 until 8).joinToString(",") { String.format(Locale.ROOT, "%02x", (v ushr (8 * it)) and 0xff) }
        }
        "binary" -> "hex:" + data.lowercase(Locale.ROOT).chunked(2).joinToString(",")
        "string" -> "\"" + escape(data, "\"") + "\""
        else -> throw IllegalArgumentException("unsupported registry type $type")
    }

    fun canonical(raw: String): String {
        val joined = raw.replace(Regex("\\\\\\r?\\n[ \\t]*"), "").trim()
        return when {
            joined.startsWith("\"") -> "sz:" + (parseString(joined, 1, '"')?.text ?: joined)
            joined.startsWith("dword:", ignoreCase = true) -> "dword:" + (joined.substring(6).trim().toLongOrNull(16) ?: joined)
            joined.startsWith("hex", ignoreCase = true) -> joined.lowercase(Locale.ROOT).replace(Regex("[\\s]"), "")
            else -> joined
        }
    }

    private fun headerKey(line: String): String? {
        if (!line.startsWith("[")) return null
        return parseString(line, 1, ']')?.text
    }

    private fun sections(lines: List<String>): List<Pair<String, Int>> =
        lines.mapIndexedNotNull { index, line -> headerKey(line)?.let { it to index } }

    private fun findSection(lines: List<String>, key: String): Section? {
        val all = sections(lines)
        val at = all.indexOfFirst { it.first.equals(key, ignoreCase = true) }
        if (at < 0) return null
        val end = all.getOrNull(at + 1)?.second ?: lines.size
        return Section(all[at].second, end)
    }

    private fun entries(lines: List<String>, section: Section): List<Entry> {
        val out = mutableListOf<Entry>()
        var i = section.header + 1
        while (i < section.end) {
            val line = lines[i]
            val parsed = when {
                line.startsWith("@=") -> Parsed("", 0)
                line.startsWith("\"") -> parseString(line, 1, '"')?.takeIf { line.getOrNull(it.end + 1) == '=' }
                else -> null
            }
            var end = i + 1
            if (parsed != null) {
                var last = line
                while (last.endsWith("\\") && end < section.end) {
                    last = lines[end]
                    end++
                }
                out += Entry(parsed.text, i, end, parsed.end + 2)
            }
            i = end
        }
        return out
    }

    private fun contentEnd(lines: List<String>, section: Section): Int {
        var end = section.end
        while (end > section.header + 1 && lines[end - 1].isBlank()) end--
        return end
    }

    fun read(text: String, key: String, names: Collection<String>): Map<String, String?> {
        val lines = text.split('\n')
        val section = findSection(lines, key) ?: return names.associateWith { null }
        val found = entries(lines, section)
        return names.associateWith { name ->
            found.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { e ->
                lines.subList(e.start, e.end).joinToString("\n").substring(e.dataAt)
            }
        }
    }

    private fun sectionHeader(key: String, nowMillis: Long): List<String> {
        val filetime = nowMillis * 10_000L + FILETIME_UNIX_EPOCH
        return listOf(
            "[" + escape(key, "[]") + "] " + (nowMillis / 1000),
            String.format(Locale.ROOT, "#time=%x%08x", filetime ushr 32, filetime and 0xffffffffL),
        )
    }

    private fun appendSection(lines: MutableList<String>, key: String, nowMillis: Long): Section {
        var at = lines.size
        while (at > 0 && lines[at - 1].isEmpty()) at--
        val block = mutableListOf("")
        block += sectionHeader(key, nowMillis)
        lines.addAll(at, block)
        if (at + block.size == lines.size) lines.add("")
        return Section(at + 1, at + block.size)
    }

    private fun write(lines: MutableList<String>, key: String, name: String, newLines: List<String>?, nowMillis: Long) {
        var section = findSection(lines, key)
        if (section == null) {
            if (newLines == null) return
            section = appendSection(lines, key, nowMillis)
        }
        val existing = entries(lines, section).firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            repeat(existing.end - existing.start) { lines.removeAt(existing.start) }
            if (newLines != null) lines.addAll(existing.start, newLines)
        } else if (newLines != null) {
            lines.addAll(contentEnd(lines, section), newLines)
        }
    }

    fun merge(text: String, key: String, changes: List<Change>, nowMillis: Long): Merged {
        val lines = text.split('\n').toMutableList()
        val section = findSection(lines, key)
        val existing = section?.let { entries(lines, it) }.orEmpty()
        val matches = changes.map { change -> existing.firstOrNull { it.name.equals(change.name, ignoreCase = true) } }
        val priors = changes.mapIndexed { index, change ->
            Prior(change.name, matches[index]?.let { lines.subList(it.start, it.end).toList() })
        }
        changes.forEachIndexed { index, change ->
            val name = matches[index]?.name ?: change.name
            write(lines, key, change.name, change.raw?.let { listOf(encodeName(name) + "=" + it) }, nowMillis)
        }
        return Merged(lines.joinToString("\n"), section != null, priors)
    }

    fun restore(text: String, key: String, sectionExisted: Boolean, priors: List<Prior>, nowMillis: Long): String {
        val lines = text.split('\n').toMutableList()
        priors.forEach { write(lines, key, it.name, it.lines, nowMillis) }
        if (!sectionExisted) {
            val section = findSection(lines, key)
            if (section != null && entries(lines, section).isEmpty()) {
                var start = section.header
                if (start > 0 && lines[start - 1].isEmpty()) start--
                var end = contentEnd(lines, section)
                if (end < section.end && start == section.header) end++
                repeat(end - start) { lines.removeAt(start) }
            }
        }
        return lines.joinToString("\n")
    }
}
