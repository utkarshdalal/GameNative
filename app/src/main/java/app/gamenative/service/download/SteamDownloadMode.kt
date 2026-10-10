package app.gamenative.service.download

/**
 * What a Steam download run is FOR. The modes are decided in two places —
 * [app.gamenative.service.download.GameDownloadService] `resolveDepotForDownload` (which manifest a
 * depot is pinned to) and the depot selection in SteamService (which depots enter the run at all) —
 * and that choice has three consequences in the engine: `fresh` (INSTALL may skip a depot the store
 * already records at the requested build; UPDATE/VERIFY always walk, re-hashing existing chunks
 * against the manifest), whether the previous build is diffed for a per-file delta and a
 * removed-file sweep (UPDATE, when the store has one and it is usable), and whether the run can
 * move the install to a different build (UPDATE yes, VERIFY never).
 *
 * Steam itself has no such split — its client only ever installs/updates to the *current* build,
 * and "verify integrity" verifies against the manifest of the build you have installed. Passing a
 * single `isUpdateOrVerify` boolean meant our Verify had to fetch the latest manifest like an
 * update, so verifying a game silently upgraded it.
 */
enum class SteamDownloadMode {
    /** Fresh install (or resume of one): depots already recorded as downloaded are skipped. */
    INSTALL,

    /** Update: take each depot's CURRENT manifest for the branch (PICS), re-hash existing chunks
     *  against it, download the changed ones, and prune content the new manifest no longer lists
     *  (the engine diffs the build the store records as installed against it). */
    UPDATE,

    /** Verify: pin every depot to the build the manifest store RECORDS AS INSTALLED
     *  (`.DepotDownloader/completed/` — see [app.gamenative.utils.DepotManifestFiles]) — never the
     *  latest one; that manifest is already on disk, so a verify fetches nothing. Chunks
     *  are re-hashed against that manifest and only missing/corrupt ones are re-fetched, so a
     *  verify can never move the install to a different build. Depots with no recorded manifest
     *  are skipped; if none is recorded at all the run fails with a message telling the user to
     *  update instead. */
    VERIFY,
}
