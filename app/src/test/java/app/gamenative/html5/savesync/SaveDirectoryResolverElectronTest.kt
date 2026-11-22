package app.gamenative.html5.savesync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.html5.profile.EngineProfile
import com.winlator.container.Container
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// robolectric because Container.<clinit> reads Android Environment
@RunWith(RobolectricTestRunner::class)
class SaveDirectoryResolverElectronTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefManager.init(context)
    }

    private fun electronProfile(): EngineProfile = EngineProfile(engine = "pack:electron")

    private fun makeContainerWithRoot(id: String = "STEAM_379210"): Container {
        val root = tempFolder.newFolder("container-$id")
        val c = Container(id)
        c.rootDir = root
        return c
    }

    @Test
    fun electron_overload_returnsWinePrefixAppDataRoamingProductName() {
        val c = makeContainerWithRoot()
        val sandboxRoot = SaveDirectoryResolver.resolveSandboxRoot(
            context = context,
            appId = "STEAM_379210",
            container = c,
            profile = electronProfile(),
            productName = "Wayward",
        )
        val path = sandboxRoot.absolutePath
        assertTrue(
            "expected .*/.wine/drive_c/users/xuser/AppData/Roaming/Wayward — got $path",
            Regex(".*/\\.wine/drive_c/users/xuser/AppData/Roaming/Wayward$").matches(path),
        )
    }

    @Test
    fun electron_overload_respectsContainerRootDirWhenSet() {
        val c = makeContainerWithRoot()
        val sandboxRoot = SaveDirectoryResolver.resolveSandboxRoot(
            context = context,
            appId = "STEAM_379210",
            container = c,
            profile = electronProfile(),
            productName = "Wayward",
        )
        assertTrue(
            "expected sandbox under ${c.rootDir!!.absolutePath} — got ${sandboxRoot.absolutePath}",
            sandboxRoot.absolutePath.startsWith(c.rootDir!!.absolutePath),
        )
    }

    @Test
    fun electron_overload_fallsBackToImagefsHomeXuserWhenRootDirUnset() {
        val c = Container("STEAM_379210") // rootDir null on purpose
        val sandboxRoot = SaveDirectoryResolver.resolveSandboxRoot(
            context = context,
            appId = "STEAM_379210",
            container = c,
            profile = electronProfile(),
            productName = "Wayward",
        )
        // matches containerRootDir() fallback: <imagefsRoot>/home/xuser-STEAM_379210/.wine/...
        val p = sandboxRoot.absolutePath
        assertTrue(
            "expected imagefs fallback path — got $p",
            p.contains("home/xuser-STEAM_379210/.wine/drive_c/users/xuser/AppData/Roaming/Wayward"),
        )
    }

    @Test
    fun electron_overload_delegatesToExistingOverloadForNonElectronProfile() {
        val c = makeContainerWithRoot()
        c.installPath = tempFolder.newFolder("install").absolutePath
        val rmmvProfile = EngineProfile(engine = "pack:rmmv")
        val electronOut = SaveDirectoryResolver.resolveSandboxRoot(
            context = context,
            appId = "STEAM_3373660",
            container = c,
            profile = rmmvProfile,
            productName = "LookOutside",
        )
        val directOut = SaveDirectoryResolver.resolveSandboxRoot("STEAM_3373660", c.installPath)
        assertEquals(directOut.absolutePath, electronOut.absolutePath)
    }

    @Test
    fun electron_overload_roundTripParityWithWinePrefixHelper() {
        // the Electron branch must land on the same AppData/Roaming base SteamAutoCloud.prefixToPath resolves
        // for a WinAppDataRoaming UFS root, or cloud sync and the game disagree on the save dir. only the
        // productName suffix differs.
        val c = makeContainerWithRoot()
        val electronPath = SaveDirectoryResolver.resolveSandboxRoot(
            context = context,
            appId = "STEAM_379210",
            container = c,
            profile = electronProfile(),
            productName = "Wayward",
        )
        val expectedBase = File(
            c.rootDir,
            ".wine/drive_c/users/xuser/AppData/Roaming/Wayward",
        )
        assertEquals(expectedBase.absolutePath, electronPath.absolutePath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun electron_overload_rejectsMaliciousProductName_slash() {
        SaveDirectoryResolver.resolveSandboxRoot(
            context = context,
            appId = "STEAM_379210",
            container = makeContainerWithRoot(),
            profile = electronProfile(),
            productName = "../evil",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun electron_overload_rejectsMaliciousProductName_backslash() {
        SaveDirectoryResolver.resolveSandboxRoot(
            context = context,
            appId = "STEAM_379210",
            container = makeContainerWithRoot(),
            profile = electronProfile(),
            productName = "..\\evil",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun electron_overload_rejectsBlankProductName() {
        SaveDirectoryResolver.resolveSandboxRoot(
            context = context,
            appId = "STEAM_379210",
            container = makeContainerWithRoot(),
            profile = electronProfile(),
            productName = "",
        )
    }

    @Test
    fun electron_overload_rejectsMaliciousProductName_nullByte() {
        // null-byte injection must be rejected BEFORE File construction. built via Char(0) because raw NUL
        // bytes in source trip git's binary-file heuristic.
        val nul = Char(0).toString()
        val malicious = "Evil${nul}more"
        val ex = try {
            SaveDirectoryResolver.resolveSandboxRoot(
                context = context,
                appId = "STEAM_379210",
                container = makeContainerWithRoot(),
                profile = electronProfile(),
                productName = malicious,
            )
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("expected IllegalArgumentException, got $ex", ex != null)
        assertTrue(
            "expected 'null byte' in message, got '${ex!!.message}'",
            ex.message!!.contains("null byte"),
        )
    }

    @Test
    fun migrationMovesOnlyUfsSubtreesAndNeverOverwrites() {
        val old = tempFolder.newFolder("appdata-product")
        val install = tempFolder.newFolder("install")
        File(old, "save/123/file1.rmmzsave").apply { parentFile!!.mkdirs() }.writeText("old1")
        File(old, "save/123/config.rmmzsave").apply { parentFile!!.mkdirs() }.writeText("old-config")
        File(old, "Local Storage/leveldb/000003.log").apply { parentFile!!.mkdirs() }.writeText("ls")
        File(install, "save/123/config.rmmzsave").apply { parentFile!!.mkdirs() }.writeText("new-config")

        SaveDirectoryResolver.migrateElectronAppDataSaves(old, install, listOf("save", ""))

        assertEquals("old1", File(install, "save/123/file1.rmmzsave").readText())
        assertFalse(File(old, "save/123/file1.rmmzsave").exists())
        assertEquals("install-dir copy wins", "new-config", File(install, "save/123/config.rmmzsave").readText())
        assertTrue(
            "conflicting old copy is kept, renamed out of the walk",
            File(old, "save/123/config.rmmzsave.gn-superseded").exists(),
        )
        assertTrue("electron userData outside UFS paths is untouched", File(old, "Local Storage/leveldb/000003.log").exists())
        assertFalse(File(install, "Local Storage").exists())
    }

    @Test
    fun migrationIsOneShotSoADeletedSaveStaysDeleted() {
        val old = tempFolder.newFolder("appdata-oneshot")
        val install = tempFolder.newFolder("install-oneshot")
        File(old, "save/1/config.rmmzsave").apply { parentFile!!.mkdirs() }.writeText("old")
        File(install, "save/1/config.rmmzsave").apply { parentFile!!.mkdirs() }.writeText("new")

        SaveDirectoryResolver.migrateElectronAppDataSaves(old, install, listOf("save"))
        File(install, "save/1/config.rmmzsave").delete()
        SaveDirectoryResolver.migrateElectronAppDataSaves(old, install, listOf("save"))

        assertFalse("losing AppData copy must not be revived", File(install, "save/1/config.rmmzsave").exists())
    }

    @Test
    fun supersededCopyIsNotRevivedEvenIfThePassReruns() {
        // an interrupted pass leaves no marker; the retry must still not restore a copy that already lost.
        val old = tempFolder.newFolder("appdata-rerun")
        val install = tempFolder.newFolder("install-rerun")
        File(old, "save/1/config.rmmzsave").apply { parentFile!!.mkdirs() }.writeText("old")
        File(install, "save/1/config.rmmzsave").apply { parentFile!!.mkdirs() }.writeText("new")

        SaveDirectoryResolver.migrateElectronAppDataSaves(old, install, listOf("save"))
        File(old, ".gn-ufs-migrated").delete()
        File(install, "save/1/config.rmmzsave").delete()
        SaveDirectoryResolver.migrateElectronAppDataSaves(old, install, listOf("save"))

        assertFalse(File(install, "save/1/config.rmmzsave").exists())
    }

    @Test
    fun markerIsWrittenEvenWithNothingToMigrate() {
        val old = File(tempFolder.root, "appdata-absent")
        val install = tempFolder.newFolder("install-absent")

        SaveDirectoryResolver.migrateElectronAppDataSaves(old, install, listOf("save"))

        assertTrue("marker records the install-dir choice", File(old, ".gn-ufs-migrated").exists())
    }

    @Test
    fun saveIsLeftInAppDataWhenTheDestinationEscapesTheInstallDir() {
        val old = tempFolder.newFolder("appdata-link")
        val install = tempFolder.newFolder("install-link")
        val outside = tempFolder.newFolder("outside")
        java.nio.file.Files.createSymbolicLink(File(install, "save").toPath(), outside.toPath())
        File(old, "save/1/file1.rmmzsave").apply { parentFile!!.mkdirs() }.writeText("old")

        SaveDirectoryResolver.migrateElectronAppDataSaves(old, install, listOf("save"))

        assertTrue("source stays put", File(old, "save/1/file1.rmmzsave").exists())
        assertFalse("nothing written through the link", File(outside, "1/file1.rmmzsave").exists())
    }
}
