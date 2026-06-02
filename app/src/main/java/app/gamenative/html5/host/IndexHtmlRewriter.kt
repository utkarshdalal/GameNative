package app.gamenative.html5.host

import java.io.InputStream
import org.json.JSONObject

// rewrites top-level HTML in-flight, injecting shim <script> tags BEFORE the first game
// <script>. regex matches case-insensitive `<script\b` so attrs on the first game script
// don't affect the match. fail-loud if no <script> present -- silently passing unshimmed
// HTML lets the WebView race the game's first JS and skip our shims.

// also inject a <style> block that forces the canvas top:0 +
// resets html/body. RMMV's Graphics._centerElement uses `inset:0; margin:auto`
// which hits a WebView layout quirk (canvas centered on y=0 instead of y=mid),
// rendering the top half off-screen. Applying at HTML parse time via stylesheet
// means the override is in effect BEFORE any game JS runs, and !important beats
// RMMV's inline-style writes.

// optional `locale` param prepends an inline <script> that pins
// navigator.language / navigator.languages BEFORE any shim or game script runs.
// null = back-compat (byte-identical to pre-change output).
object IndexHtmlRewriter {
    private val firstScript = Regex("<script\\b", RegexOption.IGNORE_CASE)

    // fix for RMMV-style canvas centering bug. RMMV's Graphics._centerElement
    // applies `position:absolute; top:0; bottom:0; margin:auto` to vertically center the
    // canvas. Chromium WebView (under our Compose AndroidView) resolves this to negative
    // top/bottom margins, positioning the canvas centered on y=0 instead of viewport-middle
    // -- rendering the top half off-screen. Stock Activity hosts (Capacitor) don't reproduce
    // this; it's specific to our composable WebView wrapper.
    
    // Minimal override: only touch top/bottom on absolutely-positioned canvases/error-printers
    // inside body. Top-aligned; any viewport-height excess falls as a small bottom strip.
    // Attempted vertical re-centering via `top: 50vh + translateY(-50%)` failed on retest
    // (canvas went back to the original negative-offset position -- unclear if transform is
    // blocked or vh resolution differs). Engine-agnostic: ID-independent.
    private val canvasFixStyle = """
        <style id="__gnCanvasFix">
          body > canvas, body > div[id$="rinter"] {
            top: 0 !important;
            bottom: auto !important;
            margin-top: 0 !important;
            margin-bottom: 0 !important;
          }
        </style>
    """.trimIndent()

    // WebView document overscroll/zoom suppression. sibling block to __gnCanvasFix -- kept
    // separate so future pack-CSS targeting body > canvas doesn't conflict. pan-x pan-y is
    // the locked W3C touch-action subset; `none` would break C3 pointer-event synthesis,
    // `manipulation` still allows pinch-zoom. overflow:hidden is idempotent for RMMV
    // (Graphics._centerElement already sets the same rule on init).
    private val overscrollFixStyle = """
        <style id="__gnOverscrollFix">
          html, body {
            overflow: hidden !important;
            overscroll-behavior: none !important;
            touch-action: pan-x pan-y !important;
          }
        </style>
    """.trimIndent()

    // process stub: pins navigator.language/languages AND exposes `process.env.{LANG,LANGUAGE,
    // LC_ALL,USERLANG}` + `process.versions` for plugins like SRD_TranslationEngine that read
    // `process.env.LANG` / `process.versions[...]` at parse time. posix LANG "en_US.UTF-8"
    // mirrors Linux shell format; BCP-47 on LANGUAGE.
    
    // CRITICAL: process is a FUNCTION, not an object. RMMV's `Utils.isNwjs()` checks
    // `typeof require === 'function' && typeof process === 'object'`
    // making process an object flips isNwjs() to true, causing YEP_CoreEngine.initNwjs to run
    // and crash on `require('nw.gui').Window.get()`. `typeof function !== 'object'`, so the
    // env/versions properties are still readable but the NW.js detection stays false.
    // versions = {} → `process.versions['node-webkit']` reads undefined without throwing.
    
