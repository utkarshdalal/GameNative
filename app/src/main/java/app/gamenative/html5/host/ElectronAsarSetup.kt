package app.gamenative.html5.host

import app.gamenative.html5.asar.AppSignInHosts
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
    // whether a page can get a WebView profile of its own (Html5PageProfiles); protocol mode needs one.
    private val profilesSupported: () -> Boolean = { false },
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
        val entry = resolvedEntry?.takeIf { archive.exists(it) } ?: discoveredProtocolPage?.entryName
        resolvePreload(archive, entry)?.let { "/$it" }
    }

    // an app with no html page of its own in the archive whose main serves one over https from files that exist
    // here, before the checks that decide whether it may run that way.
    private val discoveredProtocolPage: ProtocolServedPage? by lazy {
        if (resolvedEntry?.let { archive.exists(it) } == true) return@lazy null
        val page = runCatching { mainScriptSource(archive)?.let { ProtocolServedPage.parse(it) } }
            .onFailure { e -> Timber.tag(TAG).w(e, "protocol page discovery failed") }
            .getOrNull() ?: return@lazy null
        val root = protocolRoot(page) ?: return@lazy null
        if (page.entryHtml == null && !File(root, page.entryName).isFile) return@lazy null
        page
    }

    // the one decision every protocol-mode piece reads (interceptor, entry url, main script, bridges, quit
    // channels). refused, the title runs as any other electron app.
    val protocolPage: ProtocolServedPage? by lazy {
        val page = discoveredProtocolPage ?: return@lazy null
        val refusal = protocolPageRefusal(page)
        if (refusal != null) {
            Timber.tag(TAG).w("protocol-served page %s not used: %s", page.entryUrl, refusal)
            return@lazy null
        }
        Timber.tag(TAG).i("protocol-served page %s from resources/%s", page.entryUrl, page.resourceDir)
        if (!profilesSupported()) {
            Timber.tag(TAG).i("this WebView has no profile support; %s runs in the default profile", page.host)
        }
        page
    }

    val protocolRootDir: File? by lazy { protocolPage?.let { protocolRoot(it) } }

    // the app's own main script runs in the page as its main process (shims/electron-main.js) only when main
    // serves the page itself: such a page was written against main's live ipc handlers, and no title that ran
    // before had one, so every other electron app keeps the plain ipcRenderer stub.
    val mainProcessScript: String? by lazy {
        if (protocolPage == null) return@lazy null
        runCatching {
            val name = mainScriptName(archive) ?: return@runCatching null
            archive.read(name)?.toString(Charsets.UTF_8)?.let { wrapMainScript(it, name) }
        }.onFailure { e -> Timber.tag(TAG).w(e, "main script read failed") }.getOrNull()
    }

    // after the shims, before the preload.
    val mainProcessScriptUrls: List<String>
        get() = if (mainProcessScript != null) listOf(MAIN_PROCESS_SHIM_URL, MAIN_PROCESS_SCRIPT_URL) else emptyList()

    private fun protocolRoot(page: ProtocolServedPage): File? =
        resourcesDir?.let { File(it, page.resourceDir) }?.takeIf { it.isDirectory }

    // __gnElectronCtx, so JS app.getPath is a map lookup rather than a bridge round-trip.
    fun buildContext(): Map<String, String>? {
        val name = productName ?: return null
        // the stand-in runs main's own handlers; these are the fallback when it isn't active.
        val quit = if (protocolPage == null) {
            emptyList()
        } else {
            runCatching { mainScriptSource(archive)?.let { quitChannels(it) }.orEmpty() }.getOrDefault(emptyList())
        }
        return buildElectronCtx(name, version, quit)
    }

    fun close() {
        runCatching { archive.close() }
    }

    companion object {
        private const val TAG = "ElectronAsarSetup"

        const val MAIN_PROCESS_SHIM_URL = "/_shims/electron-main.js"

        // why a discovered protocol page must not run at its origin, or null when it may.
        internal fun protocolPageRefusal(page: ProtocolServedPage): String? =
            if ((page.aliasHosts + page.host).any(AppSignInHosts::covers)) "the app signs in to ${page.host}" else null
        const val MAIN_PROCESS_SCRIPT_URL = "/_gn_electron_main.js"

        // the script as a function body for __gnElectronMain.run. a hashbang line can't sit inside a function, and
        // the closing brace goes on its own line since mains end in a `//#` source map comment.
        internal fun wrapMainScript(source: String, name: String): String {
            val body = if (source.startsWith("#!")) "//" + source else source
            return "window.__gnElectronMain&&window.__gnElectronMain.run(" +
                "function(require,module,exports,__filename,__dirname,process,Buffer){\n" +
                body + "\n},${jsString(name)});\n"
        }

        private fun jsString(s: String): String = buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c < ' ' || c == '\u2028' || c == '\u2029' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

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
        // so a variable that happens to be named `join` is still unknown. a minified main's alias of the path module
        // (`t=require("node:path")` ... `t.join(`) is rewritten to `path.` before this (withPathAliases).
        private val KNOWN_BASE_TOKENS = Regex(
            """path\.(join|resolve)(?=\s*\()|\b(join|resolve)(?=\s*\()|__dirname|import\.meta\.dirname|app\.getAppPath\(\)|[\s+,()]""",
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

        private fun mainScriptName(archive: ElectronArchive): String? =
            (archive.packageJson()?.get("main") as? JsonPrimitive)?.contentOrNull
                ?.trim()?.removePrefix("./")?.trimStart('/')
                ?.takeIf { it.isNotBlank() }

        private fun mainScriptSource(archive: ElectronArchive): String? {
            val mainName = mainScriptName(archive) ?: return null
            return archive.read(mainName)?.toString(Charsets.UTF_8)
        }

        private val IPC_ON_REGEX = Regex("""ipcMain\.on\(\s*(["'])([^"'\\\s]+)\1\s*,\s*""")
        private val HANDLER_HEAD = Regex("""^(?:async\s+)?(?:function\s*[\w$]*\s*\(([^)]*)\)|\(([^)]*)\)\s*=>|([\w$]+)\s*=>)\s*""")
        private const val QUIT_CALL = """(?:[\w$]+\.)?app\.(?:quit|exit)\(\s*\d*\s*\)"""
        // a sender check: `ok(e)`, `!ok(e)`, `e.sender.isTrusted()`; at most one plain argument.
        private const val GUARD = """!?\s*[\w$.]+(?:\(\s*[\w$.]*\s*\))?"""
        private val QUIT_BODY = Regex(
            """^(?:$QUIT_CALL|$GUARD\s*&&\s*$QUIT_CALL|if\s*\(\s*$GUARD\s*\)\s*(?:$QUIT_CALL|\{\s*$QUIT_CALL\s*;?\s*\})|""" +
                """if\s*\(\s*$GUARD\s*\)\s*return\s*;?\s*$QUIT_CALL)\s*;?$""",
        )
        private val IDENT = Regex("""[A-Za-z_$][\w$]*""")

        // ipcMain.on channels whose handler only quits, after at most a sender check, e.g.
        // `ipcMain.on("x:exit",e=>{ok(e)&&app.quit()})`. a renderer send on one is the game's quit button. a handler
        // that branches on what was sent (window controls) or does anything else (relaunch) doesn't count: every
        // send on a listed channel exits.
        internal fun quitChannels(mainScript: String): List<String> {
            val code = jsCodeMask(mainScript)
            return IPC_ON_REGEX.findAll(mainScript)
                .filter { code[it.range.first] }
                .filter { m -> isQuitOnlyHandler(valueExpression(mainScript, m.range.last + 1)) }
                .map { it.groupValues[2] }
                .distinct()
                .toList()
        }

        internal fun isQuitOnlyHandler(handler: String): Boolean {
            val src = handler.trim()
            val head = HANDLER_HEAD.find(src) ?: return false
            val params = (head.groups[1] ?: head.groups[2] ?: head.groups[3])?.value.orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            var body = src.substring(head.range.last + 1).trim()
            if (body.startsWith("{")) {
                if (!body.endsWith("}")) return false
                body = body.substring(1, body.length - 1).trim()
            }
            if (!QUIT_BODY.matches(body)) return false
            // the guard may look at the event only, never at the payload.
            val payload = params.drop(1).toSet()
            return IDENT.findAll(body).none { it.value in payload }
        }

        // entryName: the page the app's main window loads, which picks between several preloads.
        internal fun resolvePreload(archive: ElectronArchive, entryName: String? = null): String? {
            val fromMain = runCatching { preloadFromMainScript(archive, entryName) }
                .onFailure { e -> Timber.tag(TAG).w(e, "preload discovery from main script failed") }
                .getOrNull()
            return fromMain ?: "preload.js".takeIf { archive.exists(it) }
        }

        private class PreloadKey(val at: Int, val path: String, val viaAlias: Boolean)

        // one preload: that one. several: the one in the options of the window that loads the entry page; if
        // that can't be told, the first `preload:` that resolves without a path-module alias (`path.join` / a
        // bare `join`), as before aliases were read.
        private fun preloadFromMainScript(archive: ElectronArchive, entryName: String?): String? {
            val mainName = mainScriptName(archive) ?: return null
            val src = mainScriptSource(archive) ?: return null
            val mainDir = mainName.substringBeforeLast('/', "")
            val code = jsCodeMask(src)
            val aliases = pathModuleAliases(src, code)
            val keys = mutableListOf<PreloadKey>()
            for (key in PRELOAD_KEY_REGEX.findAll(src)) {
                if (!code[key.groups[2]!!.range.first]) continue
                if (!isPropertyPosition(src, code, key.range.first)) continue
                val expr = valueExpression(src, key.range.last + 1)
                val direct = pathFromExpression(expr)
                val rel = direct ?: pathFromExpression(withPathAliases(expr, aliases)) ?: continue
                val appRootBased = APP_ROOT_BASE.containsMatchIn(STRING_LITERAL_REGEX.replace(expr, ""))
                val candidates = listOfNotNull(
                    if (mainDir.isNotEmpty() && !appRootBased) normalize("$mainDir/$rel") else null,
                    normalize(rel),
                )
                val found = candidates.firstOrNull { archive.exists(it) } ?: continue
                keys += PreloadKey(key.range.first, found, viaAlias = direct == null)
            }
            if (keys.map { it.path }.distinct().size <= 1) return keys.firstOrNull()?.path
            entryName?.let { pickEntryWindowPreload(src, code, keys, it) }?.let { return it }
            return keys.firstOrNull { !it.viaAlias }?.path
        }

        // `const t=require("node:path")`, `t=__toESM(require("path"))`, `import * as t from "path"`.
        private val PATH_REQUIRE_BINDING = Regex(
            """(?<![\w$.])([A-Za-z_$][\w$]*)\s*=\s*(?:[\w$.]+\(\s*)?require\(\s*(["'])(?:node:)?path\2\s*\)""",
        )
        private val PATH_IMPORT_BINDING = Regex(
            """\bimport\s+(?:\*\s+as\s+)?([A-Za-z_$][\w$]*)\s+from\s+(["'])(?:node:)?path\2""",
        )

        internal fun pathModuleAliases(src: String, code: BooleanArray = jsCodeMask(src)): Set<String> =
            (PATH_REQUIRE_BINDING.findAll(src) + PATH_IMPORT_BINDING.findAll(src))
                .filter { code[it.range.first] }
                .map { it.groupValues[1] }
                .filter { it != "path" }
                .toSet()

        // `t.join(` for a name bound to the path module reads as `path.join(`.
        private fun withPathAliases(expr: String, aliases: Set<String>): String {
            if (aliases.isEmpty()) return expr
            val names = aliases.joinToString("|") { Regex.escape(it) }
            return Regex("""(?<![\w$.])(?:$names)\.(join|resolve)(?=\s*\()""").replace(expr) { "path.${it.groupValues[1]}" }
        }

        private val NEW_BROWSER_WINDOW = Regex("""\bnew\s+(?:[\w$]+\.)*BrowserWindow\s*\(""")
        private val BINDING_BEFORE = Regex("""([A-Za-z_$][\w$.]*)\s*=\s*$""")
        private val HTML_LITERAL = Regex("""["'`]([^"'`]*\.html?)["'`]""", RegexOption.IGNORE_CASE)

        private enum class Loads { ENTRY, OTHER, UNKNOWN }

        private fun pickEntryWindowPreload(src: String, code: BooleanArray, keys: List<PreloadKey>, entryName: String): String? {
            val entryBase = entryName.substringAfterLast('/')
            val loads = keys.associateWith { windowLoads(src, code, it.at, entryBase) }
            loads.filterValues { it == Loads.ENTRY }.keys.map { it.path }.distinct().singleOrNull()?.let { return it }
            return loads.filterValues { it != Loads.OTHER }.keys.map { it.path }.distinct().singleOrNull()
        }

        // what the BrowserWindow whose options hold the key at `at` loads.
        private fun windowLoads(src: String, code: BooleanArray, at: Int, entryBase: String): Loads {
            val ctor = NEW_BROWSER_WINDOW.findAll(src.substring(0, at)).lastOrNull { code[it.range.first] } ?: return Loads.UNKNOWN
            val close = matchingParen(src, code, ctor.range.last)
            if (close in 0 until at) return Loads.UNKNOWN
            val name = BINDING_BEFORE.find(src.substring(maxOf(0, ctor.range.first - 120), ctor.range.first))
                ?.groupValues?.get(1) ?: return Loads.UNKNOWN
            val load = Regex("""(?<![\w$])${Regex.escape(name)}\.(?:webContents\.)?(?:loadURL|loadFile)\(\s*""")
                .findAll(src).firstOrNull { code[it.range.first] } ?: return Loads.UNKNOWN
            val pages = HTML_LITERAL.findAll(valueExpression(src, load.range.last + 1)).map { it.groupValues[1] }.toList()
            return when {
                pages.any { it.substringAfterLast('/').equals(entryBase, ignoreCase = true) } -> Loads.ENTRY
                pages.isNotEmpty() -> Loads.OTHER
                else -> Loads.UNKNOWN
            }
        }

        // index of the `)` closing the `(` at `open`, counting code only; -1 when unclosed.
        private fun matchingParen(src: String, code: BooleanArray, open: Int): Int {
            var depth = 0
            for (i in open until src.length) {
                if (!code[i]) continue
                when (src[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return i
                }
            }
            return -1
        }

        // an UNPACKED resources/app/ is ambiguous (disk-served titles keep payloads there too), so only
        // these packs route it through the archive interceptor. a real .asar file is not gated.
        private val UNPACKED_ARCHIVE_ENGINES = setOf(EnginePackId.ELECTRON, EnginePackId.TYRANO)

        // null = no archive; AssetInterceptor serves from disk.
        //
        // a real resources/app.asar is opened for ANY pack: the fingerprinter probes inside the asar, so an
        // asar-packed RMMV / C3 / NW.js title fingerprints as its true engine but its files exist only in the
        // archive. pack identity picks the shim set; the interceptor just moves bytes.
        fun open(
            installPath: String,
            profile: EngineProfile?,
            profilesSupported: () -> Boolean = Html5PageProfiles::isSupported,
        ): ElectronAsarSetup? {
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
            return archive?.let { ElectronAsarSetup(it, File(base, "resources"), profilesSupported) }
        }
    }
}
