package app.gamenative.theme.io

import app.gamenative.theme.model.Breakpoint
import app.gamenative.theme.model.ManifestEntry
import app.gamenative.theme.model.Orientation
import app.gamenative.theme.model.SourceLoc
import app.gamenative.theme.model.ThemeLoadError
import app.gamenative.theme.model.ThemeLoadResult
import app.gamenative.theme.model.ThemeTree
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
 * Loads a Theme folder: reads manifest.xml (if present), parses theme.xml,
 * resolves <include src="..."/> and <variables ref="..."/>, and returns a ThemeTree
 * preserving file and line information for diagnostics.
 */
class ThemeLoader {

    /** Load a theme from the given folder path. */
    fun load(themeDirPath: String): ThemeLoadResult {
        val errors = mutableListOf<ThemeLoadError>()
        val themeDir = Paths.get(themeDirPath).normalize().toFile()
        if (!themeDir.exists() || !themeDir.isDirectory) {
            return ThemeLoadResult.Failure(listOf(ThemeLoadError(
                code = "THEME_DIR_NOT_FOUND",
                message = "Theme directory not found: ${themeDirPath}",
                source = null,
            )))
        }

        val manifestEntry = readManifestEntry(themeDir, errors)
        val themeXmlPath = resolvePath(themeDir, manifestEntry?.themePath ?: "theme.xml")
        val canonicalRoot = themeDir.canonicalPath
        val includeResolver = IncludeResolver(canonicalRoot)
        val parsed = includeResolver.parseWithIncludes(themeXmlPath.canonicalPath)
        errors += parsed.errors
        val root = parsed.root
        if (root == null) {
            return ThemeLoadResult.Failure(errors.ifEmpty { listOf(ThemeLoadError(
                code = "THEME_XML_MISSING",
                message = "Failed to parse theme XML at ${themeXmlPath}",
                source = SourceLoc(themeXmlPath.absolutePath),
            )) })
        }

        // Gather variables and breakpoints: external (from manifest entry) + inline <variables> + any <variables ref="..."/> nodes.
        val variables = LinkedHashMap<String, String>() // maintain insertion order; last writer wins on put
        val breakpoints = mutableListOf<Breakpoint>()

        // 1) External variables from manifest entry
        manifestEntry?.variablesPath?.let { varRel ->
            val varFile = resolvePath(themeDir, varRel)
            if (varFile.exists()) {
                val parsed = parseVariablesFile(varFile, errors)
                variables.putAll(parsed.variables)
                breakpoints.addAll(parsed.breakpoints)
            } else {
                errors += ThemeLoadError(
                    code = "VARIABLES_FILE_NOT_FOUND",
                    message = "variables.xml not found: ${varFile}",
                    source = SourceLoc(varFile.absolutePath),
                )
            }
        }

        // 2) Inline variables and variables ref inside theme tree
        collectVariablesFromTree(root, themeDir, variables, breakpoints, errors)

        val tree = ThemeTree(
            rootDir = themeDir.absolutePath,
            manifestEntry = manifestEntry,
            themeXml = root,
            variables = variables,
            breakpoints = breakpoints,
        )
        return if (errors.isEmpty()) ThemeLoadResult.Success(tree) else ThemeLoadResult.Failure(errors)
    }

    // --- Manifest parsing ---

