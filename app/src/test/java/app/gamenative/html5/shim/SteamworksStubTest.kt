package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// pure-jvm: ShimBundles is pure constants; SteamworksJsBridge is exercised via the
// logToFile override so the tempFolder sink is used — no DownloadService init needed.
// @JavascriptInterface annotation is metadata-only on the JVM classpath, so the
// class loads fine without Robolectric.
class SteamworksStubTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // factory for the logToFile tests below — they only exercise the JSONL sink, not the
    // achievements/stats surface, so we don't care about gseDir contents.
    private fun bridgeForLogging(containerId: String = "test-container-1234"): SteamworksJsBridge =
        SteamworksJsBridge(
            containerId = containerId,
            appId = 0,
            gseDir = tempFolder.newFolder("gse-${System.nanoTime()}"),
        )

    // --- ShimBundles registry ---

    @Test
    fun shimBundles_assetPathFor_steamworksNoop_returnsShimAssetPath() {
        assertEquals("html5/shims/steamworks.js", ShimBundles.assetPathFor("steamworks-noop"))
    }

    @Test
    fun shimBundles_urlFor_steamworksNoop_returnsShimsUrl() {
        assertEquals("/_shims/steamworks.js", ShimBundles.urlFor("steamworks-noop"))
    }

    @Test
    fun shimBundles_assetPathFor_cleanUnknownBundle_derivesConventionalPath() {
        // convention-fallback refactor: clean unknown id -> html5/shims/<id>.js (was null).
        assertEquals("html5/shims/unknown-bundle.js", ShimBundles.assetPathFor("unknown-bundle"))
        assertNull(ShimBundles.assetPathFor("../escape"))
    }

    // --- SteamworksJsBridge.logToFile ---

    @Test
    fun logToFile_appendsRecordWithNewline() {
        val file = tempFolder.newFile("steamworks.jsonl")
        val bridge = bridgeForLogging()
        val record = """{"ts":"2026-04-17T00:00:00Z","export":"init","args":[],"returnedDefault":true}"""

        bridge.logToFile(record, file)

        assertEquals("$record\n", file.readText())
    }

    @Test
    fun logToFile_secondCallAppends_fileContainsTwoLines() {
        val file = tempFolder.newFile("steamworks.jsonl")
        val bridge = bridgeForLogging()
        val a = """{"export":"init"}"""
        val b = """{"export":"initAPI"}"""

        bridge.logToFile(a, file)
        bridge.logToFile(b, file)

        val lines = file.readText().lines()
        // "a\nb\n".lines() -> ["a","b",""]
        assertEquals(3, lines.size)
        assertEquals(a, lines[0])
        assertEquals(b, lines[1])
        assertEquals("", lines[2])
    }

    @Test
    fun logToFile_createsParentDir_whenMissing() {
        val nested = File(tempFolder.root, "deep/nested/dirs/steamworks.jsonl")
        assertTrue(!nested.parentFile!!.exists())
        val bridge = bridgeForLogging()

        // must not throw
        bridge.logToFile("""{"export":"init"}""", nested)

        assertTrue(nested.exists())
        assertTrue(nested.readText().startsWith("""{"export":"init"}"""))
    }

    // --- contract lock: assert the 30 exports live in steamworks.js ---

    // gradle runs :app unit tests with cwd=<project>/app, so the asset path resolves
    // as "src/main/assets/..." from here. fall back to "app/src/main/..." if run from
    // project root (CI or other runners).
    private fun readShimJs(): String {
        val candidates = listOf(
            File("src/main/assets/html5/shims/steamworks.js"),
            File("app/src/main/assets/html5/shims/steamworks.js"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("steamworks.js not found; tried: ${candidates.map { it.absolutePath }}")
        return f.readText()
    }

    @Test
    fun steamworksJs_containsAll30ExportsFromResearchTable() {
        val expected = listOf(
            // init (6)
            "init", "initAPI", "restartAppIfNecessary", "isSteamRunning", "isSteamRunningOnSteamDeck", "getAppId",
            // user (4)
            "getSteamId", "getPersonaName", "getCurrentGameLanguage", "getCurrentUILanguage",
            // achievements (6)
            "activateAchievement", "getAchievement", "clearAchievement", "getAchievementNames",
            "getNumberOfAchievements", "indicateAchievementProgress",
            // stats (4)
            "getStatInt", "getStatFloat", "setStat", "storeStats",
            // cloud (6)
            "saveTextToFile", "writeTextToFile", "readTextFromFile", "deleteFile",
            "isCloudEnabled", "isCloudEnabledForUser",
            // overlay (4)
            "activateGameOverlay", "isGameOverlayEnabled", "on", "isSubscribedApp",
        )
        assertEquals(30, expected.size)

        val js = readShimJs()
        expected.forEach { export ->
            // match `<export>:` in the dispatch object — anchors to property-definition form
            assertTrue("missing export $export", js.contains("$export:"))
        }
    }

    @Test
    fun steamworksJs_noNetworkCalls() {
        val js = readShimJs()
        assertTrue("stub must not contain fetch()", !js.contains("fetch("))
        assertTrue("stub must not contain XMLHttpRequest", !js.contains("XMLHttpRequest"))
    }

    @Test
    fun steamworksJs_containsProxyFallback() {
        val js = readShimJs()
        assertTrue(js.contains("new Proxy(dispatch,"))
    }

    // --- drift tests: 10 dispatch entries route through __gnSteamworksBridge ---
    // each test asserts the named entry's function body contains the matching bridge call.
    // pattern matches `<name>: function...__gnSteamworksBridge.<name>` — locks the wiring 

    @Test
    fun steamworksJs_activateAchievement_callsBridge() {
        val js = readShimJs()
        val pattern = Regex("activateAchievement\\s*:\\s*function[\\s\\S]+?__gnSteamworksBridge\\.activateAchievement")
        assertTrue("steamworks.js activateAchievement must dispatch to __gnSteamworksBridge", pattern.containsMatchIn(js))
    }

    @Test
    fun steamworksJs_clearAchievement_callsBridge() {
        val js = readShimJs()
        val pattern = Regex("clearAchievement\\s*:\\s*function[\\s\\S]+?__gnSteamworksBridge\\.clearAchievement")
        assertTrue("steamworks.js clearAchievement must dispatch to __gnSteamworksBridge", pattern.containsMatchIn(js))
    }

    @Test
    fun steamworksJs_getAchievement_callsBridge() {
        val js = readShimJs()
        val pattern = Regex("getAchievement\\s*:\\s*function[\\s\\S]+?__gnSteamworksBridge\\.getAchievement")
        assertTrue("steamworks.js getAchievement must dispatch to __gnSteamworksBridge", pattern.containsMatchIn(js))
    }

    @Test
    fun steamworksJs_getAchievementNames_callsBridge() {
        val js = readShimJs()
        val pattern = Regex("getAchievementNames\\s*:\\s*function[\\s\\S]+?__gnSteamworksBridge\\.getAchievementNames")
        assertTrue("steamworks.js getAchievementNames must dispatch to __gnSteamworksBridge", pattern.containsMatchIn(js))
    }

    @Test
    fun steamworksJs_getNumberOfAchievements_callsBridge() {
        val js = readShimJs()
        val pattern = Regex("getNumberOfAchievements\\s*:\\s*function[\\s\\S]+?__gnSteamworksBridge\\.getNumberOfAchievements")
        assertTrue("steamworks.js getNumberOfAchievements must dispatch to __gnSteamworksBridge", pattern.containsMatchIn(js))
    }

    @Test
    fun steamworksJs_setStat_callsBridge() {
        val js = readShimJs()
        val pattern = Regex("setStat\\s*:\\s*function[\\s\\S]+?__gnSteamworksBridge\\.setStat")
        assertTrue("steamworks.js setStat must dispatch to __gnSteamworksBridge", pattern.containsMatchIn(js))
    }

    @Test
    fun steamworksJs_getStatInt_callsBridge() {
        val js = readShimJs()
        val pattern = Regex("getStatInt\\s*:\\s*function[\\s\\S]+?__gnSteamworksBridge\\.getStatInt")
        assertTrue("steamworks.js getStatInt must dispatch to __gnSteamworksBridge", pattern.containsMatchIn(js))
    }

    @Test
    fun steamworksJs_getStatFloat_callsBridge() {
        val js = readShimJs()
        val pattern = Regex("getStatFloat\\s*:\\s*function[\\s\\S]+?__gnSteamworksBridge\\.getStatFloat")
        assertTrue("steamworks.js getStatFloat must dispatch to __gnSteamworksBridge", pattern.containsMatchIn(js))
    }

    @Test
    fun steamworksJs_storeStats_callsBridge() {
        val js = readShimJs()
        val pattern = Regex("storeStats\\s*:\\s*function[\\s\\S]+?__gnSteamworksBridge\\.storeStats")
        assertTrue("steamworks.js storeStats must dispatch to __gnSteamworksBridge", pattern.containsMatchIn(js))
    }

    @Test
    fun steamworksJs_requestStats_callsBridge() {
        val js = readShimJs()
        val pattern = Regex("requestStats\\s*:\\s*function[\\s\\S]+?__gnSteamworksBridge\\.requestStats")
        assertTrue("steamworks.js requestStats must dispatch to __gnSteamworksBridge", pattern.containsMatchIn(js))
    }

    // indicateAchievementProgress STAYS noop. no native progress UI in v1.
    @Test
    fun steamworksJs_indicateAchievementProgress_staysNoop() {
        val js = readShimJs()
        val entry = Regex("indicateAchievementProgress\\s*:\\s*function[\\s\\S]+?},").find(js)?.value
            ?: error("indicateAchievementProgress entry not found")
        assertFalse(
            "indicateAchievementProgress must not call bridge",
            entry.contains("__gnSteamworksBridge.indicateAchievementProgress"),
        )
    }

    // bridge-routed entries MUST keep logCall for diagnostic continuity 
    @Test
    fun steamworksJs_bridgeRoutedEntries_preserveLogCall() {
        val js = readShimJs()
        listOf("activateAchievement", "clearAchievement", "getAchievement", "setStat", "storeStats").forEach { name ->
            val entry = Regex("$name\\s*:\\s*function[\\s\\S]+?},").find(js)?.value
                ?: error("$name entry not found")
            assertTrue(
                "$name must keep logCall for diagnostic continuity",
                entry.contains("logCall("),
            )
        }
    }

    // init / initAPI / getAppId stay noop.
    @Test
    fun steamworksJs_initStaysNoop() {
        val js = readShimJs()
        listOf("init", "initAPI", "getAppId").forEach { name ->
            // anchor to property-definition form; capture the function body up to `},`.
            val entry = Regex("(?<![A-Za-z_])$name\\s*:\\s*function[\\s\\S]+?},").find(js)?.value
                ?: error("$name entry not found")
            assertFalse(
                "$name must NOT call bridge — out of 6.5 scope",
                entry.contains("__gnSteamworksBridge.$name"),
            )
        }
    }
}
