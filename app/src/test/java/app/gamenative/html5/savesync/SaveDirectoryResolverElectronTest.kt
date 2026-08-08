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

// 1 robolectric because Container.<clinit> reads Android Environment
// (same pattern as SaveDirectoryResolverTest). all IO bounded by TemporaryFolder.
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
        // SPEC Req #6 parity: the new overload's Electron branch walks winePrefixPathForRoot
        // with WinAppDataRoaming — same helper SteamAutoCloud.prefixToPath eventually hits when
        // feeding a UFS pattern whose root is WinAppDataRoaming. Parity test: both paths end at
        // the same `<containerRoot>/.wine/drive_c/users/xuser/AppData/Roaming/...`. productName
        // append is the only difference (new overload); the base path is identical.
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
        // 1-08: null-byte injection must be rejected BEFORE File construction.
        // asserts the validator predicate is !normalized.contains(NUL) not a space-check
        // copy-paste bug. built via Char(0) so source file stays ASCII-clean 1 P01
        // precedent — raw NUL bytes in source trip git binary-file heuristic).
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
