package app.gamenative.theme.validate

import app.gamenative.theme.model.SourceLoc
import app.gamenative.theme.model.ThemeEngine
import app.gamenative.theme.model.ThemeTree
import app.gamenative.theme.model.XmlNode
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.nio.file.Path
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/** Severity for validation issues. */
enum class Severity { INFO, WARNING, ERROR }

/** Stable validation codes for programmatic handling and tests. */
enum class ValidationCode {
    // Compatibility
    ENGINE_VERSION_MISMATCH,
    APP_VERSION_OUT_OF_RANGE,
    MANIFEST_MISSING,
    REQUIRED_FIELD_MISSING,

    // Schema & references
    DUPLICATE_ID,
    BAD_TEMPLATE_REF,
    UNKNOWN_STATE_REF,
    UNKNOWN_LAYER_REF,
    INVALID_RANGE,
    INVALID_VALUE,

    // Media/assets
    MISSING_MEDIA_SRC,
    ASSET_NOT_FOUND,
    POSTER_MISSING,
}

/** A single validation finding. */
data class ValidationIssue(
    val code: ValidationCode,
    val severity: Severity,
    val message: String,
    val source: SourceLoc? = null,
)

/** Aggregate validation result. */
data class ValidationResult(val issues: List<ValidationIssue>) {
    fun hasBlocking(): Boolean = issues.any { it.severity == Severity.ERROR }
}

/**
 * Validates a merged ThemeTree (from ThemeLoader) for compatibility and schema basics.
 * Never throws; always returns a list of diagnostics.
 */
object ThemeValidator {

    /** Validate and return issues. Incompatible themes should be blocked before selection. */
    fun validate(tree: ThemeTree, appVersion: String, engineMajor: Int = ThemeEngine.ENGINE_MAJOR): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        // 1) Compatibility gates via manifest.xml
        validateManifest(tree, appVersion, engineMajor, issues)

        // 2) Basic schema / referential integrity on theme.xml
        validateThemeXml(tree, issues)

