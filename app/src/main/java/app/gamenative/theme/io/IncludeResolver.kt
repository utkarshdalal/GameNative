package app.gamenative.theme.io

import app.gamenative.theme.model.SourceLoc
import app.gamenative.theme.model.ThemeLoadError
import app.gamenative.theme.model.XmlNode
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.helpers.DefaultHandler

/**
 * Resolves <include src="..."/> recursively and produces an XmlNode tree while
 * preserving source file and line numbers for diagnostics.
 */
class IncludeResolver(
    private val themeRootDir: String,
) {
    private val factory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        try { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
    }

    data class Result(
        val root: XmlNode?,
        val errors: List<ThemeLoadError>,
    )

    /**
     * Parse the provided file and return an XmlNode root with includes expanded.
     */
    fun parseWithIncludes(filePath: String): Result {
        val errors = mutableListOf<ThemeLoadError>()
        val visited = ArrayDeque<Path>()
        val root = try {
            parseInternal(Paths.get(filePath).normalize(), visited, errors)
        } catch (e: Exception) {
            errors += ThemeLoadError(
                code = "IO_PARSE_ERROR",
                message = "Failed to parse XML '${filePath}': ${e.message}",
                source = SourceLoc(filePath),
            )
            null
        }
        return Result(root, errors)
    }

    private fun parseInternal(file: Path, stack: ArrayDeque<Path>, errors: MutableList<ThemeLoadError>): XmlNode? {
        val themeRoot = Paths.get(themeRootDir).normalize()
        val normalized = file.normalize()
        if (!normalized.startsWith(themeRoot)) {
            errors += ThemeLoadError(
                code = "PATH_ESCAPE",
                message = "Include path escapes theme root: '${normalized}'",
                source = SourceLoc(normalized.toString()),
            )
            return null
        }
        if (!File(normalized.toString()).exists()) {
            errors += ThemeLoadError(
                code = "FILE_NOT_FOUND",
                message = "XML file not found: '${normalized}'",
                source = SourceLoc(normalized.toString()),
            )
            return null
        }
        if (stack.contains(normalized)) {
            errors += ThemeLoadError(
                code = "INCLUDE_CYCLE",
                message = "Cyclic include detected: ${stack.joinToString(" -> ") { it.fileName.toString() }} -> ${normalized.fileName}",
                source = SourceLoc(normalized.toString()),
            )
            return null
        }

        stack.addLast(normalized)
        val parser = factory.newSAXParser()
        val handler = BuildNodeHandler(normalized.toString()) { includeSrc, includeLoc ->
            // Resolve include path relative to current file
            val incPath = resolvePath(normalized.parent ?: themeRoot, includeSrc)
            val included = parseInternal(incPath, stack, errors)
            if (included == null) {
                errors += ThemeLoadError(
                    code = "INCLUDE_LOAD_FAILED",
                    message = "Failed to load include '${includeSrc}' from '${normalized.fileName}'",
                    source = includeLoc,
                )
                emptyList()
            } else {
                // If included root matches parent will be unpacked by caller; here we just attach as a node
                listOf(included)
            }
        }
        File(normalized.toString()).inputStream().use { input: InputStream ->
            parser.parse(InputSource(input), handler)
        }
        stack.removeLast()
        return handler.resultRoot
    }

    private fun resolvePath(baseDir: Path, raw: String): Path {
        return if (raw.startsWith("/")) {
            // Rooted at theme root
            Paths.get(themeRootDir).resolve(raw.removePrefix("/")).normalize()
        } else {
            baseDir.resolve(raw).normalize()
        }
    }

    private class BuildNodeHandler(
        private val filePath: String,
        private val onInclude: (src: String, loc: SourceLoc) -> List<XmlNode>,
    ) : DefaultHandler() {
        private var locator: Locator? = null
        private val nodeStack = ArrayDeque<XmlNodeBuilder>()
        var resultRoot: XmlNode? = null

        override fun setDocumentLocator(locator: Locator?) {
            this.locator = locator
        }

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            val name = qName
            val srcLoc = SourceLoc(
                filePath = filePath,
                line = locator?.lineNumber,
                column = locator?.columnNumber,
            )
            if (name == "include") {
                val includeSrc = attributes.getValue("src")
                if (includeSrc != null) {
                    val includedNodes = onInclude(includeSrc, srcLoc)
                    // Attach included nodes directly into current top as children
                    if (nodeStack.isNotEmpty()) {
                        val top = nodeStack.last()
                        for (n in includedNodes) {
                            // If the included root tag matches the current parent tag, splice its children
                            if (n.name == top.tagName()) {
                                n.children.forEach { child -> top.children.add(child) }
                            } else {
                                top.children.add(n)
                            }
                        }
                    } else {
                        // If include at root level, and multiple nodes returned, wrap them under a synthetic root
                        // However, we do not expect include at root; ignore here (no-op) if no stack exists
                    }
                }
                // Do not push an include node itself
                return
            }
            val attrMap = mutableMapOf<String, String>()
            for (i in 0 until attributes.length) {
                val key = attributes.getQName(i)
                val value = attributes.getValue(i)
                attrMap[key] = value
            }
            nodeStack.addLast(XmlNodeBuilder(name, attrMap, srcLoc))
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (nodeStack.isEmpty()) return
            val text = String(ch, start, length).takeIf { it.isNotBlank() } ?: return
            nodeStack.last().appendText(text)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (qName == "include") return // was handled in startElement
            val finished = nodeStack.removeLast()
            val node = finished.build()
            if (nodeStack.isEmpty()) {
                resultRoot = if (resultRoot == null) node else resultRoot
            } else {
                val parent = nodeStack.last()
                // If the node we just built is a container with same name as parent and was sourced via include,
                // the unwrapping is handled in ThemeLoader when grafting. Here we keep structure as-is.
                parent.children.add(node)
            }
        }

        private class XmlNodeBuilder(
            private val name: String,
            private val attributes: MutableMap<String, String>,
            private val source: SourceLoc,
        ) {
            fun tagName(): String = name
            val children: MutableList<XmlNode> = mutableListOf()
            private var text: String? = null

            fun appendText(t: String) {
                text = ((text ?: "") + t)
            }

            fun build(): XmlNode = XmlNode(
                name = name,
                attributes = attributes.toMap(),
                children = children.toList(),
                text = text?.trim(),
                source = source,
            )
        }
    }
}
