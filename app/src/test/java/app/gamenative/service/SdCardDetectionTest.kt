package app.gamenative.service

import app.gamenative.data.DepotInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.enums.Marker
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.EnumSet

class SdCardDetectionTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    // -- filterForDownloadableDepots: 0-byte manifest filtering --

    private fun depot(
        manifests: Map<String, ManifestInfo> = emptyMap(),
        encryptedManifests: Map<String, ManifestInfo> = emptyMap(),
        sharedInstall: Boolean = false,
        osList: EnumSet<OS> = EnumSet.of(OS.windows),
        osArch: OSArch = OSArch.Arch64,
        dlcAppId: Int = SteamService.INVALID_APP_ID,
        language: String = "",
    ) = DepotInfo(
        depotId = 1,
        dlcAppId = dlcAppId,
        depotFromApp = 0,
        sharedInstall = sharedInstall,
        osList = osList,
        osArch = osArch,
        manifests = manifests,
        encryptedManifests = encryptedManifests,
        language = language,
    )

    private fun manifest(size: Long = 1000L, download: Long = 800L) = ManifestInfo(
        name = "public",
        gid = 123L,
        size = size,
        download = download,
    )

    @Test
    fun `valid depot with normal manifest passes filter`() {
        val d = depot(manifests = mapOf("public" to manifest()))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, "english", null))
    }

    @Test
    fun `depot with 0-byte manifest is rejected`() {
        val d = depot(manifests = mapOf("public" to manifest(size = 0L, download = 0L)))
        assertFalse(SteamService.filterForDownloadableDepots(d, true, "english", null))
    }

    @Test
    fun `depot with nonzero size but 0-byte download is rejected`() {
        val d = depot(manifests = mapOf("public" to manifest(size = 1000L, download = 0L)))
        assertFalse(SteamService.filterForDownloadableDepots(d, true, "english", null))
    }

    @Test
    fun `depot with mix of 0 and nonzero manifests passes`() {
        val d = depot(manifests = mapOf(
            "public" to manifest(size = 1000L, download = 800L),
            "beta" to manifest(size = 0L, download = 0L),
        ))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, "english", null))
    }

    @Test
    fun `encrypted-only depot is rejected`() {
        val d = depot(
            manifests = emptyMap(),
            encryptedManifests = mapOf("public" to manifest()),
        )
        assertFalse(SteamService.filterForDownloadableDepots(d, true, "english", null))
    }

    @Test
    fun `depot with both regular and encrypted manifests passes`() {
        val d = depot(
            manifests = mapOf("public" to manifest()),
            encryptedManifests = mapOf("beta" to manifest()),
        )
        assertTrue(SteamService.filterForDownloadableDepots(d, true, "english", null))
    }

    @Test
    fun `depot with empty manifests and no shared install is rejected`() {
        val d = depot(manifests = emptyMap(), sharedInstall = false)
        assertFalse(SteamService.filterForDownloadableDepots(d, true, "english", null))
    }

    @Test
    fun `depot with empty manifests but shared install passes`() {
        val d = depot(manifests = emptyMap(), sharedInstall = true)
        assertTrue(SteamService.filterForDownloadableDepots(d, true, "english", null))
    }

    // -- getAppDirPath: prefer completed installs over partial --

    private fun createGameDir(base: File, gameName: String, complete: Boolean): File {
        val dir = File(base, gameName)
        dir.mkdirs()
        if (complete) {
            File(dir, Marker.DOWNLOAD_COMPLETE_MARKER.fileName).createNewFile()
        }
        return dir
    }

    @Test
    fun `completed install on second volume preferred over partial on first`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        createGameDir(internal, "MyGame", complete = false)
        createGameDir(sdcard, "MyGame", complete = true)

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(File(sdcard, "MyGame").absolutePath, result)
    }

    @Test
    fun `falls back to first existing when none are complete`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val sdcard = tmpDir.newFolder("sdcard", "Steam", "steamapps", "common")

        createGameDir(internal, "MyGame", complete = false)
        createGameDir(sdcard, "MyGame", complete = false)

        val paths = listOf(internal.absolutePath, sdcard.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertEquals(File(internal, "MyGame").absolutePath, result)
    }

    @Test
    fun `returns null when no directory exists`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val paths = listOf(internal.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf("MyGame"))

        assertNull(result)
    }

    @Test
    fun `empty name is skipped — never returns install root`() {
        val internal = tmpDir.newFolder("internal", "Steam", "steamapps", "common")
        val paths = listOf(internal.absolutePath)
        val result = SteamService.resolveExistingAppDir(paths, listOf(""))

        assertNull(result)
    }
}