        return ValidationResult(issues)
    }

    // region Manifest
    private fun validateManifest(tree: ThemeTree, appVersion: String, engineMajor: Int, out: MutableList<ValidationIssue>) {
        val manifestFile = File(tree.rootDir, "manifest.xml")
        if (!manifestFile.exists()) {
            out += ValidationIssue(
                ValidationCode.MANIFEST_MISSING, Severity.ERROR,
                "Theme manifest.xml missing; cannot determine compatibility.",
                SourceLoc(manifestFile.absolutePath)
            )
            return
        }
        var engineVersion: Int? = null
        var minApp: String? = null
        var maxApp: String? = null
        var src: SourceLoc? = null

        try {
            val factory = SAXParserFactory.newInstance()
            val parser = factory.newSAXParser()
            val handler = object : DefaultHandler() {
                private var locator: org.xml.sax.Locator? = null
                override fun setDocumentLocator(locator: org.xml.sax.Locator?) { this.locator = locator }
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    if (qName.equals("manifest", ignoreCase = true)) {
                        src = SourceLoc(manifestFile.absolutePath, locator?.lineNumber, locator?.columnNumber)
                        engineVersion = attributes.getValue("engineVersion")?.toIntOrNull()
                        minApp = attributes.getValue("minAppVersion")
                        maxApp = attributes.getValue("maxAppVersion")
                    }
                }
            }
            parser.parse(manifestFile, handler)
        } catch (e: Exception) {
            out += ValidationIssue(
                ValidationCode.REQUIRED_FIELD_MISSING, Severity.ERROR,
                "Failed to parse manifest.xml: ${e.message}",
                SourceLoc(manifestFile.absolutePath)
            )
            return
        }

        if (engineVersion == null) {
            out += ValidationIssue(
                ValidationCode.REQUIRED_FIELD_MISSING, Severity.ERROR,
                "Manifest must declare engineVersion.", src
            )
        } else if (engineVersion != engineMajor) {
            out += ValidationIssue(
                ValidationCode.ENGINE_VERSION_MISMATCH, Severity.ERROR,
                "Theme engineVersion=$engineVersion does not match app engine=$engineMajor.", src
            )
        }

        if (minApp == null) {
            out += ValidationIssue(
                ValidationCode.REQUIRED_FIELD_MISSING, Severity.ERROR,
                "Manifest must declare minAppVersion.", src
            )
        } else {
            val cmpMin = compareSemVer(appVersion, minApp!!)
            if (cmpMin < 0) {
                out += ValidationIssue(
                    ValidationCode.APP_VERSION_OUT_OF_RANGE, Severity.ERROR,
                    "App version $appVersion is older than required minAppVersion $minApp.", src
                )
            }
        }
        maxApp?.let { max ->
            val cmpMax = compareSemVer(appVersion, max)
            if (cmpMax > 0) {
                out += ValidationIssue(
                    ValidationCode.APP_VERSION_OUT_OF_RANGE, Severity.ERROR,
                    "App version $appVersion exceeds maxAppVersion $max.", src
                )
            }
        }
    }
    // endregion

    // region Theme XML
    private fun validateThemeXml(tree: ThemeTree, out: MutableList<ValidationIssue>) {
        val root = tree.themeXml
        // Collect card/template IDs (support both new "card" and legacy "template" naming)
        val cardIds = LinkedHashSet<String>()
        val cardNodes = mutableListOf<XmlNode>()
        traverse(root) { node ->
            // Support both <card> (new) and <template> (legacy)
            if (node.name.equals("card", ignoreCase = true) || node.name.equals("template", ignoreCase = true)) {
                cardNodes += node
                val id = node.attributes["id"]?.trim().orEmpty()
                if (id.isEmpty()) {
                    out += ValidationIssue(
                        ValidationCode.REQUIRED_FIELD_MISSING, Severity.ERROR,
                        "Card must declare non-empty id.", node.source
                    )
                } else if (!cardIds.add(id)) {
                    out += ValidationIssue(
                        ValidationCode.DUPLICATE_ID, Severity.ERROR,
                        "Duplicate card id '$id'.", node.source
                    )
                }
                validateLayers(node, out)
                validateStatesTransitions(node, out)
            }
            if (node.name.equals("grid", ignoreCase = true)) {
                // Columns > 0, rows optional but if present >0, itemTemplate must exist later
                val columns = node.attributes["columns"]?.toIntOrNull()
                if (columns == null || columns <= 0) {
                    out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Grid columns must be > 0.", node.source)
                }
                node.attributes["rows"]?.toIntOrNull()?.let { if (it <= 0) out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Grid rows must be > 0 when specified.", node.source) }
                val cellW = parseDimensionValue(node.attributes["cellWidth"])
                if (cellW == null || cellW <= 0f) {
                    out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Grid cellWidth must be > 0.", node.source)
                }
                // cellHeight is optional - if specified, must be > 0
                val cellH = parseDimensionValue(node.attributes["cellHeight"])
                if (cellH != null && cellH <= 0f) {
                    out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Grid cellHeight must be > 0 when specified.", node.source)
                }
            }
            if (node.name.equals("carousel", ignoreCase = true)) {
                val itemW = parseDimensionValue(node.attributes["itemWidth"])
                val itemH = parseDimensionValue(node.attributes["itemHeight"])
                if (itemW == null || itemW <= 0f || itemH == null || itemH <= 0f) {
                    out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Carousel itemWidth/itemHeight must be > 0.", node.source)
                }
                node.attributes["pageSize"]?.toIntOrNull()?.let { if (it <= 0) out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Carousel pageSize must be > 0 when specified.", node.source) }
            }
            if (node.name.equals("image", ignoreCase = true)) {
                validateImageNode(tree, node, out)
            }
            if (node.name.equals("video", ignoreCase = true)) {
                validateVideoNode(tree, node, out)
            }
            if (node.name.equals("canvas", ignoreCase = true)) {
                val w = parseDimensionValue(node.attributes["width"])
                val h = parseDimensionValue(node.attributes["height"])
                if (w == null || w <= 0f || h == null || h <= 0f) {
                    out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Canvas width/height must be > 0.", node.source)
                }
            }
            if (node.name.equals("child", ignoreCase = true)) {
                val ref = node.attributes["template"]
                if (ref.isNullOrBlank()) {
                    out += ValidationIssue(ValidationCode.REQUIRED_FIELD_MISSING, Severity.ERROR, "Canvas child requires template attribute.", node.source)
                }
            }
        }

        // After traversal, validate card/template references
        traverse(root) { node ->
            when (node.name.lowercase(Locale.ROOT)) {
                "grid" -> {
                    // Support both "itemCard" (new) and "itemTemplate" (legacy)
                    val ref = node.attributes["itemCard"] ?: node.attributes["itemTemplate"]
                    if (ref.isNullOrBlank() || !cardIds.contains(ref)) {
                        out += ValidationIssue(ValidationCode.BAD_TEMPLATE_REF, Severity.ERROR, "Grid itemCard '$ref' not found.", node.source)
                    }
                }
                "carousel" -> {
                    // Support both "itemCard" (new) and "itemTemplate" (legacy)
                    val ref = node.attributes["itemCard"] ?: node.attributes["itemTemplate"]
                    if (ref.isNullOrBlank() || !cardIds.contains(ref)) {
                        out += ValidationIssue(ValidationCode.BAD_TEMPLATE_REF, Severity.ERROR, "Carousel itemCard '$ref' not found.", node.source)
                    }
                }
                "child" -> {
                    // Support both "card" (new) and "template" (legacy)
                    val ref = node.attributes["card"] ?: node.attributes["template"]
                    if (ref.isNullOrBlank() || !cardIds.contains(ref)) {
                        out += ValidationIssue(ValidationCode.BAD_TEMPLATE_REF, Severity.ERROR, "Canvas child card '$ref' not found.", node.source)
                    }
                }
            }
        }
    }

    private fun validateLayers(cardNode: XmlNode, out: MutableList<ValidationIssue>) {
        // Ensure unique layer ids within a card
        val layerIds = HashSet<String>()
        cardNode.children.forEach { child ->
            when (child.name.lowercase(Locale.ROOT)) {
                "image", "video", "overlay", "shadow", "border", "text", "backdrop" -> {
                    val id = child.attributes["id"]?.trim()
                    if (!id.isNullOrEmpty()) {
                        if (!layerIds.add(id)) {
                            out += ValidationIssue(ValidationCode.DUPLICATE_ID, Severity.ERROR, "Duplicate layer id '$id' in card '${cardNode.attributes["id"]}'.", child.source)
                        }
                    }
                    // Common checks
                    child.attributes["opacity"]?.toFloatOrNull()?.let { if (it < 0f || it > 1f) out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Layer opacity must be in [0,1].", child.source) }
                }
            }
        }
    }

    private fun validateStatesTransitions(cardNode: XmlNode, out: MutableList<ValidationIssue>) {
        // Find states declared under this card
        val stateNames = HashSet<String>()
        val statesParent = cardNode.children.firstOrNull { it.name.equals("states", true) }
        statesParent?.children?.forEach { st ->
            if (st.name.equals("state", true)) {
                val name = st.attributes["name"]?.trim().orEmpty()
                if (name.isEmpty()) {
                    out += ValidationIssue(ValidationCode.REQUIRED_FIELD_MISSING, Severity.ERROR, "State requires name.", st.source)
                } else if (!stateNames.add(name)) {
                    out += ValidationIssue(ValidationCode.DUPLICATE_ID, Severity.ERROR, "Duplicate state '$name' in card '${cardNode.attributes["id"]}'.", st.source)
                }
                // Modifiers sanity
                st.children.forEach { mod ->
                    when (mod.name.lowercase(Locale.ROOT)) {
                        "opacity" -> mod.attributes["value"]?.toFloatOrNull()?.let { if (it < 0f || it > 1f) out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Opacity must be in [0,1] in state '$name'.", mod.source) }
                        "shadow" -> mod.attributes["radiusPx"]?.toFloatOrNull()?.let { if (it < 0f) out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Shadow radius must be >= 0.", mod.source) }
                        "border" -> mod.attributes["widthPx"]?.toFloatOrNull()?.let { if (it < 0f) out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Border width must be >= 0.", mod.source) }
                        "blur" -> mod.attributes["radiusPx"]?.toFloatOrNull()?.let { if (it < 0f) out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "Blur radius must be >= 0.", mod.source) }
                    }
                }
            }
        }
        val transitionsParent = cardNode.children.firstOrNull { it.name.equals("transitions", true) }
        transitionsParent?.children?.forEach { tr ->
            if (tr.name.equals("transition", true)) {
                val from = tr.attributes["from"]
                val to = tr.attributes["to"]
                if (from.isNullOrBlank() || to.isNullOrBlank()) {
                    out += ValidationIssue(ValidationCode.REQUIRED_FIELD_MISSING, Severity.ERROR, "Transition requires 'from' and 'to'.", tr.source)
                } else {
                    if (!stateNames.contains(from)) out += ValidationIssue(ValidationCode.UNKNOWN_STATE_REF, Severity.ERROR, "Transition from '$from' not declared.", tr.source)
                    if (!stateNames.contains(to)) out += ValidationIssue(ValidationCode.UNKNOWN_STATE_REF, Severity.ERROR, "Transition to '$to' not declared.", tr.source)
                }
                tr.attributes["durationMs"]?.toIntOrNull()?.let { if (it < 0) out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "durationMs must be >= 0.", tr.source) }
                tr.attributes["delayMs"]?.toIntOrNull()?.let { if (it < 0) out += ValidationIssue(ValidationCode.INVALID_RANGE, Severity.ERROR, "delayMs must be >= 0.", tr.source) }
            }
        }
        // swapSource layer id existence (if used)
        statesParent?.children?.forEach { st ->
            st.children.filter { it.name.equals("swapSource", true) }.forEach { swap ->
                val layerId = swap.attributes["layerId"]
                if (layerId.isNullOrBlank()) {
                    out += ValidationIssue(ValidationCode.REQUIRED_FIELD_MISSING, Severity.ERROR, "swapSource requires layerId.", swap.source)
                } else {
                    val layerExists = cardNode.children.any { child ->
                        when (child.name.lowercase(Locale.ROOT)) {
                            "image", "video", "overlay", "shadow", "border", "text", "backdrop" -> child.attributes["id"] == layerId
                            else -> false
                        }
                    }
                    if (!layerExists) {
                        out += ValidationIssue(ValidationCode.UNKNOWN_LAYER_REF, Severity.ERROR, "swapSource layerId '$layerId' not found in card '${cardNode.attributes["id"]}'.", swap.source)
                    }
                }
            }
        }
    }

    private fun validateImageNode(tree: ThemeTree, node: XmlNode, out: MutableList<ValidationIssue>) {
        val src = node.attributes["src"]
        if (src.isNullOrBlank()) {
            out += ValidationIssue(ValidationCode.MISSING_MEDIA_SRC, Severity.ERROR, "<image> requires 'src'.", node.source)
            return
        }
        // Best-effort asset presence for literal relative paths
        checkAssetPresenceIfLiteral(tree, node, attrName = "src", out)
        // Optional fallback
        checkAssetPresenceIfLiteral(tree, node, attrName = "fallback", out, warnMissing = true)
    }

    private fun validateVideoNode(tree: ThemeTree, node: XmlNode, out: MutableList<ValidationIssue>) {
        val src = node.attributes["src"]
        if (src.isNullOrBlank()) {
            out += ValidationIssue(ValidationCode.MISSING_MEDIA_SRC, Severity.ERROR, "<video> requires 'src'.", node.source)
            return
        }
        checkAssetPresenceIfLiteral(tree, node, attrName = "src", out)
        val poster = node.attributes["poster"]
        if (poster.isNullOrBlank()) {
            out += ValidationIssue(ValidationCode.POSTER_MISSING, Severity.WARNING, "Video should provide a poster image for better UX.", node.source)
        } else {
            checkAssetPresenceIfLiteral(tree, node, attrName = "poster", out, warnMissing = true)
        }
        // fallback image
        checkAssetPresenceIfLiteral(tree, node, attrName = "fallbackImage", out, warnMissing = true)
    }

    private fun checkAssetPresenceIfLiteral(
        tree: ThemeTree,
        node: XmlNode,
        attrName: String,
        out: MutableList<ValidationIssue>,
        warnMissing: Boolean = false
    ) {
        val value = node.attributes[attrName] ?: return
        val isBinding = value.startsWith("@{") && value.endsWith("}")
        if (isBinding) return // runtime-bound; cannot validate presence
        val hasScheme = value.contains("://")
        val isAbsoluteWindowsPath = value.matches(Regex("^[A-Za-z]:\\\\.*"))
        if (hasScheme || isAbsoluteWindowsPath) return // external or absolute; skip best-effort
        // Treat as theme-relative path
        val f = File(tree.rootDir, value)
        if (!f.exists()) {
            out += ValidationIssue(
                ValidationCode.ASSET_NOT_FOUND,
                if (warnMissing) Severity.WARNING else Severity.WARNING,
                "Asset not found: '${value}'.",
                node.source
            )
        }
    }
    // endregion

    // region utils
    /** Compare two semantic version strings a vs b. Returns <0 if a<b, 0 if equal, >0 if a>b. */
    fun compareSemVer(a: String, b: String): Int {
        fun parse(s: String): List<Int> = s.trim().split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parse(a)
        val pb = parse(b)
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val ai = if (i < pa.size) pa[i] else 0
            val bi = if (i < pb.size) pb[i] else 0
            if (ai != bi) return ai - bi
        }
        return 0
    }

    /**
     * Parse a dimension value that can be either pixels (e.g., "100") or percentage (e.g., "50%").
     * Returns the numeric value if valid (> 0), or null if invalid.
     */
    private fun parseDimensionValue(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        return if (trimmed.endsWith("%")) {
            trimmed.dropLast(1).toFloatOrNull()
        } else {
            trimmed.toFloatOrNull()
        }
    }

    private fun traverse(node: XmlNode, block: (XmlNode) -> Unit) {
        fun walk(n: XmlNode) {
            block(n)
            n.children.forEach { walk(it) }
        }
        walk(node)
    }
    // endregion
}