    // mainModule.filename is read by RMMZ's StorageManager.fileDirectoryPath
    // (via `path.dirname(process.mainModule.filename)`) once fs.js's isLocalMode override
    // routes saves through the native path. functions are objects, so attaching mainModule
    // doesn't change typeof process -- still 'function', YEP guard still holds. empty filename
    // makes path.dirname('') → '.' and path.join('.', 'save/') → 'save/', which the fs bridge
    // resolves against the sandbox root correctly.
    
    // try/catch guards vendor WebViews that pre-lock navigator.language. emits a single
    // <script> IIFE that pins navigator + assembles a NW.js-style `window.process` so engine
    // bootstrap code that reads it at index.html parse time doesn't throw. comments inside the
    // template document the WHY for each block; engineering rationale that doesn't translate
    // to inline JS comments stays in the trailing Kotlin docstring above each section.
    //
    // env literal extends LANG/LANGUAGE/LC_ALL/USERLANG with Windows env vars matching
    // the wine prefix layout (<wine>/drive_c/users/xuser/...). Forward-slash forms because
    // our path.js is posix; wine is case-insensitive so drive_c vs C: doesn't matter once
    // the bridge maps drive letters.
    //
    // process.execPath = dot-relative Windows-form path. consumers:
    //   - TyranoScript libs.js: process.execPath.indexOf('var/folders') (mac branch guard);
    //     value just needs to NOT contain "var/folders", any path with ".exe" satisfies.
    //   - C2/C3 NodeWebkit plugins: this._appFolder = path.dirname(execPath) + slash. posix
    //     path.dirname of ".\\game.exe" → "." → AppFolder = "." → composed asset paths
    //     land sandbox-relative (=install dir). previously we used
    //     "C:\\users\\xuser\\AppData\\Local\\game\\game.exe" which derived AppFolder = wine
    //     roaming → "EXTERNAL FILES NOT LOADING PROPERLY" failures.
    //
    // CRITICAL: do NOT pre-initialize `module.exports`. UMD libraries (jQuery, etc.) check
    // `typeof module.exports === "object"` to decide between CommonJS and browser-global
    // assignment. with `.exports` defined they take the CommonJS branch and never set
    // their global → game's inline `jQuery(document).ready(...)` breaks.
    private fun buildLocaleScript(locale: String, mainModuleFilename: String): String {
        val q = JSONObject.quote(locale)
        val posix = JSONObject.quote(locale.replace('-', '_') + ".UTF-8")
        val envLit = """{LANG:$posix,LANGUAGE:$q,LC_ALL:$posix,USERLANG:$q,""" +
            """APPDATA:"C:/users/xuser/AppData/Roaming",""" +
            """LOCALAPPDATA:"C:/users/xuser/AppData/Local",""" +
            """USERPROFILE:"C:/users/xuser",""" +
            """HOMEPATH:"/users/xuser",HOMEDRIVE:"C:",""" +
            """TEMP:"C:/users/xuser/AppData/Local/Temp",TMP:"C:/users/xuser/AppData/Local/Temp",""" +
            """OS:"Windows_NT",PROCESSOR_ARCHITECTURE:"AMD64"}"""
        val filenameQ = JSONObject.quote(mainModuleFilename)
        return """
            <script>(function(){try{
            Object.defineProperty(navigator,'language',{get:function(){return $q;},configurable:true});
            Object.defineProperty(navigator,'languages',{get:function(){return [$q];},configurable:true});
            try{Object.defineProperty(navigator,'platform',{get:function(){return 'Win32';},configurable:true});}catch(_e){}
            if(typeof window.process!=='function'){window.process=function(){};window.process.env=$envLit;window.process.versions={};}
            else{if(!window.process.env){window.process.env=$envLit;}else{var __e=$envLit;for(var __k in __e){if(typeof window.process.env[__k]==='undefined'){window.process.env[__k]=__e[__k];}}}if(!window.process.versions){window.process.versions={};}}
            if(window.process&&!window.process.mainModule){window.process.mainModule={filename:$filenameQ};}
            try{if(window.process){var __ee=window.process;
            if(typeof __ee.on!=='function'){__ee.on=function(){return __ee;};}
            if(typeof __ee.once!=='function'){__ee.once=function(){return __ee;};}
            if(typeof __ee.off!=='function'){__ee.off=function(){return __ee;};}
            if(typeof __ee.addListener!=='function'){__ee.addListener=function(){return __ee;};}
            if(typeof __ee.removeListener!=='function'){__ee.removeListener=function(){return __ee;};}
            if(typeof __ee.removeAllListeners!=='function'){__ee.removeAllListeners=function(){return __ee;};}
            if(typeof __ee.emit!=='function'){__ee.emit=function(){return false;};}
            if(typeof __ee.listeners!=='function'){__ee.listeners=function(){return [];};}
            if(typeof __ee.listenerCount!=='function'){__ee.listenerCount=function(){return 0;};}
            }}catch(_e){}
            try{if(window.process&&typeof window.process.platform==='undefined'){window.process.platform='win32';}}catch(_e){}
            try{if(window.process&&typeof window.process.execPath==='undefined'){window.process.execPath='.\\game.exe';}}catch(_e){}
            try{if(window.process&&typeof window.process.arch==='undefined'){window.process.arch='x64';}}catch(_e){}
            try{if(typeof window.__gnPlatform==='undefined'){window.__gnPlatform='win32';}}catch(_e){}
            try{if(typeof window.c2nwjs==='undefined'){window.c2nwjs=true;}}catch(_e){}
            if(typeof window.global==='undefined'){try{window.global=window;}catch(_e){}}
            if(typeof window.module==='undefined'){try{window.module={};}catch(_e){}}
            if(window.global&&typeof window.global.gc!=='function'){try{window.global.gc=function(){};}catch(_e){}}
            }catch(e){}})();</script>
        """.trimIndent() + "\n"
    }

