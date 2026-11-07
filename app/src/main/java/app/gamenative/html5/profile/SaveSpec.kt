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
    // keep syncing the chromium profile instead of letting the fs reroute scrub it. needed when the
    // store mirrors the whole profile to cloud (GOG CrossCode) -- scrubbing would churn against the PC.
    val syncChromiumProfile: Boolean = false,
    // routed by SaveSyncStrategy.forProfile. set explicitly only for a strategy a title always
    // uses (e.g. c3+worker -> "opfs-mirror").
    val mechanism: String = "leveldb-origin-rewrite",
)
