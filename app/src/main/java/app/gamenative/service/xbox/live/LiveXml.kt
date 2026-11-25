// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

sealed class XNode

class XText(val text: String) : XNode()

data class XAttribute(val prefix: String, val localName: String, val namespace: String, val value: String)

/**
 * Minimal XML element tree. [prefix] is "" for the default namespace; [namespace] is "" for none.
 * Trees parsed without namespace processing keep prefixes but leave [namespace] empty; look-ups go by local name.
 */
class XElement(val prefix: String, val localName: String, val namespace: String = "") : XNode() {
    val attributes = mutableListOf<XAttribute>()
    val children = mutableListOf<XNode>()

    /** Namespace declarations present on the source element (parsed trees only). */
    val declarations = LinkedHashMap<String, String>()
    var parent: XElement? = null
        private set

    val qualifiedName: String get() = if (prefix.isEmpty()) localName else "$prefix:$localName"

    fun add(node: XNode): XNode {
        if (node is XElement) node.parent = this
        children.add(node)
        return node
    }

    fun el(prefix: String, localName: String, namespace: String, block: XElement.() -> Unit = {}): XElement =
        XElement(prefix, localName, namespace).apply(block).also { add(it) }

    fun attr(localName: String, value: String) {
        attributes.add(XAttribute("", localName, "", value))
    }

    fun attr(prefix: String, localName: String, namespace: String, value: String) {
        attributes.add(XAttribute(prefix, localName, namespace, value))
    }

    fun text(value: String) {
        if (value.isNotEmpty()) add(XText(value))
    }

    fun setText(value: String) {
        children.removeAll { it is XText }
        text(value)
    }

    fun attribute(localName: String): String? = attributes.firstOrNull { it.localName == localName }?.value

    fun elements(): List<XElement> = children.filterIsInstance<XElement>()

    fun child(localName: String): XElement? = elements().firstOrNull { it.localName == localName }

    fun childrenNamed(localName: String): List<XElement> = elements().filter { it.localName == localName }

    fun path(vararg names: String): XElement? = names.fold(this as XElement?) { e, n -> e?.child(n) }

    fun textContent(): String = buildString {
        for (c in children) when (c) {
            is XText -> append(c.text)
            is XElement -> append(c.textContent())
        }
    }

    fun childText(localName: String): String? = child(localName)?.textContent()

    fun descendants(): Sequence<XElement> = sequence {
        for (c in elements()) {
            yield(c)
            yieldAll(c.descendants())
        }
    }

    /** Element carrying `Id`, `wsu:Id` or `id` equal to [id], searching this subtree. */
    fun findById(id: String): XElement? =
        (sequenceOf(this) + descendants()).firstOrNull { e ->
            e.attributes.any { (it.localName == "Id" || it.localName == "id") && it.value == id }
        }

    /** Namespaces in scope from the source document's declarations. */
    fun inScopeNamespaces(): Map<String, String> {
        val chain = generateSequence(this) { it.parent }.toList().asReversed()
        val result = HashMap<String, String>()
        for (e in chain) result.putAll(e.declarations)
        return result
    }
}

/** W3C Exclusive XML Canonicalization 1.0, without comments. */
object ExcC14n {
    fun canonicalize(element: XElement, inclusivePrefixes: Set<String> = emptySet(), exclude: XElement? = null): ByteArray =
        canonicalString(element, inclusivePrefixes, exclude).toByteArray(Charsets.UTF_8)

    fun canonicalString(element: XElement, inclusivePrefixes: Set<String> = emptySet(), exclude: XElement? = null): String =
        StringBuilder().also { write(it, element, emptyMap(), inclusivePrefixes, exclude) }.toString()