    // __gnElectronCtx pre-script. inlined BEFORE shim scripts so
    // packs/electron.js reads these values at parse time (getPath becomes a map lookup,
    // no bridge round-trip). values JSON-escaped via JSONObject.quote matching
    // buildLocaleScript precedent. keys are static ({productName, userData, appData,
    // documents, temp, home, version}); attacker-controllable values already validated
    // by SaveDirectoryResolver.validateProductName
    private fun buildElectronCtxScript(ctx: Map<String, String>): String {
        val entries = ctx.entries.joinToString(",") { (k, v) ->
            "${JSONObject.quote(k)}:${JSONObject.quote(v)}"
        }
        return "<script>(function(){try{" +
            "window.__gnElectronCtx={$entries};" +
            "}catch(e){}})();</script>\n"
    }

    // parse-time __gnGestureConfig snippet. injected BEFORE shim
    // scripts so touch.js reads cfg at parse time. live updates thereafter via
    // WebViewScreen evaluateJavascript gestureConfigJson is already a valid
    // JSON object string from TouchGestureConfig.toJson -- no escape needed (closed-set
    // enums + primitives only; org.json.JSONObject already escaped string contents).
    private fun buildGestureConfigScript(gestureConfigJson: String): String {
        return "<script>(function(){try{" +
            "window.__gnGestureConfig = $gestureConfigJson;" +
            "}catch(e){}})();</script>\n"
    }

    // NW.js parity: titles read `nw.App.argv` to extract Steam-passed launch args (e.g.
    // anti-piracy keys). when set, the nw shim mirrors __gnNwArgv into the deep proxy so
    // `String(nw.App.argv)` returns the actual `--<arg>` instead of a proxy stringification.
    // value is a JSON-serialized string array, e.g. `["--6bdb2e58..."]`.
    private fun buildNwArgvScript(nwArgvJson: String): String {
        return "<script>(function(){try{" +
            "window.__gnNwArgv = $nwArgvJson;" +
            "}catch(e){}})();</script>\n"
    }

