package app.gamenative.html5.profile

import kotlinx.serialization.Serializable

@Serializable
data class SaveSpec(
    val sync: SaveSyncSpec? = SaveSyncSpec(),
)

@Serializable
data class SaveSyncSpec(
    // origin the PC build stores under; "file://" for NW.js/Electron titles loading a local index.
    val pcOrigin: String = "file://",
    // e.g. "%LOCALAPPDATA%/<game>/User Data/Default/"
    val pcPath: String = "",
    // which UFS pattern holds the saves when several exist. null = first match.
    val ufsPatternIndex: Int? = null,
    // for titles whose save dir differs from the pack default (e.g. non-standard RMMV layout).
    val localSaveSubdir: String? = null,
    // hop between userDataRoot and `Local Storage/`/`IndexedDB/`: NW.js titles write under
    // `<root>/User Data/Default/`. null = no hop (Electron / sideloaded).
    val chromiumProfileSubdir: String? = null,
    // bypass the FsAuthoritative→FsBridge reroute for this title even when fs writes
    // are detected. set true for Impact-class NW.js titles (CrossCode) where Galaxy
    // desktop's cross-device sync requires BOTH the fs save and the chromium-LS
    // leveldb to be present on cloud. default false preserves the safe fsbridge-only
    // posture for unknown nwjs titles. typically configured per-title via
    // <pack>-patches.json byAppId override, not at pack level.
    val bypassFsBridgeReroute: Boolean = false,
    // dispatched by SaveSyncStrategy.forProfile (which owns the routing rules). default
    // "leveldb-origin-rewrite" is the chromium web-storage sync used by every shipped pack; set
    // explicitly only for a strategy a title always uses (e.g. c3+worker → "opfs-mirror").
    val mechanism: String = "leveldb-origin-rewrite",
)
