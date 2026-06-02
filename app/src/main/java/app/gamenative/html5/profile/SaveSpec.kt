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
    // bypass the FsAuthoritative→FsBridge reroute for this title even when fs writes
    // are detected. set true for Impact-class NW.js titles (CrossCode) where Galaxy
    // desktop's cross-device sync requires BOTH the fs save and the chromium-LS
    // leveldb to be present on cloud. default false preserves the safe fsbridge-only
    // posture for unknown nwjs titles. typically configured per-title via
    // <pack>-patches.json byAppId override, not at pack level.
    val bypassFsBridgeReroute: Boolean = false,
    // dispatched by SaveSyncStrategy.forProfile. unknown values throw. default
    // "leveldb-origin-rewrite" matches every html5 pack we ship -- packs only override
    // when the title genuinely uses a different sync strategy (e.g. "opfs-mirror").
    val mechanism: String = "leveldb-origin-rewrite",
)