    // pack:nwjs + Impact-engine parity: real NW.js sets nw.App.dataPath to %LOCALAPPDATA%/<App>.
    // Impact titles guard `if (nw.App.dataPath) useFs() else useLocalStorage()` -- empty string
    // is falsy, so they fall back to localStorage and writes never reach disk. Windows-form
    // path (e.g. "C:\\Users\\xuser\\AppData\\Local\\<App>") that fsBridge translates to
    // <wine>/drive_c/users/xuser/AppData/Local/<App>/. WebViewScreen passes this for pack:nwjs
    // containers; null for everything else.
    private fun buildNwAppDataPathScript(nwAppDataPath: String): String {
        // JSON-encode as a string literal for safe escape (handles backslashes / quotes).
        val asJsonString = org.json.JSONObject.quote(nwAppDataPath)
        return "<script>(function(){try{" +
            "window.__gnNwAppDataPath = $asJsonString;" +
            "}catch(e){}})();</script>\n"
    }

    // bridge-authoritative fs flag. emitted as `window.__gnFsBridgeOnly = true` BEFORE shim
    // scripts so fs.js sees it at first call. only emitted when the value is true (false
    // would just write `false`, but skipping the emit keeps byte-identical output for the
    // default-off packs and existing test fixtures).
    private fun buildFsBridgeOnlyScript(): String {
        return "<script>(function(){try{" +
            "window.__gnFsBridgeOnly = true;" +
            "}catch(e){}})();</script>\n"
    }

    // wine-parity TOUCHSCREEN_MODE persistence. ON = default (touch.js interprets gestures);
    // only emit when OFF so default state stays byte-identical with pre-feature output. touch.js
    // reads window.__gnTouchModeActive !== false at handler entry -- unset == true.
    private fun buildTouchscreenModeOffScript(): String {
        return "<script>(function(){try{" +
            "window.__gnTouchModeActive = false;" +
            "}catch(e){}})();</script>\n"
    }

    // perf: window.devicePixelRatio override. PIXI/C3 cache DPR at renderer init --
    // injecting BEFORE any game/shim script means the engine's backing-store math
    // (canvas.width = css × DPR²) reads our value, not the device-native one.
    // configurable:true so devtools / tests can override; non-enumerable so feature
    // detection (`'devicePixelRatio' in window`) still passes naturally.
    private fun buildDevicePixelRatioScript(scale: Float): String {
        // toString() on Float emits "1.0" / "1.5" -- JS Number parses both fine.
        return "<script>(function(){try{" +
            "Object.defineProperty(window,'devicePixelRatio',{get:function(){return $scale;},configurable:true});" +
            "}catch(e){}})();</script>\n"
    }

    // perf: when DPR < 1 the backing store is smaller than the CSS layout box, so Chromium
    // upscales the canvas at composition time. default filter is bilinear → blurry, especially
    // bad for the pixel-art-heavy html5 library (RMMV/RMMZ/Impact). image-rendering:pixelated
    // forces nearest-neighbor -- crisp pixels, no blur. DPR>=1 path is unchanged (no upscale to
    // smooth, so bilinear default is fine there).
    //
    // !important is load-bearing: PIXI/RMMV/Impact set `canvas.style.imageRendering` at runtime
    // (often to '-webkit-optimize-contrast' or 'auto'), and inline styles beat external
    // stylesheets without !important. multiple aliases declared so older Chromium WebViews that
    // don't recognize 'pixelated' still fall back to crisp-edges. -webkit-optimize-contrast
    // covers the legacy alias some engines write back.
    private val canvasCrispUpscaleStyle = """
        <style id="__gnCanvasCrispUpscale">
          canvas {
            image-rendering: crisp-edges !important;
            image-rendering: pixelated !important;
          }
        </style>
    """.trimIndent()

