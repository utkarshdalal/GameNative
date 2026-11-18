package app.gamenative.optimize

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Edits the options of Optimize's own application element in a Mesa driconf file and leaves every other element as
 * it was. An element without an executable applies to every process.
 */
object Driconf {

    const val APPLICATION_NAME = "GameNative optimize"

    /** [priors] holds each changed option's value before the merge, null where it was not set. */
    data class Merged(val text: String, val priors: List<Pair<String, String?>>)

    private const val EMPTY = "<driconf>\n</driconf>\n"
    private const val STEP = "    "
    private val APPLICATION = Regex("""<application\b[^>]*(?<!/)>.*?</application\s*>""", RegexOption.DOT_MATCHES_ALL)
    private val OPTION = Regex("""<option\b[^>]*>""")
    private val BARE_DEVICE = Regex("""<device\s*>""")
    private val DRICONF_END = Regex("""</driconf\s*>""")

    private fun attr(tag: String, name: String): String? {
        val m = Regex("""\s$name\s*=\s*(?:"([^"]*)"|'([^']*)')""").find(tag) ?: return null
        val raw = if (m.groups[1] != null) m.groupValues[1] else m.groupValues[2]
        return unescape(raw)
    }

    private fun startTag(element: String): String = element.substring(0, element.indexOf('>') + 1)

    private fun isOurs(element: String, executable: String?): Boolean {
        val tag = startTag(element)
        return attr(tag, "name") == APPLICATION_NAME && attr(tag, "executable") == executable
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun parse(text: String): Document = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(text.removePrefix("\uFEFF"))))
    } catch (e: Exception) {
        throw FormatException("not valid driconf xml: ${e.message}")
    }

    private fun indentAt(text: String, index: Int): String {
        val lineStart = text.lastIndexOf('\n', index - 1) + 1
        val lead = text.substring(lineStart, index)
        return if (lead.isBlank()) lead else ""
    }

    private fun render(executable: String?, options: Map<String, String>, indent: String, eol: String): String = buildString {
        append("<application name=\"").append(escape(APPLICATION_NAME)).append('"')
        if (executable != null) append(" executable=\"").append(escape(executable)).append('"')
        append('>').append(eol)
        options.forEach { (name, value) ->
            append(indent).append(STEP).append("<option name=\"").append(escape(name)).append("\" value=\"")
                .append(escape(value)).append("\" />").append(eol)
        }
        append(indent).append("</application>")
    }

    private fun removeElement(text: String, range: IntRange): String {
        var start = range.first
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        var end = range.last + 1
        if (start == 0 || text[start - 1] == '\n') {
            if (text.startsWith("\r\n", end)) end += 2 else if (text.startsWith("\n", end)) end += 1
        }
        return text.removeRange(start, end)
    }

    private fun insert(text: String, executable: String?, options: Map<String, String>, eol: String): String {
        BARE_DEVICE.find(text)?.let { device ->
            val indent = indentAt(text, device.range.first) + STEP
            val at = device.range.last + 1
            return text.substring(0, at) + eol + indent + render(executable, options, indent, eol) + text.substring(at)
        }
        val end = DRICONF_END.findAll(text).last()
        val lineStart = text.lastIndexOf('\n', end.range.first - 1) + 1
        val atLineStart = text.substring(lineStart, end.range.first).isBlank()
        val at = if (atLineStart) lineStart else end.range.first
        val indent = if (atLineStart) text.substring(lineStart, end.range.first) else ""
        val device = indent + STEP
        val app = device + STEP
        val block = (if (atLineStart) "" else eol) + device + "<device>" + eol + app + render(executable, options, app, eol) +
            eol + device + "</device>" + eol + (if (atLineStart) "" else indent)
        return text.substring(0, at) + block + text.substring(at)
    }

    /**
     * Sets each option of [changes] in the element for [executable], or removes it where the value is null. The
     * element is added when missing and dropped once it holds no option.
     */
    fun merge(text: String, executable: String?, changes: List<Pair<String, String?>>): Merged {
        val source = text.ifBlank { EMPTY }
        if (!DRICONF_END.containsMatchIn(source)) throw FormatException("not a driconf file")
        parse(source)
        val match = APPLICATION.findAll(source).firstOrNull { isOurs(it.value, executable) }
        val options = LinkedHashMap<String, String>()
        match?.let { m ->
            OPTION.findAll(m.value).forEach { o ->
                val name = attr(o.value, "name")
                val value = attr(o.value, "value")
                if (name != null && value != null) options[name] = value
            }
        }
        val priors = changes.map { it.first }.distinct().map { it to options[it] }
        changes.forEach { (name, value) -> if (value == null) options.remove(name) else options[name] = value }
        val eol = ConfigFormats.eolOf(source)
        val out = when {
            match != null && options.isEmpty() -> removeElement(source, match.range)
            match != null -> source.replaceRange(match.range, render(executable, options, indentAt(source, match.range.first), eol))
            options.isEmpty() -> text
            else -> insert(source, executable, options, eol)
        }
        if (out != text) parse(out)
        return Merged(out, priors)
    }

    private fun applications(doc: Document): List<Element> {
        val nodes = doc.getElementsByTagName("application")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    /** The options of the element for [executable], read with an XML parser; null when there is no such element. */
    fun read(text: String, executable: String?): Map<String, String>? {
        val element = applications(parse(text)).firstOrNull {
            it.getAttribute("name") == APPLICATION_NAME &&
                (if (it.hasAttribute("executable")) it.getAttribute("executable") else null) == executable
        } ?: return null
        val nodes = element.getElementsByTagName("option")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .associate { it.getAttribute("name") to it.getAttribute("value") }
    }

    fun applicationCount(text: String): Int = applications(parse(text)).size
}
