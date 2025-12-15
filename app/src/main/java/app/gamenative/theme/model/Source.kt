package app.gamenative.theme.model

/**
 * Source location information for better diagnostics in parsing/validation.
 */
data class SourceLoc(
    /** Full file path on disk or in assets from which this node originated. */
    val filePath: String,
    /** 1-based line number within the source file, if available. */
    val line: Int? = null,
    /** 1-based column number within the source file, if available. */
    val column: Int? = null,
)

/**
 * Minimal XML-structured node used by the ThemeLoader to represent merged XML trees
 * (with includes expanded) while preserving source locations.
 */
data class XmlNode(
    /** Tag name of the element. */
    val name: String,
    /** Attributes as parsed on this element. */
    val attributes: Map<String, String> = emptyMap(),
    /** Ordered child elements (text content is not significant for our schema). */
    val children: List<XmlNode> = emptyList(),
    /** Optional text content if needed for leaf nodes. */
    val text: String? = null,
    /** Where this node came from in the original source. */
    val source: SourceLoc? = null,
)

/**
 * Resulting merged theme tree produced by ThemeLoader step.
 * This is the artifact validated by ThemeValidator in the next step,
 * and later mapped into the runtime ThemeDefinition.
 */
data class ThemeTree(
    /** Directory path (root folder of the theme). */
    val rootDir: String,
    /** Parsed manifest entry values that guide loading (e.g., selected theme.xml and variables.xml paths). */
    val manifestEntry: ManifestEntry?,
    /** Fully merged theme XML as an XmlNode tree with includes expanded. */
    val themeXml: XmlNode,
    /** Merged variables from external and inline sources; last-writer wins semantics. */
    val variables: Map<String, String> = emptyMap(),
    /** Responsive breakpoints for orientation/size-based variable overrides. */
    val breakpoints: List<Breakpoint> = emptyList(),
)

/**
 * Manifest entry directing the loader which files to use.
 */
data class ManifestEntry(
    /** Relative path to theme.xml within the theme folder. */
    val themePath: String,
    /** Optional relative path to variables.xml within the theme folder. */
    val variablesPath: String? = null,
    /** Source location for the entry for diagnostics. */
    val source: SourceLoc? = null,
)

/**
 * A recoverable error encountered during loading; never throws to caller.
 */
data class ThemeLoadError(
    /** Stable error code for programmatic handling. */
    val code: String,
    /** Human-readable message (dev facing). */
    val message: String,
    /** Optional source location for precise diagnostics. */
    val source: SourceLoc? = null,
)

sealed class ThemeLoadResult {
    data class Success(val tree: ThemeTree): ThemeLoadResult()
    data class Failure(val errors: List<ThemeLoadError>): ThemeLoadResult()
}
