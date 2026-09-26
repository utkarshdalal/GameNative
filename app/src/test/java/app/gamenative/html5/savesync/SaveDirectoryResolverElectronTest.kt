package app.gamenative.html5.savesync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.html5.profile.EngineProfile
import com.winlator.container.Container
import java.io.File
import org.junit.Assert.assertEquals
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
}
