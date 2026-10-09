package app.gamenative.service.download

/**
 * What a Steam download run is FOR. The three modes differ in exactly two places: which depot
 * manifests are used ([app.gamenative.service.download.GameDownloadService] `resolveDepotForDownload`),
 * and whether the app's already-downloaded depots are re-included (SteamService depot selection).
 *
 * Steam itself has no such split — its client only ever installs/updates to the *current* build,
 * and "verify integrity" verifies against the manifest of the build you have installed. Passing a
 * single `isUpdateOrVerify` boolean meant our Verify had to fetch the latest manifest like an
 * update, so verifying a game silently upgraded it.
 */
enum class SteamDownloadMode {
    /** Fresh install (or resume of one): depots already recorded as downloaded are skipped, and
     *  the existing on-disk state is trusted via the resume journal (no forced re-hash). */
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
