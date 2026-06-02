package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// pure-jvm asset-content lock for 3 shims. asserts the contract each shim ships with
// so a future edit that drops a method or renames a bridge surface fails the test immediately.
// no WebView / Android deps — just file reads (Gradle cwd=app/, CI cwd=root — both paths tried).
class ShimScriptTest {

    private fun readShim(name: String): String {
        val candidates = listOf(
            File("src/main/assets/html5/shims/$name"),
            File("app/src/main/assets/html5/shims/$name"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("$name not found; tried: ${candidates.map { it.absolutePath }}")
        return f.readText()
    }

    // ---------------- require-dispatcher.js 1) ----------------

    @Test
    fun requireDispatcher_installsWindowRequire() {
        val js = readShim("require-dispatcher.js")
        assertTrue("dispatcher must assign window.require", js.contains("window.require = myRequire"))
    }

    @Test
    fun requireDispatcher_exposesRegister() {
        val js = readShim("require-dispatcher.js")
        assertTrue("dispatcher must expose .register(id, impl)", js.contains("myRequire.register = function"))
    }

    @Test
    fun requireDispatcher_ownsRequireMainFilenameStub() {
        // ownership moved from steamworks.js to require-dispatcher.js.
        val js = readShim("require-dispatcher.js")
        assertTrue(js.contains("require.main") && js.contains("filename"))
    }

    @Test
    fun requireDispatcher_noNetworkCalls() {
        val js = readShim("require-dispatcher.js")
        assertFalse(js.contains("fetch("))
        assertFalse(js.contains("XMLHttpRequest"))
    }

    @Test
    fun requireDispatcher_doesNotClobberProcess() {
        // IndexHtmlRewriter.buildLocaleScript intentionally sets window.process as a
        // FUNCTION (not object) to keep Utils.isNwjs() false and avoid YEP_CoreEngine's
        // `require('nw.gui').Window.get()` crash. dispatcher must NOT overwrite process.
        // save routing happens via StorageManager.isLocalMode override in fs.js (fsJs_forcesIsLocalModeTrue).
        val js = readShim("require-dispatcher.js")
        assertFalse("dispatcher must NOT assign window.process", js.contains("window.process ="))
        assertFalse("dispatcher must NOT define process.versions", js.contains("process.versions ="))
    }

    // ---------------- fs.js ----------------

    @Test
    fun fsJs_containsAll9V1Methods() {
        val js = readShim("fs.js")
        val v1 = listOf(
            "writeFileSync", "readFileSync", "existsSync", "unlinkSync", "statSync",
            "mkdirSync", "readdirSync", "renameSync", "appendFileSync",
        )
        v1.forEach { m ->
            assertTrue("fs.js must dispatch $m", js.contains("$m:") || js.contains("$m: $m"))
        }
    }

    @Test
    fun fsJs_unknownMethodThrowsNotImplemented() {
        val js = readShim("fs.js")
        assertTrue(
            "fs.js must throw NOT_IMPLEMENTED_V1 in unknown-method Proxy path",
            js.contains("NOT_IMPLEMENTED_V1: fs."),
        )
    }

    @Test
    fun fsJs_referencesFsBridgeByName() {
        val js = readShim("fs.js")
        assertTrue("fs.js must reference __gnFsBridge", js.contains("__gnFsBridge"))
    }

    @Test
    fun fsJs_registersViaDispatcher() {
        val js = readShim("fs.js")
        assertTrue(js.contains("window.require.register('fs'"))
    }

    @Test
    fun fsJs_usesProxyFallback() {
        val js = readShim("fs.js")
        assertTrue(js.contains("new Proxy"))
    }

    @Test
    fun fsJs_promisesApiThrows() {
        // fs.promises.* all reject with NOT_IMPLEMENTED_V1.
        val js = readShim("fs.js")
        assertTrue(
            js.contains("NOT_IMPLEMENTED_V1: fs.promises.") ||
                js.contains("NOT_IMPLEMENTED_V1: fs.' + name") ||
                js.contains("Promise.reject"),
        )
    }

    @Test
    fun fsJs_supportsBase64Encoding() {
        val js = readShim("fs.js")
        assertTrue("base64 encode path for binary writes", js.contains("btoa") || js.contains("toBase64"))
        assertTrue("base64 decode path for binary reads", js.contains("atob") || js.contains("fromBase64"))
    }

    @Test
    fun fsJs_absolutePathReadsUseAssetInterceptor() {
        // Curious Expedition reads /img/*.json and /langs/*.csv via fs.readFileSync.
        // post-c2 (Hypnospace) the contract is bridge-FIRST then asset-fallback: c2 NW.js
        // titles compose absolute SAVE paths (`/.local/<vendor>/...`) that need bridge
        // routing; CE's absolute ASSET paths still hit the asset interceptor on bridge miss.
        // user-data absolutes (`/.foo/...` hidden dirs) are bridge-only — see
        // looksLikeUserDataAbsolute. lock the helpers + both routing branches.
        val js = readShim("fs.js")
        assertTrue("fs.js must define asset-read helper", js.contains("function assetTryReadSync"))
        assertTrue("fs.js must define asset-exists helper", js.contains("function assetExistsSync"))
        assertTrue("fs.js must branch on absolute path for reads", js.contains("isAssetPath(pth)"))
        assertTrue(
            "fs.js must define bridgeRel helper for absolute-path bridge routing",
            js.contains("function bridgeRel"),
        )
        assertTrue(
            "fs.js must skip asset XHR for user-data absolutes (silence DNS noise)",
            js.contains("looksLikeUserDataAbsolute"),
        )
    }

    @Test
    fun fsJs_readdirSyncRoutesAbsolutePathsToListdirEndpoint() {
        // CE scans /conf, /langs to discover mod configs. readdirSync must hit
        // the synthetic /_asar_listdir endpoint, not the save-sandbox bridge.
        val js = readShim("fs.js")
        assertTrue("readdirSync must branch on asset paths", js.contains("isAssetPath(pth)"))
        assertTrue("readdirSync must call the listdir endpoint", js.contains("/_asar_listdir"))
    }

    @Test
    fun fsJs_asyncReadFileDispatched() {
        // CE's resource loader uses node callback-style fs.readFile(path, opts, cb).
        // dispatch table must expose it; readFile must route absolute paths the same way
        // readFileSync does.
        val js = readShim("fs.js")
        assertTrue("fs.js must define async readFile", js.contains("function readFile(pth"))
        assertTrue("fs.js must expose readFile in dispatch", js.contains("readFile: readFile"))
    }

    // ---------------- Buffer — extension ----------------

    @Test
    fun fsJs_installsWindowBuffer() {
        val js = readShim("fs.js")
        assertTrue("fs.js must install window.Buffer per Q1", js.contains("window.Buffer") || js.contains("window['Buffer']"))
    }

    @Test
    fun fsJs_bufferFromKnownEncodings() {
        val js = readShim("fs.js")
        // minimal surface: utf8 / base64 / hex
        assertTrue(js.contains("function bufferFrom") || js.contains("bufferFrom("))
        assertTrue(js.contains("'utf8'") || js.contains("'utf-8'"))
        assertTrue(js.contains("'base64'"))
    }

    @Test
    fun fsJs_bufferUnknownMethodThrowsNotImplemented() {
        val js = readShim("fs.js")
        assertTrue(
            "Buffer.<unknown> must NOT_IMPLEMENTED_V1 throw",
            js.contains("NOT_IMPLEMENTED_V1: Buffer."),
        )
        assertTrue(
            "buf.<unknown> instance method must NOT_IMPLEMENTED_V1 throw",
            js.contains("NOT_IMPLEMENTED_V1: buf."),
        )
    }

    @Test
    fun fsJs_bufferInstanceMarker() {
        val js = readShim("fs.js")
        // __isGnBuffer marker enables writeFileSync to route the base64 branch
        assertTrue(js.contains("__isGnBuffer"))
    }

    @Test
    fun fsJs_bufferIsBufferHelper() {
        val js = readShim("fs.js")
        assertTrue(js.contains("isBuffer"))
    }

    // ---------------- StorageManager.isLocalMode override ----------------

    @Test
    fun fsJs_forcesIsLocalModeTrue() {
        // route RMMV/RMMZ saves through fs bridge without flipping Utils.isNwjs
        // (which would crash YEP_CoreEngine on require('nw.gui').Window.get()).
        val js = readShim("fs.js")
        assertTrue("must override StorageManager.isLocalMode", js.contains("sm.isLocalMode = function"))
        assertTrue("override must return true", js.contains("return true"))
        assertTrue("must set idempotent marker", js.contains("__gnIsLocalModeForced"))
    }

    @Test
    fun fsJs_isLocalModeOverrideIsBounded() {
        // must stop polling so engines without StorageManager (C3, Electron) don't leak intervals.
        val js = readShim("fs.js")
        assertTrue("must use setInterval for polling", js.contains("setInterval"))
        assertTrue("must clear interval on success/timeout", js.contains("clearInterval"))
        assertTrue("must bound attempts", js.contains("maxAttempts"))
    }

    // ---------------- path.js ----------------

    @Test
    fun pathJs_exposesPosixSep() {
        val js = readShim("path.js")
        assertTrue(js.contains("sep: '/'"))
    }

    @Test
    fun pathJs_exposesSixFunctionsPerSpec() {
        val js = readShim("path.js")
        listOf("join:", "resolve:", "normalize:", "dirname:", "basename:", "extname:").forEach {
            assertTrue("path.js must expose $it", js.contains(it))
        }
    }

    @Test
    fun pathJs_registersViaDispatcher() {
        val js = readShim("path.js")
        assertTrue(js.contains("window.require.register('path'"))
    }

    @Test
    fun pathJs_noBridgeCall() {
        // pure-JS string math; no bridge.
        val js = readShim("path.js")
        assertFalse("path.js must NOT call any host-side bridge", js.contains("__gnFsBridge"))
        assertFalse(js.contains("__gnSteamworksBridge"))
    }

    @Test
    fun pathJs_normalize_preservesTrailingSlash() {
        // 3 cloud-sync gap: RMMZ StorageManager does `fileDirectoryPath() + saveName`
        // string concat; collapsing `./save/` to `save` breaks the save path.
        // lock the two sentinel lines so nobody "simplifies" this away.
        val js = readShim("path.js")
        assertTrue(
            "normalize must detect trailing slash",
            js.contains("p.charAt(p.length - 1) === '/'"),
        )
        assertTrue(
            "normalize must re-append trailing slash when present",
            js.contains("if (trailing && result.length > 0) result += '/'"),
        )
    }

    @Test
    fun pathJs_exposesFullNodeSurface() {
        // CE hit `path.isAbsolute is not a function`. lock the full node posix
        // surface so future electron titles don't re-trip on missing methods.
        val js = readShim("path.js")
        listOf("isAbsolute:", "relative:", "parse:", "format:", "toNamespacedPath:").forEach {
            assertTrue("path.js must expose $it", js.contains(it))
        }
    }

    @Test
    fun pathJs_exposesPosixAndWin32NamespaceAliases() {
        // node libs often reach for path.posix.join / path.posix.sep even in posix-only code.
        // alias both to the same object.
        val js = readShim("path.js")
        assertTrue("path.posix alias", js.contains("path.posix = path"))
        assertTrue("path.win32 alias", js.contains("path.win32 = path"))
    }

    // ---------------- steamworks.js chain refactor ----------------

    @Test
    fun steamworksJs_chainsGreenworksOntoDispatcher() {
        val js = readShim("steamworks.js")
        assertTrue(js.contains("window.require.register('greenworks', proxy)"))
        assertTrue(js.contains("window.require.register('./greenworks', proxy)"))
        assertTrue(js.contains("window.require.register('steamworks.js', proxy)"))
    }

    @Test
    fun steamworksJs_preserves30Exports() {
        // existing phase-2 contract — reassert post-refactor.
        val js = readShim("steamworks.js")
        val expectedExports = listOf(
            "init", "initAPI", "restartAppIfNecessary", "isSteamRunning", "isSteamRunningOnSteamDeck", "getAppId",
            "getSteamId", "getPersonaName", "getCurrentGameLanguage", "getCurrentUILanguage",
            "activateAchievement", "getAchievement", "clearAchievement", "getAchievementNames",
            "getNumberOfAchievements", "indicateAchievementProgress",
            "getStatInt", "getStatFloat", "setStat", "storeStats",
            "saveTextToFile", "writeTextToFile", "readTextFromFile", "deleteFile",
            "isCloudEnabled", "isCloudEnabledForUser",
            "activateGameOverlay", "isGameOverlayEnabled", "on", "isSubscribedApp",
        )
        assertEquals(30, expectedExports.size)
        expectedExports.forEach { e ->
            assertTrue("missing export $e after refactor", js.contains("$e:"))
        }
    }

    // ---------------- ShimBundles registry entries ----------------

    @Test
    fun shimBundles_requireDispatcher_registered() {
        assertEquals("/_shims/require-dispatcher.js", ShimBundles.urlFor(ShimBundles.REQUIRE_DISPATCHER_ID))
        assertEquals("html5/shims/require-dispatcher.js", ShimBundles.assetPathFor(ShimBundles.REQUIRE_DISPATCHER_ID))
    }

    @Test
    fun shimBundles_fs_registered() {
        assertEquals("/_shims/fs.js", ShimBundles.urlFor(ShimBundles.FS_SHIM_ID))
        assertEquals("html5/shims/fs.js", ShimBundles.assetPathFor(ShimBundles.FS_SHIM_ID))
    }

    @Test
    fun shimBundles_path_registered() {
        assertEquals("/_shims/path.js", ShimBundles.urlFor(ShimBundles.PATH_SHIM_ID))
        assertEquals("html5/shims/path.js", ShimBundles.assetPathFor(ShimBundles.PATH_SHIM_ID))
    }

}