    // pack:unity force-fill. Unity's WebGL template sizes the canvas to 100%/position:fixed ONLY
    // inside an `if (/iPhone|iPad|iPod|Android/.test(navigator.userAgent))` branch; under our
    // desktop UA spoof that branch never runs, leaving the canvas at its inline 1280x720 (top-left,
    // most of it off-screen on a handheld). this mirrors that mobile branch's end state via
    // parse-time CSS so it lands before createUnityInstance reads canvas.clientWidth/Height for the
    // WebGL drawing buffer. body > canvas is engine-agnostic but only emitted for fillCanvas packs.
    private val canvasFillStyle = """
        <style id="__gnCanvasFill">
          html, body {
            width: 100% !important;
            height: 100% !important;
            margin: 0 !important;
            padding: 0 !important;
          }
          body > canvas {
            position: fixed !important;
            top: 0 !important;
            left: 0 !important;
            width: 100% !important;
            height: 100% !important;
          }
        </style>
    """.trimIndent()

    // legacy overload -- preserved for tests that pass individual params. new call sites should
    // construct an IndexInjectionConfig and call the primary overload below.
    fun inject(
        source: InputStream,
        shimScriptUrls: List<String>,
        locale: String? = null,
        electronCtx: Map<String, String>? = null,
        gestureConfigJson: String? = null,
        nwArgvJson: String? = null,
        nwAppDataPath: String? = null,
        mainModuleFilename: String = "",
        electronPreloadUrl: String? = null,
        renderScaleOverride: Float? = null,
        fsBridgeOnly: Boolean = false,
        touchscreenMode: Boolean = true,
        fillCanvas: Boolean = false,
    ): InputStream = inject(
        source = source,
        shimScriptUrls = shimScriptUrls,
        config = IndexInjectionConfig(
            locale = locale,
            electronCtx = electronCtx,
            gestureConfigJson = gestureConfigJson,
            nwArgvJson = nwArgvJson,
            nwAppDataPath = nwAppDataPath,
            mainModuleFilename = mainModuleFilename,
            electronPreloadUrl = electronPreloadUrl,
            renderScaleOverride = renderScaleOverride,
            fsBridgeOnly = fsBridgeOnly,
            touchscreenMode = touchscreenMode,
            fillCanvas = fillCanvas,
        ),
    )

