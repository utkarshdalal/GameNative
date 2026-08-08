package app.gamenative.html5.profile

import kotlinx.serialization.Serializable

@Serializable
data class SaveSpec(
    val sync: SaveSyncSpec? = SaveSyncSpec(),
)

@Serializable
data class SaveSyncSpec(
    // URL form. OriginCodec.filenameFromUrl derives the IDB filename dir + UTF-16BE
    // bytes for DatabaseNameKey match. default "file://" matches NW.js/Electron-packaged
    // titles loading their index from a local file URL.
    val pcOrigin: String = "file://",
    // optional UFS-pattern override: "%LOCALAPPDATA%/<game>/User Data/Default/" etc.
    val pcPath: String = "",
    // pins which UFS Windows-rooted pattern is the save-bearing one when multiple exist.
    // null → resolver picks first match.
    val ufsPatternIndex: Int? = null,
    // LOCAL_ONLY override -- overrides engine-pack default when the pack default is wrong
    // for this title (e.g. non-standard RMMV save dir).
    val localSaveSubdir: String? = null,
    // chromium-profile hop applied between userDataRoot and `Local Storage/`/`IndexedDB/`.
    // NW.js-distribution titles (CrossCode lineage) write to `<root>/User Data/Default/...`,
    // not directly under `<root>/`. null = no hop (Electron / sideloaded).
    val chromiumProfileSubdir: String? = null,
    // keep this title's chromium User Data profile syncing instead of letting the fs-authoritative
    // reroute scrub it. needed when the store mirrors the whole profile to cloud (GOG/Galaxy does,
    // for CrossCode) -- scrubbing the leveldb would churn against the PC. the fs save (cc.save)
    // syncs either way; default false.
    val syncChromiumProfile: Boolean = false,
    // dispatched by SaveSyncStrategy.forProfile (which owns the routing rules). default
    // "leveldb-origin-rewrite" is the chromium web-storage sync used by every shipped pack; set
    // explicitly only for a strategy a title always uses (e.g. c3+worker → "opfs-mirror").
    val mechanism: String = "leveldb-origin-rewrite",
)
