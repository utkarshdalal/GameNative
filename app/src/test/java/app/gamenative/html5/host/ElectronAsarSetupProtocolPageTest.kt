package app.gamenative.html5.host

import app.gamenative.html5.asar.AsarTestFixtures
import app.gamenative.html5.profile.EnginePackId
import app.gamenative.html5.profile.EngineProfile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// an app whose asar holds only main + preload and whose main serves the page over https from resources/<dir>.
class ElectronAsarSetupProtocolPageTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val main = """
        "use strict";const e=require("electron"),t=require("node:path"),h="example.test",x="/__game__/",u=`https://${'$'}{h}${'$'}{x}index.html`;
        const w=t.join(process.resourcesPath,"site");
        async function f(i){const s=new URL(i.url);if(s.pathname===`${'$'}{x}index.html`)return new Response('<html><body><script src="./main.js"><\/script></body></html>');return e.net.fetch(i)}
        e.ipcMain.on("game:exit",t=>{ok(t)&&e.app.quit()});e.ipcMain.on("game:loaded",t=>{ok(t)&&t.sender.focus()});
        e.app.whenReady().then(()=>{e.protocol.handle("https",f);new e.BrowserWindow({url:u,webPreferences:{preload:t.join(__dirname,"preload.js")}})});
    """.trimIndent()

    private fun install(withRoot: Boolean = true, extra: Map<String, ByteArray> = emptyMap()): File {
        val root = tempFolder.newFolder("install")
        File(root, "resources").mkdirs()
        if (withRoot) File(root, "resources/site").mkdirs()
        AsarTestFixtures.writeFixture(
            File(root, "resources/app.asar"),
            linkedMapOf(
                "package.json" to """{"name":"game","main":"out/main.js"}""".toByteArray(),
                "out/main.js" to main.toByteArray(),
                "out/preload.js" to "//preload".toByteArray(),
            ) + extra,
        )
        return root
    }

    private val electron = EngineProfile(engine = EnginePackId.ELECTRON)

    private fun open(dir: File, profiles: Boolean = true) =
        ElectronAsarSetup.open(dir.absolutePath, electron, profilesSupported = { profiles })!!

    @Test
    fun protocolPageAndRootComeFromTheInstall() {
        val dir = install()
        val setup = open(dir)
        try {
            val page = setup.protocolPage
            assertNotNull(page)
            assertEquals("https://example.test/__game__/index.html", page!!.entryUrl)
            assertEquals(File(dir, "resources/site").canonicalPath, setup.protocolRootDir!!.canonicalPath)
            assertEquals("/out/preload.js", setup.preloadUrl)
        } finally {
            setup.close()
        }
    }

    @Test
    fun missingResourceDirMeansNoProtocolPage() {
        val setup = open(install(withRoot = false))
        try {
            assertNull(setup.protocolPage)
        } finally {
            setup.close()
        }
    }

    @Test
    fun anAppWithItsOwnHtmlPageKeepsTheArchiveEntry() {
        val extra = mapOf("index.html" to "<html><script>1</script></html>".toByteArray())
        val setup = open(install(extra = extra))
        try {
            assertNull(setup.protocolPage)
        } finally {
            setup.close()
        }
    }

    @Test
    fun quitChannelsAreTheOnesWhoseHandlerQuits() {
        assertEquals(listOf("game:exit"), ElectronAsarSetup.quitChannels(main))
    }

    @Test
    fun quitChannelsIgnoreCommentsAndHandlersThatDoMore() {
        val src = """
            // ipcMain.on("old:exit",()=>app.quit())
            ipcMain.on("busy",()=>{${"a();".repeat(3)}app.quit()});
            ipcMain.on('bye', () => app.exit(0));
        """.trimIndent()
        assertEquals(listOf("bye"), ElectronAsarSetup.quitChannels(src))
    }

    @Test
    fun aSenderGuardBeforeTheQuitStillCounts() {
        val src = """
            e.ipcMain.on("native:exit",t=>{un(t)&&e.app.quit()});
            ipcMain.on("a", function (ev) { if (!trusted(ev)) return; app.quit(); });
            ipcMain.on("b", (ev) => { if (ev.sender.isTrusted()) { app.exit(0); } });
        """.trimIndent()
        assertEquals(listOf("native:exit", "a", "b"), ElectronAsarSetup.quitChannels(src))
    }

    // every send on a listed channel exits, so a window-control channel that quits on one value must not be listed.
    @Test
    fun aWindowControlHandlerThatQuitsOnOneValueIsNotAQuitChannel() {
        val src = """
            ipcMain.on("win:control",(e,a)=>{"close"===a?app.quit():"min"===a?w.minimize():w.maximize()});
            ipcMain.on("win:close",(e,a)=>{if(a)app.quit()});
            ipcMain.on("win:x",(e,a)=>{if(a==="close")app.quit();else w.minimize()});
        """.trimIndent()
        assertEquals(emptyList<String>(), ElectronAsarSetup.quitChannels(src))
    }

    @Test
    fun aRelaunchThenExitHandlerIsNotAQuitChannel() {
        val src = """
            ipcMain.on("app:restart",()=>{app.relaunch();app.exit(0)});
            ipcMain.on("app:restart2",e=>{ok(e)&&(app.relaunch(),app.exit())});
        """.trimIndent()
        assertEquals(emptyList<String>(), ElectronAsarSetup.quitChannels(src))
    }

    @Test
    fun anAppWithItsOwnPageGetsNoQuitChannels() {
        val extra = mapOf("index.html" to "<html><script>1</script></html>".toByteArray())
        val setup = open(install(extra = extra))
        try {
            assertNull(setup.buildContext()!!["quitChannels"])
        } finally {
            setup.close()
        }
    }

    @Test
    fun withoutProfileSupportTheTitleRunsAsAPlainElectronApp() {
        val setup = open(install(), profiles = false)
        try {
            assertNull(setup.protocolPage)
            assertNull(setup.mainProcessScript)
            assertNull(setup.buildContext()!!["quitChannels"])
            assertEquals("/out/preload.js", setup.preloadUrl)
        } finally {
            setup.close()
        }
    }

    @Test
    fun aPageAtASiteTheAppSignsInToIsRefused() {
        fun page(host: String, aliases: Set<String> = emptySet()) =
            app.gamenative.html5.asar.ProtocolServedPage(host, aliases, "/p/", "index.html", null, "site", null)
        assertTrue(ElectronAsarSetup.protocolPageRefusal(page("store.steampowered.com"), true)!!.contains("signs in"))
        assertNotNull(ElectronAsarSetup.protocolPageRefusal(page("example.test", setOf("login.steampowered.com")), true))
        assertNotNull(ElectronAsarSetup.protocolPageRefusal(page("example.test"), false))
        assertNull(ElectronAsarSetup.protocolPageRefusal(page("example.test"), true))
    }

    @Test
    fun contextCarriesQuitChannels() {
        val setup = open(install())
        try {
            val ctx = setup.buildContext()!!
            assertTrue(ctx["quitChannels"]!!.contains("game:exit"))
        } finally {
            setup.close()
        }
    }

    @Test
    fun aProtocolServedAppRunsItsMainScriptBeforeThePreload() {
        val setup = open(install())
        try {
            assertEquals(
                listOf(ElectronAsarSetup.MAIN_PROCESS_SHIM_URL, ElectronAsarSetup.MAIN_PROCESS_SCRIPT_URL),
                setup.mainProcessScriptUrls,
            )
            val js = setup.mainProcessScript!!
            assertTrue(js.startsWith("window.__gnElectronMain&&window.__gnElectronMain.run(function(require,module,exports,__filename,__dirname,process,Buffer){\n"))
            assertTrue(js.contains(main))
            assertTrue(js.endsWith("\n},\"out/main.js\");\n"))
        } finally {
            setup.close()
        }
    }

    @Test
    fun anAppWithItsOwnPageNeverRunsItsMainScript() {
        val extra = mapOf("index.html" to "<html><script>1</script></html>".toByteArray())
        val setup = open(install(extra = extra))
        try {
            assertNull(setup.mainProcessScript)
            assertEquals(emptyList<String>(), setup.mainProcessScriptUrls)
        } finally {
            setup.close()
        }
    }

    @Test
    fun wrappingKeepsAHashbangAndATrailingCommentOutOfTheWay() {
        val js = ElectronAsarSetup.wrapMainScript("#!/usr/bin/env node\nrun()\n//# sourceMappingURL=main.js.map", "a\"b.js")
        assertTrue(js.contains("{\n//#!/usr/bin/env node\nrun()\n//# sourceMappingURL=main.js.map\n},\"a\\\"b.js\");"))
    }
}