    private fun write(
        out: StringBuilder,
        e: XElement,
        rendered: Map<String, String>,
        inclusive: Set<String>,
        exclude: XElement?,
    ) {
        val needed = sortedMapOf<String, String>()
        needed[e.prefix] = e.namespace
        for (a in e.attributes) if (a.prefix.isNotEmpty() && a.prefix != "xml") needed[a.prefix] = a.namespace
        if (inclusive.isNotEmpty()) {
            val scope = e.inScopeNamespaces()
            for (p in inclusive) scope[p]?.let { if (p !in needed) needed[p] = it }
        }
        val output = sortedMapOf<String, String>()
        for ((p, uri) in needed) {
            if (p == "xml") continue
            if ((rendered[p] ?: "") != uri) output[p] = uri
        }
        out.append('<').append(e.qualifiedName)
        for ((p, uri) in output) {
            out.append(if (p.isEmpty()) " xmlns=\"" else " xmlns:$p=\"").append(escapeAttribute(uri)).append('"')
        }
        val attrs = e.attributes.sortedWith(compareBy<XAttribute>({ it.namespace }, { it.localName }))
        for (a in attrs) {
            out.append(' ').append(if (a.prefix.isEmpty()) a.localName else "${a.prefix}:${a.localName}")
                .append("=\"").append(escapeAttribute(a.value)).append('"')
        }
        out.append('>')
        val childRendered = if (output.isEmpty()) rendered else rendered + output
        for (c in e.children) when (c) {
            is XText -> out.append(escapeText(c.text))
            is XElement -> if (c !== exclude) write(out, c, childRendered, inclusive, exclude)
        }
        out.append("</").append(e.qualifiedName).append('>')
    }

    fun escapeText(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\r' -> append("&#xD;")
            else -> append(ch)
        }
    }

    fun escapeAttribute(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '"' -> append("&quot;")
            '\t' -> append("&#x9;")
            '\n' -> append("&#xA;")
            '\r' -> append("&#xD;")
            else -> append(ch)
        }
    }
}

object LiveXml {
    private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

    /** Parses [xml]; with [namespaceAware] false, unbound prefixes (decrypted fragments) are accepted. */
    fun parse(xml: String, namespaceAware: Boolean = true): XElement {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = namespaceAware
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw LiveException("Malformed XML response", e)
        }
        return convert(document.documentElement, namespaceAware)
    }

    private fun splitName(name: String): Pair<String, String> {
        val i = name.indexOf(':')
        return if (i < 0) "" to name else name.substring(0, i) to name.substring(i + 1)
    }

    private fun convert(node: Element, aware: Boolean): XElement {
        val (prefix, local) = if (aware) (node.prefix ?: "") to (node.localName ?: node.nodeName) else splitName(node.nodeName)
        val e = XElement(prefix, local, if (aware) node.namespaceURI ?: "" else "")
        val attrs = node.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            val name = a.nodeName
            if (name == "xmlns" || name.startsWith("xmlns:") || (aware && a.namespaceURI == XMLNS_NS)) {
                e.declarations[if (name == "xmlns") "" else name.substringAfter(':')] = a.nodeValue
                continue
            }
            if (aware) {
                e.attributes.add(XAttribute(a.prefix ?: "", a.localName ?: name, a.namespaceURI ?: "", a.nodeValue))
            } else {
                val (p, l) = splitName(name)
                e.attributes.add(XAttribute(p, l, "", a.nodeValue))
            }
        }
        var child = node.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.ELEMENT_NODE -> e.add(convert(child as Element, aware))
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> e.add(XText(child.nodeValue))
            }
            child = child.nextSibling
        }
        return e
    }

    /** Plain serialization in the shape serde/quick-xml writes: declared namespaces as attributes, empty elements self-closed. */
    fun serialize(e: XElement): String = StringBuilder().also { serialize(it, e) }.toString()

    private fun serialize(out: StringBuilder, e: XElement) {
        out.append('<').append(e.qualifiedName)
        for ((p, uri) in e.declarations) {
            out.append(if (p.isEmpty()) " xmlns=\"" else " xmlns:$p=\"").append(ExcC14n.escapeAttribute(uri)).append('"')
        }
        for (a in e.attributes) {
            out.append(' ').append(if (a.prefix.isEmpty()) a.localName else "${a.prefix}:${a.localName}")
                .append("=\"").append(ExcC14n.escapeAttribute(a.value)).append('"')
        }
        if (e.children.isEmpty()) {
            out.append("/>")
            return
        }
        out.append('>')
        for (c in e.children) when (c) {
            is XText -> out.append(ExcC14n.escapeText(c.text))
            is XElement -> serialize(out, c)
        }
        out.append("</").append(e.qualifiedName).append('>')
    }
}
