package app.gamenative.service

import app.gamenative.data.DepotInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import org.junit.Assert.*
import org.junit.Test
import java.util.EnumSet

class DepotFilteringTest {

    private fun depot(
        depotId: Int = 1,
        manifests: Map<String, ManifestInfo> = emptyMap(),
        encryptedManifests: Map<String, ManifestInfo> = emptyMap(),
        sharedInstall: Boolean = false,
        osList: EnumSet<OS> = EnumSet.of(OS.windows),
        osArch: OSArch = OSArch.Arch64,
        dlcAppId: Int = SteamService.INVALID_APP_ID,
        language: String = "",
        systemDefined: Boolean = false,
        steamDeck: Boolean = false,
    ) = DepotInfo(
        depotId = depotId,
        dlcAppId = dlcAppId,
        depotFromApp = 0,
        sharedInstall = sharedInstall,
        osList = osList,
        osArch = osArch,
        manifests = manifests,
        encryptedManifests = encryptedManifests,
        language = language,
        systemDefined = systemDefined,
        steamDeck = steamDeck,
    )

    private fun manifest(size: Long = 1000L, download: Long = 800L) = ManifestInfo(
        name = "public",
        gid = 123L,
        size = size,
        download = download,
    )

    // -- filterForDownloadableDepots: 0-byte manifest filtering --

    @Test
    fun `valid depot with normal manifest passes filter`() {
        val d = depot(manifests = mapOf("public" to manifest()))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with 0-byte manifest is rejected`() {
        val d = depot(manifests = mapOf("public" to manifest(size = 0L, download = 0L)))
        assertFalse(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with nonzero size but 0-byte download passes (old game without download metadata)`() {
        val d = depot(manifests = mapOf("public" to manifest(size = 1000L, download = 0L)))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with mix of 0 and nonzero manifests passes`() {
        val d = depot(manifests = mapOf(
            "public" to manifest(size = 1000L, download = 800L),
            "beta" to manifest(size = 0L, download = 0L),
        ))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `encrypted-only depot is rejected`() {
        val d = depot(
            manifests = emptyMap(),
            encryptedManifests = mapOf("public" to manifest()),
        )
        assertFalse(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with both regular and encrypted manifests passes`() {
        val d = depot(
            manifests = mapOf("public" to manifest()),
            encryptedManifests = mapOf("beta" to manifest()),
        )
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with empty manifests and no shared install is rejected`() {
        val d = depot(manifests = emptyMap(), sharedInstall = false)
        assertFalse(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `depot with empty manifests but shared install passes`() {
        val d = depot(manifests = emptyMap(), sharedInstall = true)
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    // -- licensedDepotIds filtering --

    @Test
    fun `depot in licensed set passes`() {
        val d = depot(depotId = 100, manifests = mapOf("public" to manifest()))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null, setOf(100, 200)))
    }

    @Test
    fun `depot not in licensed set is rejected`() {
        val d = depot(depotId = 100, manifests = mapOf("public" to manifest()))
        assertFalse(SteamService.filterForDownloadableDepots(d, true, false, "english", null, setOf(200, 300)))
    }

    @Test
    fun `null licensedDepotIds skips license check`() {
        val d = depot(depotId = 100, manifests = mapOf("public" to manifest()))
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null, null))
    }

    @Test
    fun `systemDefined depot bypasses license check`() {
        val d = depot(depotId = 551, manifests = mapOf("public" to manifest()), systemDefined = true)
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null, setOf(552, 553)))
    }

    @Test
    fun `non-systemDefined depot still rejected when unlicensed`() {
        val d = depot(depotId = 100, manifests = mapOf("public" to manifest()), systemDefined = false)
        assertFalse(SteamService.filterForDownloadableDepots(d, true, false, "english", null, setOf(200, 300)))
    }

    // -- Steam Deck depot filtering --

    @Test
    fun `deck depot rejected when non-deck windows depot exists`() {
        val d = depot(manifests = mapOf("public" to manifest()), steamDeck = true)
        assertFalse(SteamService.filterForDownloadableDepots(d, true, true, "english", null))
    }

    @Test
    fun `deck depot passes when no non-deck windows depot exists`() {
        val d = depot(manifests = mapOf("public" to manifest()), steamDeck = true)
        assertTrue(SteamService.filterForDownloadableDepots(d, true, false, "english", null))
    }

    @Test
    fun `non-deck depot passes regardless of preferNonDeckWindows`() {
        val d = depot(manifests = mapOf("public" to manifest()), steamDeck = false)
        assertTrue(SteamService.filterForDownloadableDepots(d, true, true, "english", null))
    }

    // -- language filter fallback (publisher-mistagged single-language DLCs) --

    @Test
    fun `non-matching language depot rejected when group has preferred-language alternative`() {
        val d = depot(
            manifests = mapOf("public" to manifest()),
            dlcAppId = 42,
            language = "english",
        )
        assertFalse(
            SteamService.filterForDownloadableDepots(
                d, true, false, "french", null, null, false,
                dlcGroupsWithPreferredLanguage = setOf(42),
            ),
        )
    }

    @Test
    fun `non-matching language depot accepted when group has no preferred-language alternative`() {
        val d = depot(
            manifests = mapOf("public" to manifest()),
            dlcAppId = 42,
            language = "english",
        )
        assertTrue(
            SteamService.filterForDownloadableDepots(
                d, true, false, "french", null, null, false,
                dlcGroupsWithPreferredLanguage = emptySet(),
            ),
        )
    }

    @Test
    fun `null dlcGroupsWithPreferredLanguage preserves strict legacy behavior`() {
        val d = depot(
            manifests = mapOf("public" to manifest()),
            dlcAppId = 42,
            language = "english",
        )
        assertFalse(SteamService.filterForDownloadableDepots(d, true, false, "french", null))
    }
}
