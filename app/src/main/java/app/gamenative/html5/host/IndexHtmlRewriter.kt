package app.gamenative.html5.host

import java.io.InputStream
import org.json.JSONObject

// injects shim <script> tags BEFORE the first game <script>. fails loud if there is none: unshimmed HTML
// would let game JS run without our shims.
object IndexHtmlRewriter {
    private val firstScript = Regex("<script\\b", RegexOption.IGNORE_CASE)

    // RMMV's Graphics._centerElement (`top:0; bottom:0; margin:auto`) resolves to negative margins in our
    // Compose-hosted WebView, putting the top half of the canvas off-screen. pin to the top instead;
    // re-centering via `top: 50vh + translateY(-50%)` did NOT work. parse-time + !important beats the
    // engine's inline styles.
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

    // pan-x pan-y: `none` breaks C3 pointer-event synthesis, `manipulation` still allows pinch-zoom.
    private val overscrollFixStyle = """
        <style id="__gnOverscrollFix">
          html, body {
            overflow: hidden !important;
            overscroll-behavior: none !important;
            touch-action: pan-x pan-y !important;
          }
        </style>
    """.trimIndent()

    // pins navigator.language/languages and builds a NW.js-style window.process (env, versions, mainModule,
    // execPath, ...) that engine bootstrap code reads at parse time.
    //
    // CRITICAL: process is a FUNCTION, not an object. RMMV's Utils.isNwjs() checks
    // `typeof process === 'object'`; true would make YEP_CoreEngine.initNwjs crash on require('nw.gui').
    //
    // env mirrors the wine prefix layout with forward slashes (our path.js is posix).
    //
    // execPath is dot-relative so C2/C3 NodeWebkit plugins' path.dirname(execPath) resolves to the install
    // dir; a wine-absolute path made them look for external files in AppData.
    //
    // CRITICAL: do NOT pre-initialize `module.exports`. UMD libraries (jQuery, etc.) would take the CommonJS
    // branch and never set their global.
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
            try{if(window.process&&typeof window.process.cwd!=='function'){window.process.cwd=function(){return '/';};}}catch(_e){}
            try{if(typeof window.__gnPlatform==='undefined'){window.__gnPlatform='win32';}}catch(_e){}
            try{if(typeof window.c2nwjs==='undefined'){window.c2nwjs=true;}}catch(_e){}
            if(typeof window.global==='undefined'){try{window.global=window;}catch(_e){}}
            if(typeof window.module==='undefined'){try{window.module={};}catch(_e){}}
            if(window.global&&typeof window.global.gc!=='function'){try{window.global.gc=function(){};}catch(_e){}}
            }catch(e){}})();</script>
        """.trimIndent() + "\n"
    }

    // values come from the game's package.json, hence the quoting.
    private fun buildElectronCtxScript(ctx: Map<String, String>): String {
        val entries = ctx.entries.joinToString(",") { (k, v) ->
            "${JSONObject.quote(k)}:${JSONObject.quote(v)}"
        }
        return "<script>(function(){try{" +
            "window.__gnElectronCtx={$entries};" +
            "}catch(e){}})();</script>\n"
    }

    // gestureConfigJson is already valid JSON from TouchGestureConfig.toJson.
    private fun buildGestureConfigScript(gestureConfigJson: String): String {
        return "<script>(function(){try{" +
            "window.__gnGestureConfig = $gestureConfigJson;" +
            "}catch(e){}})();</script>\n"
    }

    // some titles read Steam launch args (e.g. decrypt keys) from nw.App.argv. JSON string array.
    private fun buildNwArgvScript(nwArgvJson: String): String {
        return "<script>(function(){try{" +
            "window.__gnNwArgv = $nwArgvJson;" +
            "}catch(e){}})();</script>\n"
    }

    // Impact titles only save to disk when nw.App.dataPath is truthy. the Windows-form path is mapped into
    // the wine prefix by the fs bridge.
    private fun buildNwAppDataPathScript(nwAppDataPath: String): String {
        val asJsonString = org.json.JSONObject.quote(nwAppDataPath)
        return "<script>(function(){try{" +
            "window.__gnNwAppDataPath = $asJsonString;" +
            "}catch(e){}})();</script>\n"
    }

    private fun buildFsBridgeOnlyScript(): String {
        return "<script>(function(){try{" +
            "window.__gnFsBridgeOnly = true;" +
            "}catch(e){}})();</script>\n"
    }

    // only emitted when OFF; touch.js treats unset as on.
    private fun buildTouchscreenModeOffScript(): String {
        return "<script>(function(){try{" +
            "window.__gnTouchModeActive = false;" +
            "}catch(e){}})();</script>\n"
    }

    // PIXI/C3 cache DPR at renderer init, so this must precede any game/shim script.
    private fun buildDevicePixelRatioScript(scale: Float): String {
        return "<script>(function(){try{" +
            "Object.defineProperty(window,'devicePixelRatio',{get:function(){return $scale;},configurable:true});" +
            "}catch(e){}})();</script>\n"
    }

    // DPR < 1 means chromium upscales the canvas bilinearly, which blurs pixel art; force nearest-neighbor.
    // !important is load-bearing: engines set canvas.style.imageRendering at runtime. crisp-edges is the
    // fallback for WebViews without 'pixelated'.
    private val canvasCrispUpscaleStyle = """
        <style id="__gnCanvasCrispUpscale">
          canvas {
            image-rendering: crisp-edges !important;
            image-rendering: pixelated !important;
          }
        </style>
    """.trimIndent()

    // reproduces Unity's mobile-UA canvas sizing, which our desktop UA spoof skips. parse-time so it lands
    // before createUnityInstance reads the canvas size for the drawing buffer.
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

    // test convenience overload; production builds an IndexInjectionConfig.
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
        val electronCtxScript = if (electronCtx != null) buildElectronCtxScript(electronCtx) else ""
        val gestureConfigScript = if (gestureConfigJson != null) buildGestureConfigScript(gestureConfigJson) else ""
        val nwArgvScript = if (nwArgvJson != null) buildNwArgvScript(nwArgvJson) else ""
        val nwAppDataPathScript = if (nwAppDataPath != null) buildNwAppDataPathScript(nwAppDataPath) else ""
        val dprScript = if (renderScaleOverride != null && renderScaleOverride > 0f) {
            buildDevicePixelRatioScript(renderScaleOverride)
        } else {
            ""
        }
        val crispUpscaleStyle = if (renderScaleOverride != null && renderScaleOverride in 0.001f..0.999f) {
            canvasCrispUpscaleStyle + "\n"
        } else {
            ""
        }
        val fsBridgeOnlyScript = if (fsBridgeOnly) buildFsBridgeOnlyScript() else ""
        val touchscreenModeScript = if (!touchscreenMode) buildTouchscreenModeOffScript() else ""
        val fillCanvasBlock = if (fillCanvas) canvasFillStyle + "\n" else ""

        if (match == null) {
            // no anchor and no shims: synthesize a <head> so the parse-time globals still reach the DOM.
            if ((locale != null || electronCtx != null || gestureConfigJson != null || nwArgvJson != null || nwAppDataPath != null || dprScript.isNotEmpty() || fsBridgeOnlyScript.isNotEmpty() || touchscreenModeScript.isNotEmpty()) && shimScriptUrls.isEmpty()) {
                return ("<head>$dprScript$crispUpscaleStyle$localeScript$overscrollFixStyle\n$electronCtxScript$gestureConfigScript$touchscreenModeScript$nwArgvScript$nwAppDataPathScript$fsBridgeOnlyScript</head>$html").byteInputStream(Charsets.UTF_8)
            }
            error("index.html has no <script> tag — cannot inject shims")
        }

        val scriptInjection = shimScriptUrls.joinToString("\n") {
            """<script src="$it"></script>"""
        } + "\n"
        // AFTER shims: require('electron') isn't registered until pack-electron.js runs.
        val preloadInjection = if (electronPreloadUrl != null) {
            """<script src="$electronPreloadUrl"></script>""" + "\n"
        } else {
            ""
        }
        // ORDER: dpr first (read at engine init); the parse-time globals MUST precede the shims that read them;
        // preload sits between shims and game so contextBridge exposures exist before game code runs.
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