    private fun readManifestEntry(themeDir: File, errors: MutableList<ThemeLoadError>): ManifestEntry? {
        val manifestFile = File(themeDir, "manifest.xml")
        if (!manifestFile.exists()) return null
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            try { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
        }
        var result: ManifestEntry? = null
        try {
            val parser = factory.newSAXParser()
            var inManifest = false
            parser.parse(InputSource(manifestFile.inputStream()), object : DefaultHandler() {
                private var loc: Locator? = null
                override fun setDocumentLocator(locator: Locator?) { this.loc = locator }
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    when (qName) {
                        "manifest" -> inManifest = true
                        "theme" -> if (inManifest) {
                            val source = attributes.getValue("source")
                            val vars = attributes.getValue("variables")
                            val src = SourceLoc(manifestFile.absolutePath, loc?.lineNumber, loc?.columnNumber)
                            if (source != null) {
                                result = ManifestEntry(themePath = source, variablesPath = vars, source = src)
                            } else {
                                errors += ThemeLoadError(
                                    code = "MANIFEST_THEME_MISSING_SOURCE",
                                    message = "<theme> in manifest.xml is missing required 'source' attribute",
                                    source = src,
                                )
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            errors += ThemeLoadError(
                code = "MANIFEST_PARSE_ERROR",
                message = "Failed to parse manifest.xml: ${e.message}",
                source = SourceLoc(manifestFile.absolutePath),
            )
        }
        return result
    }

    // --- Variables parsing ---

    /** Result of parsing a variables file, containing both base variables and breakpoints. */
    private data class VariablesParseResult(
        val variables: Map<String, String>,
        val breakpoints: List<Breakpoint>
    )

    private fun collectVariablesFromTree(
        root: XmlNode,
        themeDir: File,
        outVars: MutableMap<String, String>,
        outBreakpoints: MutableList<Breakpoint>,
        errors: MutableList<ThemeLoadError>
    ) {
        fun traverse(node: XmlNode) {
            if (node.name.equals("variables", ignoreCase = false)) {
                // Load external referenced variables first (so inline can override if needed)
                val ref = node.attributes["ref"]
                if (!ref.isNullOrBlank()) {
                    val base = node.source?.filePath?.let { File(it).parentFile ?: themeDir } ?: themeDir
                    val refFile = if (ref.startsWith("/")) File(themeDir, ref.removePrefix("/")).canonicalFile else File(base, ref).canonicalFile
                    if (refFile.exists()) {
                        val parsed = parseVariablesFile(refFile, errors)
                        outVars.putAll(parsed.variables)
                        outBreakpoints.addAll(parsed.breakpoints)
                    } else {
                        errors += ThemeLoadError(
                            code = "VARIABLES_REF_NOT_FOUND",
                            message = "Referenced variables file not found: ${ref}",
                            source = node.source,
                        )
                    }
                }
                // Inline variable definitions (top-level vars, not inside breakpoints)
                node.children.filter { it.name == "var" }.forEach { vNode ->
                    val name = vNode.attributes["name"]
                    val value = vNode.attributes["value"] ?: vNode.text
                    if (!name.isNullOrBlank() && value != null) {
                        outVars[name] = value
                    } else {
                        errors += ThemeLoadError(
                            code = "VAR_BAD_DEF",
                            message = "<var> must have name and value",
                            source = vNode.source,
                        )
                    }
                }
                // Inline breakpoint definitions
                node.children.filter { it.name == "breakpoint" }.forEach { bpNode ->
                    val bp = parseBreakpointNode(bpNode, errors)
                    if (bp != null) {
                        outBreakpoints.add(bp)
                    }
                }
            }
            node.children.forEach { traverse(it) }
        }
        traverse(root)
    }

    private fun parseVariablesFile(file: File, errors: MutableList<ThemeLoadError>): VariablesParseResult {
        val variables = LinkedHashMap<String, String>()
        val breakpoints = mutableListOf<Breakpoint>()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            try { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
        }
        try {
            val parser = factory.newSAXParser()
            var inVariables = false
            var inBreakpoint = false
            var currentBreakpointOrientation: Orientation? = null
            var currentBreakpointMinWidth: Int? = null
            var currentBreakpointMaxWidth: Int? = null
            var currentBreakpointVars = LinkedHashMap<String, String>()

            parser.parse(InputSource(file.inputStream()), object : DefaultHandler() {
                private var loc: Locator? = null
                override fun setDocumentLocator(locator: Locator?) { this.loc = locator }

                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    when (qName) {
                        "variables" -> inVariables = true
                        "breakpoint" -> if (inVariables) {
                            inBreakpoint = true
                            currentBreakpointOrientation = Orientation.fromString(attributes.getValue("orientation"))
                            currentBreakpointMinWidth = attributes.getValue("minWidth")?.toIntOrNull()
                            currentBreakpointMaxWidth = attributes.getValue("maxWidth")?.toIntOrNull()
                            currentBreakpointVars = LinkedHashMap()
                        }
                        "var" -> if (inVariables) {
                            val name = attributes.getValue("name")
                            val value = attributes.getValue("value")
                            if (!name.isNullOrBlank() && value != null) {
                                if (inBreakpoint) {
                                    currentBreakpointVars[name] = value
                                } else {
                                    variables[name] = value
                                }
                            }
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "breakpoint" -> if (inBreakpoint) {
                            // Only add if we have variables and at least one condition
                            if (currentBreakpointVars.isNotEmpty() &&
                                (currentBreakpointOrientation != null || currentBreakpointMinWidth != null || currentBreakpointMaxWidth != null)) {
                                breakpoints.add(Breakpoint(
                                    orientation = currentBreakpointOrientation,
                                    minWidth = currentBreakpointMinWidth,
                                    maxWidth = currentBreakpointMaxWidth,
                                    variables = currentBreakpointVars.toMap()
                                ))
                            }
                            inBreakpoint = false
                            currentBreakpointOrientation = null
                            currentBreakpointMinWidth = null
                            currentBreakpointMaxWidth = null
                            currentBreakpointVars = LinkedHashMap()
                        }
                        "variables" -> inVariables = false
                    }
                }
            })
        } catch (e: Exception) {
            errors += ThemeLoadError(
                code = "VARIABLES_PARSE_ERROR",
                message = "Failed to parse variables file '${file.name}': ${e.message}",
                source = SourceLoc(file.absolutePath),
            )
        }
        return VariablesParseResult(variables, breakpoints)
    }

    /** Parse a breakpoint XmlNode from inline theme.xml. */
    private fun parseBreakpointNode(node: XmlNode, errors: MutableList<ThemeLoadError>): Breakpoint? {
        val orientation = Orientation.fromString(node.attributes["orientation"])
        val minWidth = node.attributes["minWidth"]?.toIntOrNull()
        val maxWidth = node.attributes["maxWidth"]?.toIntOrNull()

        // Must have at least one condition
        if (orientation == null && minWidth == null && maxWidth == null) {
            errors += ThemeLoadError(
                code = "BREAKPOINT_NO_CONDITION",
                message = "<breakpoint> must have 'orientation', 'minWidth', or 'maxWidth' attribute",
                source = node.source,
            )
            return null
        }

        val vars = LinkedHashMap<String, String>()
        node.children.filter { it.name == "var" }.forEach { vNode ->
            val name = vNode.attributes["name"]
            val value = vNode.attributes["value"] ?: vNode.text
            if (!name.isNullOrBlank() && value != null) {
                vars[name] = value
            }
        }

        if (vars.isEmpty()) {
            // Empty breakpoint - not an error, just skip it
            return null
        }

        return Breakpoint(
            orientation = orientation,
            minWidth = minWidth,
            maxWidth = maxWidth,
            variables = vars
        )
    }

    // --- Utils ---

    private fun resolvePath(baseDir: File, raw: String): File {
        return if (raw.startsWith("/")) {
            // Leading slash means theme root
            File(baseDir, ".") // normalize base
            File(baseDir.absolutePath).parentFile?.let { themeRootParent ->
                // But we actually have themeDir as base; we want themeDir itself.
                // Simpler: compute using Paths
                val root = baseDir.toPath()
                val abs = root.resolve(raw.removePrefix("/")).normalize()
                abs.toFile()
            } ?: File(baseDir, raw.removePrefix("/"))
        } else {
            File(baseDir, raw).canonicalFile
        }
    }
}
