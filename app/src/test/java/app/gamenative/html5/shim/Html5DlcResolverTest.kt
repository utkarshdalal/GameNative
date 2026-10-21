package app.gamenative.html5.shim

import app.gamenative.data.SteamApp
import app.gamenative.html5.shim.Html5DlcResolver.Dlc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Html5DlcResolverTest {

    // Moonstone Island GOG build's GalaxyConfig.json shape (tab-indented rows included).
    private val galaxyConfig = """
        {
            "client_id": "57040877152288617",
            "dlcs": [
                {"steam_id":3146820, "name":"Autumnal Accessories DLC Pack", "galaxy_id":1842640820},
        	{"steam_id":2747460, "name":"Designed for Lovers DLC Pack", "galaxy_id":1411301542}
            ]
        }
    """.trimIndent()

    @Test
    fun galaxyConfig_mapsSteamIdsAndAnswersFromGogOwnership() {
        val dlcs = Html5DlcResolver.fromGalaxyConfig(galaxyConfig, owns = { it == "1411301542" }, isInstalled = { true })

        assertEquals(
            listOf(
                Dlc(3146820, "Autumnal Accessories DLC Pack", available = false, installed = false),
                Dlc(2747460, "Designed for Lovers DLC Pack", available = true, installed = true),
            ),
            dlcs,
        )
    }

    @Test
    fun galaxyConfig_ownedWithoutInstallRecord_isAvailableButNotInstalled() {
        val dlcs = Html5DlcResolver.fromGalaxyConfig(galaxyConfig, owns = { true }, isInstalled = { it == "1842640820" })

        assertEquals(listOf(3146820 to true, 2747460 to false), dlcs.map { it.appId to it.installed })
        assertTrue(dlcs.all { it.available })
    }

    @Test
    fun galaxyConfig_withoutDlcs_isEmpty() {
        assertTrue(Html5DlcResolver.fromGalaxyConfig("""{"client_id":"1"}""", owns = { true }, isInstalled = { true }).isEmpty())
    }

    @Test
    fun steam_downloadableDlc_installedOnlyWhenRecordedOrInstalledSeparately() {
        val dlcs = Html5DlcResolver.fromSteam(
            baseAppId = 100,
            downloadable = listOf(SteamApp(id = 101, name = "a"), SteamApp(id = 102, name = "b"), SteamApp(id = 103, name = "c")),
            hidden = emptyList(),
            installedDlcAppIds = listOf(101),
            depotDlcAppIds = listOf(101, 102, 103),
            isAppInstalled = { it == 102 },
        )

        assertEquals(listOf(true, true, false), dlcs.map { it.installed })
        assertTrue(dlcs.all { it.available })
    }

    @Test
    fun steam_hiddenDlc_installedUnlessItHasSeveralDepots() {
        val dlcs = Html5DlcResolver.fromSteam(
            baseAppId = 100,
            downloadable = emptyList(),
            hidden = listOf(SteamApp(id = 201, name = "no depot"), SteamApp(id = 202, name = "two depots")),
            installedDlcAppIds = emptyList(),
            depotDlcAppIds = listOf(202, 202),
            isAppInstalled = { false },
        )

        assertEquals(listOf(201 to true, 202 to false), dlcs.map { it.appId to it.installed })
    }

    @Test
    fun steam_skipsBaseAppAndKeepsRecordedIdsMissingFromTheLists() {
        val dlcs = Html5DlcResolver.fromSteam(
            baseAppId = 100,
            downloadable = listOf(SteamApp(id = 100, name = "base")),
            hidden = emptyList(),
            installedDlcAppIds = listOf(100, 301),
            depotDlcAppIds = emptyList(),
            isAppInstalled = { true },
        )

        assertEquals(listOf(Dlc(301, "", available = true, installed = true)), dlcs)
    }

    @Test
    fun toJson_emitsGreenworksFieldNames() {
        val json = Html5DlcResolver.toJson(listOf(Dlc(7, "x", available = true, installed = false)))

        assertEquals("""[{"appId":7,"name":"x","available":true,"installed":false}]""", json)
    }
}