    // primary entry -- all parse-time injection knobs travel as a single config DTO so future
    // knob additions don't widen this signature or the 4 method signatures that forward to it.
    fun inject(
        source: InputStream,
        shimScriptUrls: List<String>,
        config: IndexInjectionConfig,
    ): InputStream {
        val locale = config.locale
        val electronCtx = config.electronCtx
        val gestureConfigJson = config.gestureConfigJson
        val nwArgvJson = config.nwArgvJson
        val nwAppDataPath = config.nwAppDataPath
        val mainModuleFilename = config.mainModuleFilename
        val electronPreloadUrl = config.electronPreloadUrl
        val renderScaleOverride = config.renderScaleOverride
        val fsBridgeOnly = config.fsBridgeOnly
        val touchscreenMode = config.touchscreenMode
        val fillCanvas = config.fillCanvas
        val html = source.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val match = firstScript.find(html)
        val localeScript = if (locale != null) buildLocaleScript(locale, mainModuleFilename) else ""
        // optional __gnElectronCtx inline pre-snippet. empty when null so byte-identical
        // behavior is preserved for RMMV / C3 / sideloaded.
        val electronCtxScript = if (electronCtx != null) buildElectronCtxScript(electronCtx) else ""
        // optional __gnGestureConfig parse-time snippet. empty when null so
        // existing electronCtx/locale-only tests stay byte-identical (touch.js still defaults
        // safely when this is absent -- see DEFAULTS in shim).
        val gestureConfigScript = if (gestureConfigJson != null) buildGestureConfigScript(gestureConfigJson) else ""
        val nwArgvScript = if (nwArgvJson != null) buildNwArgvScript(nwArgvJson) else ""
        val nwAppDataPathScript = if (nwAppDataPath != null) buildNwAppDataPathScript(nwAppDataPath) else ""
        // EMITTED FIRST: must precede every other shim so DPR is overridden before any
        // engine init reads it. null leaves the prior byte-identical output intact.
        val dprScript = if (renderScaleOverride != null && renderScaleOverride > 0f) {
            buildDevicePixelRatioScript(renderScaleOverride)
        } else {
            ""
        }
        // crisp upscale: nearest-neighbor canvas scaling when DPR<1 -- Chromium's default
        // bilinear blurs sub-CSS-px backing stores into mush.
        val crispUpscaleStyle = if (renderScaleOverride != null && renderScaleOverride in 0.001f..0.999f) {
            canvasCrispUpscaleStyle + "\n"
        } else {
            ""
        }
        val fsBridgeOnlyScript = if (fsBridgeOnly) buildFsBridgeOnlyScript() else ""
        val touchscreenModeScript = if (!touchscreenMode) buildTouchscreenModeOffScript() else ""
        val fillCanvasBlock = if (fillCanvas) canvasFillStyle + "\n" else ""

        // no <script> anchor in source.
        if (match == null) {
            // locale-only OR electronCtx-only OR gestureConfig-only OR nwArgv-only (or any combo),
            // shims empty: synthesize minimal <head> block so all side-channels still reach the DOM.
            // overscrollFixStyle is ALWAYS emitted here too -- document scroll/zoom suppression
            // must apply even when no game scripts are anchored yet.
            if ((locale != null || electronCtx != null || gestureConfigJson != null || nwArgvJson != null || nwAppDataPath != null || dprScript.isNotEmpty() || fsBridgeOnlyScript.isNotEmpty() || touchscreenModeScript.isNotEmpty()) && shimScriptUrls.isEmpty()) {
                return ("<head>$dprScript$crispUpscaleStyle$localeScript$overscrollFixStyle\n$electronCtxScript$gestureConfigScript$touchscreenModeScript$nwArgvScript$nwAppDataPathScript$fsBridgeOnlyScript</head>$html").byteInputStream(Charsets.UTF_8)
            }
            // shims need a game-script anchor to sit before -- fail-loud invariant.
            error("index.html has no <script> tag — cannot inject shims")
        }

        val scriptInjection = shimScriptUrls.joinToString("\n") {
            """<script src="$it"></script>"""
        } + "\n"
        // preload runs AFTER shims so it can call require('electron').contextBridge -- that
        // module isn't registered until pack-electron.js executes.
        val preloadInjection = if (electronPreloadUrl != null) {
            """<script src="$electronPreloadUrl"></script>""" + "\n"
        } else {
            ""
        }
        // order (top → bottom): dpr, locale, canvas-fix-style, overscroll-fix-style, electronCtx,
        // gestureConfig, nwArgv, shim scripts, electron preload, game. parse-time pre-snippets
        // MUST precede scriptInjection so shims (touch.js / nw.js) read their globals at parse
        // time. dpr leads the pack -- overrides window.devicePixelRatio before ANY game/shim code
        // reads it (PIXI/C3 cache DPR at renderer init). preload sits between shims and game so
        // contextBridge.exposeInMainWorld lands before the game's first script runs.
        val rewritten = html.substring(0, match.range.first) +
            dprScript +
            crispUpscaleStyle +
            localeScript +
            canvasFixStyle + "\n" +
            overscrollFixStyle + "\n" +
            fillCanvasBlock +
            electronCtxScript +
            gestureConfigScript +
            touchscreenModeScript +
            nwArgvScript +
            nwAppDataPathScript +
            fsBridgeOnlyScript +
            scriptInjection +
            preloadInjection +
            html.substring(match.range.first)
        return rewritten.byteInputStream(Charsets.UTF_8)
    }
}
