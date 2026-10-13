package app.gamenative.workshop.compatibility

import app.gamenative.data.GameSource

/**
 * Everlasting Summer (Steam)
 *
 * Ren'Py scans its game/ tree recursively. This game can load Workshop items
 * through Steam metadata, so filesystem links under the game tree expose the
 * same .rpy/.rpyc files twice and crash with duplicate script definitions.
 */
val STEAM_WorkshopOverride_331470 = KeyedWorkshopCompatibilityOverride(
    gameSource = GameSource.STEAM,
    gameId = "331470",
    override = WorkshopCompatibilityOverride(
        exposureMode = WorkshopExposureMode.METADATA_ONLY,
        ignoreManualModPath = true,
        cleanupNestedSteamSettingsArtifacts = true,
    ),
)
