package app.gamenative.service.download

/**
 * What a Steam download run is FOR. The modes are decided in two places —
 * [app.gamenative.service.download.GameDownloadService] `resolveDepotForDownload` (which manifest a
 * depot is pinned to) and the depot selection in SteamService (which depots enter the run at all) —
 * and that choice has three consequences in the engine: `fresh` (INSTALL trusts the resume journal,
 * UPDATE/VERIFY always re-validate against the manifest), whether the previous manifest is diffed
 * for a per-file delta and a removed-file sweep (UPDATE, when it has a usable previous manifest),
 * and whether the run can move the install to a different build (UPDATE yes, VERIFY never).
 *
 * Steam itself has no such split — its client only ever installs/updates to the *current* build,
 * and "verify integrity" verifies against the manifest of the build you have installed. Passing a
 * single `isUpdateOrVerify` boolean meant our Verify had to fetch the latest manifest like an
 * update, so verifying a game silently upgraded it.
 */
enum class SteamDownloadMode {
    /** Fresh install (or resume of one): depots already recorded as downloaded are skipped, and a
     *  run that resumes from a clean pause trusts the chunks its journal recorded instead of
     *  re-hashing them. */
    INSTALL,

    /** Update: take each depot's CURRENT manifest for the branch (PICS), re-hash existing chunks
     *  against it, download the changed ones, and prune content the new manifest no longer lists
     *  (the engine diffs the previous manifest from its local cache). */
    UPDATE,

    /** Verify: pin every depot to the manifest the app has RECORDED AS INSTALLED
     *  (`.DepotDownloader/depot.config` → `installedManifestIDs`) — never the latest one. Chunks
     *  are re-hashed against that manifest and only missing/corrupt ones are re-fetched, so a
     *  verify can never move the install to a different build. Depots with no recorded manifest
     *  are skipped; if none is recorded at all the run fails with a message telling the user to
     *  update instead. */
    VERIFY,
}
