package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A game owned on a store served through a [app.gamenative.service.store.StoreAdapter].
 * [appId] is the stable Int behind library ids and container ids ("<SOURCE>_<appId>");
 * [productId] is the store's own id. [metadata] holds whatever else the adapter needs, as JSON.
 */
@Entity(tableName = "store_games", indices = [Index(value = ["source", "product_id"], unique = true)])
data class StoreGame(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("app_id")
    val appId: Int = 0,
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("product_id")
    val productId: String,
    @ColumnInfo("title")
    val title: String = "",
    @ColumnInfo("icon_url")
    val iconUrl: String = "",
    @ColumnInfo("cover_url")
    val coverUrl: String = "",
    @ColumnInfo("hero_url")
    val heroUrl: String = "",
    @ColumnInfo("developer")
    val developer: String = "",
    @ColumnInfo("release_date")
    val releaseDate: String = "",
    @ColumnInfo("download_size")
    val downloadSize: Long = 0,
    @ColumnInfo("is_installed")
    val isInstalled: Boolean = false,
    @ColumnInfo("install_path")
    val installPath: String = "",
    @ColumnInfo("install_size")
    val installSize: Long = 0,
    @ColumnInfo("installed_version")
    val installedVersion: String = "",
    @ColumnInfo("latest_version")
    val latestVersion: String = "",
    /** Non-empty when the store cannot install this title here; a short reason code for logs. */
    @ColumnInfo("unsupported_reason")
    val unsupportedReason: String = "",
    @ColumnInfo("metadata")
    val metadata: String = "",
    @ColumnInfo("last_played")
    val lastPlayed: Long = 0,
)

fun StoreGame.gameSource(): GameSource = GameSource.valueOf(source)

/** The library/container id, e.g. "EA_12". */
fun StoreGame.libraryAppId(): String = "${source}_$appId"

fun StoreGame.isUpdatePending(): Boolean =
    isInstalled && latestVersion.isNotEmpty() && installedVersion.isNotEmpty() && latestVersion != installedVersion
