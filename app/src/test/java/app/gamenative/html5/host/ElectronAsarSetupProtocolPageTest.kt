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

    @Test
    fun protocolPageAndRootComeFromTheInstall() {
        val dir = install()
        val setup = ElectronAsarSetup.open(dir.absolutePath, electron)!!
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
        val setup = ElectronAsarSetup.open(install(withRoot = false).absolutePath, electron)!!
        try {
            assertNull(setup.protocolPage)
        } finally {
            setup.close()
        }
    }

    @Test
    fun anAppWithItsOwnHtmlPageKeepsTheArchiveEntry() {
        val extra = mapOf("index.html" to "<html><script>1</script></html>".toByteArray())
        val setup = ElectronAsarSetup.open(install(extra = extra).absolutePath, electron)!!
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
    fun quitChannelsIgnoreCommentsAndLongHandlers() {
        val src = """
            // ipcMain.on("old:exit",()=>app.quit())
            ipcMain.on("busy",()=>{${"a();".repeat(200)}app.quit()});
            ipcMain.on('bye', () => app.exit(0));
        """.trimIndent()
        assertEquals(listOf("bye"), ElectronAsarSetup.quitChannels(src))
    }

    @Test
    fun contextCarriesQuitChannels() {
        val setup = ElectronAsarSetup.open(install().absolutePath, electron)!!
        try {
            val ctx = setup.buildContext()!!
            assertTrue(ctx["quitChannels"]!!.contains("game:exit"))
        } finally {
            setup.close()
        }
    }
}
