package app.gamenative.service

import app.gamenative.data.DepotInfo
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet

/** Depot coverage for an INSTALL: parent-row depots, DLC-row depots, and the ids a run must record. */
class DepotSelectionTest {

    private fun depot(depotId: Int, dlcAppId: Int = SteamService.INVALID_APP_ID) = DepotInfo(
        depotId = depotId,
        dlcAppId = dlcAppId,
        depotFromApp = 0,
        sharedInstall = false,
        osList = EnumSet.of(OS.windows),
        osArch = OSArch.Arch64,
        manifests = emptyMap(),
        encryptedManifests = emptyMap(),
    )

    @Test
    fun install_skips_depots_the_parent_record_lists() {
        assertEquals(
            setOf(100),
            SteamService.alreadyInstalledDepotIds(
                depotIds = setOf(100, 101),
                parentInstalled = setOf(1, 100, 200),
                dlcInstalledPerApp = emptyMap(),
            ),
        )
    }

    @Test
    fun install_skips_depots_a_dlc_app_records_on_its_own_row() {
        // An indirect DLC's depots live on its own row only; leaving them selected re-runs a no-op.
        assertEquals(
            setOf(200),
            SteamService.alreadyInstalledDepotIds(
                depotIds = setOf(200),
                parentInstalled = setOf(1, 100),
                dlcInstalledPerApp = mapOf(200 to setOf(200)),
            ),
        )
    }

    @Test
    fun install_keeps_every_depot_no_record_lists() {
        assertTrue(
            SteamService.alreadyInstalledDepotIds(
                depotIds = setOf(200, 201),
                parentInstalled = setOf(1),
                dlcInstalledPerApp = mapOf(200 to emptySet()),
            ).isEmpty(),
        )
    }

    @Test
    fun a_dlc_record_only_covers_the_depots_it_lists() {
        assertEquals(
            "a depot the record does not list stays in the selection",
            setOf(200),
            SteamService.alreadyInstalledDepotIds(
                depotIds = setOf(200, 201),
                parentInstalled = emptySet(),
                dlcInstalledPerApp = mapOf(200 to setOf(200)),
            ),
        )
    }

    @Test
    fun completion_records_the_dlc_that_owns_a_parent_depot() {
        // A DLC owning a parent depot has no row of its own; its id must reach the parent's record.
        val depots = mapOf(
            100 to depot(100),
            200 to depot(200, dlcAppId = 200),
            300 to depot(300, dlcAppId = 300),
        )
        assertEquals(
            listOf(200),
            SteamService.dlcAppIdsOfInstalledDepots(
                installedDepotIds = listOf(1, 100, 200),
                depots = depots,
            ),
        )
    }

    @Test
    fun completion_records_nothing_for_base_depots() {
        val depots = mapOf(100 to depot(100), 1 to depot(1))
        assertTrue(
            SteamService.dlcAppIdsOfInstalledDepots(
                installedDepotIds = listOf(1, 100),
                depots = depots,
            ).isEmpty(),
        )
    }

    @Test
    fun completion_ignores_depots_the_run_never_selected() {
        // A depot id with no DepotInfo contributes nothing; a repeated DLC id is listed once.
        val depots = mapOf(
            200 to depot(200, dlcAppId = 200),
            201 to depot(201, dlcAppId = 200),
        )
        assertEquals(
            listOf(200),
            SteamService.dlcAppIdsOfInstalledDepots(
                installedDepotIds = listOf(200, 201, 999),
                depots = depots,
            ),
        )
    }
}
