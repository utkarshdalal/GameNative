package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class NwjsAppOriginTest {

    private lateinit var install: File

    @Before
    fun setUp() {
        install = Files.createTempDirectory("nwjs-install-").toFile()
    }

    @After
    fun tearDown() {
        install.deleteRecursively()
    }

    @Test
    fun fromInstall_readsLoosePackageJson() {
        File(install, "nw.dll").writeText("")
        File(install, "package.json").writeText("""{"name": "CrossCode", "main": "assets/node-webkit.html"}""")

        assertEquals("chrome-extension://lmepkikdgdbfdpjokdmnnanopegnpjda", NwjsAppOrigin.fromInstall(install))
    }

    @Test
    fun fromInstall_readsPackageNwZip() {
        File(install, "nw.dll").writeText("")
        ZipOutputStream(File(install, "package.nw").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("package.json"))
            zip.write("""{"name":"SolCesto"}""".toByteArray())
            zip.closeEntry()
        }

        assertEquals("chrome-extension://anopiimlkmdoenonenclohfilpeenfmj", NwjsAppOrigin.fromInstall(install))
    }

    @Test
    fun fromInstall_nullWithoutNwDll() {
        // a package.json alone doesn't make an NW.js app (Electron, web builds)
        File(install, "package.json").writeText("""{"name": "CrossCode"}""")

        assertNull(NwjsAppOrigin.fromInstall(install))
    }

    @Test
    fun fromInstall_nullWhenManifestMissingNamelessOrInvalid() {
        File(install, "nw.dll").writeText("")
        assertNull(NwjsAppOrigin.fromInstall(install))

        File(install, "package.json").writeText("""{"main": "index.html"}""")
        assertNull(NwjsAppOrigin.fromInstall(install))

        File(install, "package.json").writeText("not json")
        assertNull(NwjsAppOrigin.fromInstall(install))
    }
}
