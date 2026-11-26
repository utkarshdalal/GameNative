package app.gamenative.html5.host

import app.gamenative.html5.asar.AsarArchive
import app.gamenative.html5.asar.AsarAssetInterceptor
import app.gamenative.html5.asar.ElectronArchive
import app.gamenative.html5.asar.ProtocolServedPage
import app.gamenative.html5.asar.UnpackedElectronArchive
import app.gamenative.html5.asar.jsCodeMask
import app.gamenative.html5.profile.EngineProfile
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import app.gamenative.html5.profile.EnginePackId

// owns the archive handle; caller must close() on WebView teardown.
// packed asar wins over unpacked resources/app -- it's what the title actually runs.
class ElectronAsarSetup private constructor(
    val archive: ElectronArchive,
    // electron's process.resourcesPath.
    private val resourcesDir: File? = null,
) {
    // null surfaces a snackbar; the bridge falls back to the install-dir sandbox root and app.getPath
    // fails loudly with NOT_IMPLEMENTED_V1.
    val productName: String? by lazy {
        archive.packageJson()?.let { pkg ->
            val byProductName = (pkg["productName"] as? JsonPrimitive)?.contentOrNull
            val byName = (pkg["name"] as? JsonPrimitive)?.contentOrNull
            byProductName?.takeIf { it.isNotBlank() }
                ?: byName?.takeIf { it.isNotBlank() }
        }
    }

    val version: String? by lazy {
        archive.packageJson()?.let { pkg ->
            (pkg["version"] as? JsonPrimitive)?.contentOrNull
        }
    }

    // same heuristic as the interceptor. an entry in a subdir (src/index.html) must be loaded by its real
    // URL, or relative sub-resource URLs resolve against the wrong path and 404.
    val resolvedEntry: String? by lazy {
        AsarAssetInterceptor.resolveEntry(archive)
    }

    // without the preload, whatever it publishes via contextBridge.exposeInMainWorld stays undefined.
    // the main script names it in webPreferences.preload, often outside the root (electron/preload.cjs).
    val preloadUrl: String? by lazy {
        resolvePreload(archive)?.let { "/$it" }
    }

    // only for an app with no html page of its own in the archive whose main serves one over https from files
    // that exist here.
    val protocolPage: ProtocolServedPage? by lazy {
        if (resolvedEntry?.let { archive.exists(it) } == true) return@lazy null
        val page = runCatching { mainScriptSource(archive)?.let { ProtocolServedPage.parse(it) } }
            .onFailure { e -> Timber.tag(TAG).w(e, "protocol page discovery failed") }
            .getOrNull() ?: return@lazy null
        val root = protocolRoot(page) ?: return@lazy null
        if (page.entryHtml == null && !File(root, page.entryName).isFile) return@lazy null
        Timber.tag(TAG).i("protocol-served page %s from resources/%s", page.entryUrl, page.resourceDir)
        page
    }

    val protocolRootDir: File? by lazy { protocolPage?.let { protocolRoot(it) } }

    private fun protocolRoot(page: ProtocolServedPage): File? =
        resourcesDir?.let { File(it, page.resourceDir) }?.takeIf { it.isDirectory }

    // __gnElectronCtx, so JS app.getPath is a map lookup rather than a bridge round-trip.
    fun buildContext(): Map<String, String>? {
        val name = productName ?: return null
        val quit = runCatching { mainScriptSource(archive)?.let { quitChannels(it) }.orEmpty() }.getOrDefault(emptyList())
        return buildElectronCtx(name, version, quit)
    }

    fun close() {
        runCatching { archive.close() }
    }

    companion object {
        private const val TAG = "ElectronAsarSetup"

        // `preload: join(__dirname, "preload.cjs")` / `preload: path.join(__dirname, "src", "preload.js")` /
        // `preload: __dirname + "/bridge/preload.js"`: the string parts of the preload value expression, joined
        // (`+` concatenates, otherwise path-joins), relative to the main script's dir. only that expression is
        // read, and only `preload:` keys in code (not comments or strings): a minified main is one line, and
        // `preload: p` with p bound earlier must not pick up a later literal. unresolvable = root preload.js.
        // bare or quoted key (`preload:` / `"preload":`); the colon must be code, which rules out comments and
        // string values that merely mention it.
        private val PRELOAD_KEY_REGEX = Regex("""(?<![\w$])(["']?)preload\1\s*(:)\s*""")
        private val STRING_LITERAL_REGEX = Regex("""'([^']*)'|"([^"]*)"|`([^`]*)`""")
        private val TEMPLATE_SUBST_REGEX = Regex("""\$\{[^}]*\}""")
        private val PRELOAD_EXT_REGEX = Regex("""\.c?js$""")

        // the value runs to the first `,` `}` `)` `]` or `;` at bracket depth 0. strings are kept whole and
        // comments dropped, so neither can end the value early or contribute a literal.
        private fun valueExpression(src: String, start: Int): String {
            val out = StringBuilder()
            var depth = 0
            var i = start
            while (i < src.length) {
                val c = src[i]
                val next = if (i + 1 < src.length) src[i + 1] else ' '
                when {
                    c == '/' && next == '/' -> {
                        i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                        continue
                    }
                    c == '/' && next == '*' -> {
                        i = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                        continue
                    }
                    c == '\'' || c == '"' || c == '`' -> {
                        val begin = i
                        i++
                        while (i < src.length && src[i] != c) {
                            if (src[i] == '\\') i++
                            i++
                        }
                        out.append(src, begin, minOf(i + 1, src.length))
                        i++
                        continue
                    }
                    c == '(' || c == '[' || c == '{' -> depth++
                    c == ')' || c == ']' || c == '}' -> if (depth == 0) return out.toString() else depth--
                    c == ',' || c == ';' -> if (depth == 0) return out.toString()
                }
                out.append(c)
                i++
            }
            return out.toString()
        }

        // archive-relative path from the expression's string parts, or null when it can't name a .js/.cjs file.
        // anything else in the expression (a variable like `join(__dirname, sub, "preload.js")`) is a path part
        // we can't know, so the expression is unresolvable rather than guessed. join/resolve only count as calls,
        // so a variable that happens to be named `join` is still unknown. minified mains alias the path module
        // (`t.join(__dirname,"preload.js")`), so any `<name>.join(` counts.
        private val KNOWN_BASE_TOKENS = Regex(
            """[A-Za-z_$][\w$]*\.(join|resolve)(?=\s*\()|\b(join|resolve)(?=\s*\()|__dirname|import\.meta\.dirname|app\.getAppPath\(\)|[\s+,()]""",
        )
        private val KNOWN_SUBST = Regex("""\$\{\s*(__dirname|import\.meta\.dirname)\s*\}""")

        private val JOIN_CALL = Regex("""\b(join|resolve)\s*\(""")

        // app.getAppPath() is the app root, not the main script's dir.
        private val APP_ROOT_BASE = Regex("""app\.getAppPath\(\)""")

        private fun pathFromExpression(expr: String): String? {
            val literals = STRING_LITERAL_REGEX.findAll(expr)
                .map { m -> m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value.orEmpty() }
                .toList()
            if (literals.isEmpty()) return null
            val outsideStrings = STRING_LITERAL_REGEX.replace(expr, "")
            if (KNOWN_BASE_TOKENS.replace(outsideStrings, "").isNotEmpty()) return null
            // join("bridge", "preload" + ".js") mixes per-argument concatenation with joining; not worth evaluating.
            if (outsideStrings.contains('+') && JOIN_CALL.containsMatchIn(outsideStrings)) return null
            // only the dirname substitutions are base dirs; any other ${...} is unknown.
            val parts = literals.map { KNOWN_SUBST.replace(it, "") }
            if (parts.any { TEMPLATE_SUBST_REGEX.containsMatchIn(it) }) return null
            val joined = if (outsideStrings.contains('+')) parts.joinToString("") else parts.joinToString("/")
            return joined.takeIf { PRELOAD_EXT_REGEX.containsMatchIn(it) }
        }

        // resolves `.` / `..`; null when the path climbs above the archive root.
        private fun normalize(path: String): String? {
            val out = ArrayDeque<String>()
            for (seg in path.replace('\\', '/').split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> out.removeLastOrNull() ?: return null
                    else -> out.addLast(seg)
                }
            }
            return out.joinToString("/").takeIf { it.isNotEmpty() }
        }

        // an object key follows `{` or `,`; a string that merely reads "preload" before a ternary `:` does not.
        private fun isPropertyPosition(src: String, code: BooleanArray, keyStart: Int): Boolean {
            var i = keyStart - 1
            while (i >= 0 && (src[i].isWhitespace() || !code[i])) i--
            return i >= 0 && (src[i] == '{' || src[i] == ',')
        }

        private fun mainScriptSource(archive: ElectronArchive): String? {
            val mainName = (archive.packageJson()?.get("main") as? JsonPrimitive)?.contentOrNull
                ?.trim()?.removePrefix("./")?.trimStart('/')
                ?.takeIf { it.isNotBlank() } ?: return null
            return archive.read(mainName)?.toString(Charsets.UTF_8)
        }

        private val IPC_ON_REGEX = Regex("""ipcMain\.on\(\s*(["'])([^"'\\\s]+)\1\s*,\s*""")
        private val APP_QUIT_REGEX = Regex("""\bapp\.(quit|exit)\(""")
        private const val QUIT_HANDLER_MAX = 400

        // ipcMain.on channels whose (short) handler quits the app, e.g. `ipcMain.on("x:exit",e=>{ok(e)&&app.quit()})`.
        // a renderer send on one of them is the game's quit button.
        internal fun quitChannels(mainScript: String): List<String> {
            val code = jsCodeMask(mainScript)
            return IPC_ON_REGEX.findAll(mainScript)
                .filter { code[it.range.first] }
                .filter { m ->
                    val handler = valueExpression(mainScript, m.range.last + 1)
                    handler.length <= QUIT_HANDLER_MAX && APP_QUIT_REGEX.containsMatchIn(handler)
                }
                .map { it.groupValues[2] }
                .distinct()
                .toList()
        }

        internal fun resolvePreload(archive: ElectronArchive): String? {
            val fromMain = runCatching { preloadFromMainScript(archive) }
                .onFailure { e -> Timber.tag(TAG).w(e, "preload discovery from main script failed") }
                .getOrNull()
            return fromMain ?: "preload.js".takeIf { archive.exists(it) }
        }

        private fun preloadFromMainScript(archive: ElectronArchive): String? {
            val mainName = (archive.packageJson()?.get("main") as? JsonPrimitive)?.contentOrNull
                ?.trim()?.removePrefix("./")?.trimStart('/')
                ?.takeIf { it.isNotBlank() } ?: return null
            val src = mainScriptSource(archive) ?: return null
            val mainDir = mainName.substringBeforeLast('/', "")
            val code = jsCodeMask(src)
            for (key in PRELOAD_KEY_REGEX.findAll(src)) {
                if (!code[key.groups[2]!!.range.first]) continue
                if (!isPropertyPosition(src, code, key.range.first)) continue
                val expr = valueExpression(src, key.range.last + 1)
                val rel = pathFromExpression(expr) ?: continue
                val appRootBased = APP_ROOT_BASE.containsMatchIn(STRING_LITERAL_REGEX.replace(expr, ""))
                val candidates = listOfNotNull(
                    if (mainDir.isNotEmpty() && !appRootBased) normalize("$mainDir/$rel") else null,
                    normalize(rel),
                )
                candidates.firstOrNull { archive.exists(it) }?.let { return it }
            }
            return null
        }

        // an UNPACKED resources/app/ is ambiguous (disk-served titles keep payloads there too), so only
        // these packs route it through the archive interceptor. a real .asar file is not gated.
        private val UNPACKED_ARCHIVE_ENGINES = setOf(EnginePackId.ELECTRON, EnginePackId.TYRANO)

        // null = no archive; AssetInterceptor serves from disk.
        //
        // a real resources/app.asar is opened for ANY pack: the fingerprinter probes inside the asar, so an
        // asar-packed RMMV / C3 / NW.js title fingerprints as its true engine but its files exist only in the
        // archive. pack identity picks the shim set; the interceptor just moves bytes.
        fun open(installPath: String, profile: EngineProfile?): ElectronAsarSetup? {
            val base = File(installPath)
            val asarFile = listOf(
                File(base, "resources/app.asar"),
                File(base, "resources/electron.asar"),
            ).firstOrNull { it.isFile }
            val unpackedRoot = File(base, "resources/app").takeIf {
                profile?.engine in UNPACKED_ARCHIVE_ENGINES && File(it, "package.json").isFile
            }
            val archive: ElectronArchive? = when {
                asarFile != null -> runCatching { AsarArchive.open(asarFile) }
                    .onFailure { e -> Timber.tag(TAG).e(e, "asar open failed: %s", asarFile.absolutePath) }
                    .getOrNull()
                unpackedRoot != null -> runCatching { UnpackedElectronArchive(unpackedRoot) }
                    .onFailure { e -> Timber.tag(TAG).e(e, "unpacked open failed: %s", unpackedRoot.absolutePath) }
                    .getOrNull()
                else -> null
            }
            return archive?.let { ElectronAsarSetup(it, File(base, "resources")) }
        }
    }
}
