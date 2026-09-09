package app.gamenative.savebackup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Metadata stored as `manifest.json` inside a GameNative archive-layout export (schema version 5).
 *
 * This generalizes the Steam-only manifest previously defined inside [app.gamenative.ui.util.SteamSaveTransfer]
 * so it can describe saves from any [app.gamenative.enums.GameSource] (Steam, GOG, Epic, Amazon).
 *
 * Back-compat: the retired Steam manifest wrote the game identifier under the field name `steamAppId`.
 * The generalized manifest names it [gameId]. Relying on `ignoreUnknownKeys = true` alone is not
 * enough — it would silently drop the old `steamAppId` field and then throw `MissingFieldException`
 * for the absent `gameId`. The [JsonNames] alias lets either name deserialize into [gameId], so an
 * existing version-5 archive written with `steamAppId` still imports. New archives write `gameId`.
 *
 * [JsonNames] requires the [ExperimentalSerializationApi] opt-in and the reading `Json` instance to
 * keep `useAlternativeNames = true` (the default).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SaveArchiveManifest(
    val version: Int = 5,
    @JsonNames("steamAppId") val gameId: Int,
    val gameName: String,
    val exportedAt: Long,
    val roots: List<SaveRoot>,
)

/**
 * A single save root recorded in a [SaveArchiveManifest].
 *
 * Field names match the retired Steam manifest's `SaveRoot` so version-5 archives round-trip:
 * - [rootId]: the normalized `PathType` + relative-path identifier used as the on-disk directory
 *   name under `files/` (e.g. `winsavedgames/root`).
 * - [path]: the container-absolute path the root resolved to at export time.
 */
@Serializable
data class SaveRoot(
    val rootId: String,
    val path: String,
)
