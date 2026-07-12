package app.gamenative.utils

import app.gamenative.data.DepotInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.enums.SteamRealm
import app.gamenative.service.SteamService
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.EnumSet

class SteamUtilsDepotLanguageTest {

    private fun depot(
        depotId: Int = 1,
        language: String = "",
        manifests: Map<String, ManifestInfo> = mapOf("public" to manifest()),
        encryptedManifests: Map<String, ManifestInfo> = emptyMap(),
        sharedInstall: Boolean = false,
        osList: EnumSet<OS> = EnumSet.of(OS.windows),
        dlcAppId: Int = SteamService.INVALID_APP_ID,
        systemDefined: Boolean = false,
        realm: SteamRealm = SteamRealm.Unknown,
    ) = DepotInfo(
        depotId = depotId,
        dlcAppId = dlcAppId,
        depotFromApp = 0,
        sharedInstall = sharedInstall,
        osList = osList,
        osArch = OSArch.Arch64,
        manifests = manifests,
        encryptedManifests = encryptedManifests,
        language = language,
        realm = realm,
        systemDefined = systemDefined,
    )

    private fun manifest() = ManifestInfo(name = "public", gid = 1L, size = 1000L, download = 800L)

    // Insertion order preserved so first-available fallback is deterministic.
    private fun depotsOf(vararg depots: DepotInfo) =
        depots.associateByTo(LinkedHashMap()) { it.depotId }

    private fun resolve(
        depots: Map<Int, DepotInfo>,
        preferred: String,
        ownedDlc: Map<Int, DepotInfo>? = null,
        licensedDepotIds: Set<Int>? = null,
        hasSteamUnlockedBranch: Boolean = false,
    ) = SteamUtils.effectiveDepotLanguage(depots, preferred, ownedDlc, licensedDepotIds, hasSteamUnlockedBranch)

    // -- Priority 1: requested language wins when available --

    @Test
    fun `returns requested language when the app ships it`() {
        val depots = depotsOf(
            depot(depotId = 1, language = "english"),
            depot(depotId = 2, language = "french"),
            depot(depotId = 3, language = "german"),
        )
        assertEquals("french", resolve(depots, "french"))
    }

    // -- Priority 2: fall back to English --

    @Test
    fun `falls back to english when requested language is absent`() {
        val depots = depotsOf(
            depot(depotId = 1, language = "english"),
            depot(depotId = 2, language = "german"),
        )
        assertEquals("english", resolve(depots, "french"))
    }

    // -- Priority 3: fall back to the first language the app ships --

    @Test
    fun `falls back to the only available language when neither requested nor english exist`() {
        val depots = depotsOf(depot(depotId = 1, language = "german"))
        assertEquals("german", resolve(depots, "french"))
    }

    @Test
    fun `falls back to the first available language when neither requested nor english exist`() {
        val depots = depotsOf(
            depot(depotId = 1, language = "schinese"),
            depot(depotId = 2, language = "japanese"),
        )
        assertEquals("schinese", resolve(depots, "french"))
    }

    // -- Untagged (neutral) depots are ignored by the fallback pool --

    @Test
    fun `keeps preferred language when the app only ships untagged depots`() {
        val depots = depotsOf(depot(depotId = 1, language = ""))
        assertEquals("french", resolve(depots, "french"))
    }

    @Test
    fun `untagged depot does not become the fallback`() {
        val depots = depotsOf(
            depot(depotId = 1, language = ""),
            depot(depotId = 2, language = "german"),
        )
        assertEquals("german", resolve(depots, "french"))
    }

    // -- Only base-game depots steer the language --

    @Test
    fun `dlc depot language does not steer the base game`() {
        val depots = depotsOf(
            depot(depotId = 1, language = "german"),
            depot(depotId = 2, language = "french", dlcAppId = 4242),
        )
        // french lives only on the DLC depot, so it is excluded and german wins.
        assertEquals("german", resolve(depots, "french"))
    }

    // -- A depot must be installable to count as an available language --

    @Test
    fun `encrypted-only language is ignored without a steam-unlocked branch`() {
        val depots = depotsOf(
            depot(depotId = 1, language = "english"),
            depot(
                depotId = 2,
                language = "french",
                manifests = emptyMap(),
                encryptedManifests = mapOf("public" to manifest()),
            ),
        )
        assertEquals("english", resolve(depots, "french", hasSteamUnlockedBranch = false))
    }

    @Test
    fun `encrypted-only language counts when the steam-unlocked branch is present`() {
        val depots = depotsOf(
            depot(depotId = 1, language = "english"),
            depot(
                depotId = 2,
                language = "french",
                manifests = emptyMap(),
                encryptedManifests = mapOf("public" to manifest()),
            ),
        )
        assertEquals("french", resolve(depots, "french", hasSteamUnlockedBranch = true))
    }

    @Test
    fun `non-windows language is ignored`() {
        val depots = depotsOf(
            depot(depotId = 1, language = "english"),
            depot(depotId = 2, language = "french", osList = EnumSet.of(OS.linux)),
        )
        assertEquals("english", resolve(depots, "french"))
    }

    @Test
    fun `steamchina realm language is ignored`() {
        val depots = depotsOf(
            depot(depotId = 1, language = "english"),
            depot(depotId = 2, language = "french", realm = SteamRealm.SteamChina),
        )
        assertEquals("english", resolve(depots, "french"))
    }

    @Test
    fun `unlicensed base-game language is ignored`() {
        val depots = depotsOf(
            depot(depotId = 100, language = "french"),
            depot(depotId = 101, language = "english"),
        )
        assertEquals("english", resolve(depots, "french", licensedDepotIds = setOf(101)))
    }

    // -- No installable tagged depot at all --

    @Test
    fun `returns preferred language when there are no depots`() {
        assertEquals("french", resolve(emptyMap(), "french"))
    }
}
