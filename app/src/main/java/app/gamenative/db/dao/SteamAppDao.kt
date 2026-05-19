package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.gamenative.data.SteamApp
import app.gamenative.service.SteamService.Companion.INVALID_PKG_ID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

// An app is considered "owned" if there's any non-expired license that grants access
// to it: either its own package's license, or any DLC of it (e.g. for free-to-start
// titles like Diablo IV where the actual purchase is a separate "Standard Edition"
// DLC sub that unlocks the base appid). Without the DLC arm, F2P-with-paid-DLC games
// disappear after the user's free-weekend sub on the base appid expires, even though
// they still own the game via the DLC entitlement.
//
// The two arms are split into two separate EXISTS clauses on purpose: most apps will
// be decided by the first (own-license) arm, which is an O(1) PK lookup on
// steam_license. SQLite short-circuits the second (DLC scan) arm in that common case,
// keeping load times comparable to the original PR #985 query for large libraries.
//
// When :includeExpired is 1, the license predicate is bypassed entirely, surfacing
// apps that are normally hidden (used by the "Expired" library filter for diagnostics).
private const val OWNED_APPS_WHERE =
    "WHERE app.id != 480 " + // Actively filter out Spacewar
    "AND app.package_id != :invalidPkgId " +
    "AND app.type != 0 " +
    "AND (" +
    "  :includeExpired = 1 " +
    "  OR EXISTS (" +
    "    SELECT 1 FROM steam_license AS license " +
    "    WHERE license.packageId = app.package_id " +
    "    AND (license.license_flags & 8) = 0 " + // exclude expired licenses (e.g. free weekends)
    "  ) " +
    "  OR EXISTS (" +
    "    SELECT 1 FROM steam_app AS dlc " +
    "    INNER JOIN steam_license AS license ON dlc.package_id = license.packageId " +
    "    WHERE dlc.dlc_for_app_id = app.id " +
    "    AND (license.license_flags & 8) = 0 " +
    "  ) " +
    ") "

private const val PAGE_SIZE = 50

@Dao
interface SteamAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(apps: SteamApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<SteamApp>)

    @Update
    suspend fun update(app: SteamApp)

    // observe change count — triggers re-load without pulling all blobs into one CursorWindow
    @Query(
        "SELECT COUNT(*) FROM steam_app AS app " + OWNED_APPS_WHERE,
    )
    fun _observeOwnedAppCount(
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): Flow<Int>

    // paged data load — each page fits comfortably in a CursorWindow
    @Query(
        "SELECT * FROM steam_app AS app " + OWNED_APPS_WHERE +
            "ORDER BY LOWER(app.name), app.id LIMIT :limit OFFSET :offset",
    )
    suspend fun _getOwnedAppsPage(
        limit: Int,
        offset: Int,
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): List<SteamApp>

    @Transaction
    suspend fun _getAllOwnedAppsPaged(
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): List<SteamApp> {
        val result = mutableListOf<SteamApp>()
        var offset = 0
        while (true) {
            // reset per-offset: try full fetch on first page, PAGE_SIZE thereafter
            var pageSize = if (offset == 0) Int.MAX_VALUE else PAGE_SIZE
            while (true) {
                try {
                    val page = _getOwnedAppsPage(pageSize, offset, invalidPkgId, includeExpired)
                    if (page.isEmpty()) return result
                    result += page
                    if (pageSize == Int.MAX_VALUE) return result // got everything in one shot
                    offset += page.size
                    break
                } catch (e: android.database.sqlite.SQLiteBlobTooBigException) {
                    if (pageSize <= 1) throw e // single row exceeds window, can't recover
                    pageSize = if (pageSize == Int.MAX_VALUE) PAGE_SIZE else (pageSize / 2).coerceAtLeast(1)
                }
            }
        }
    }

    // emits full list on count changes, loaded in pages to avoid CursorWindow overflow.
    // property-only updates (name, icon) won't re-emit until the next count change.
    // Pass includeExpired = true to surface apps whose license is flagged Expired or
    // is missing entirely — used by the library "Expired" filter for diagnostics.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAllOwnedApps(
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Boolean = false,
    ): Flow<List<SteamApp>> {
        val includeExpiredFlag = if (includeExpired) 1 else 0
        return _observeOwnedAppCount(invalidPkgId, includeExpiredFlag)
            .distinctUntilChanged() // skip reload when count unchanged
            .flatMapLatest { // cancel stale reloads during rapid PICS inserts
                flow { emit(_getAllOwnedAppsPaged(invalidPkgId, includeExpiredFlag)) }
            }
    }

    @Query(
        "SELECT * FROM steam_app " +
            "WHERE id != 480 " +
            "AND package_id != :invalidPkgId " +
            "AND type != 0 " +
            "ORDER BY LOWER(name)",
    )
    suspend fun getAllOwnedAppsAsList(
        invalidPkgId: Int = INVALID_PKG_ID,
    ): List<SteamApp>

    @Query("SELECT * FROM steam_app WHERE received_pics = 0 AND package_id != :invalidPkgId AND owner_account_id = :ownerId")
    fun getAllOwnedAppsWithoutPICS(
        ownerId: Int,
        invalidPkgId: Int = INVALID_PKG_ID,
    ): List<SteamApp>

    @Query("SELECT * FROM steam_app WHERE id = :appId")
    suspend fun findApp(appId: Int): SteamApp?

    @Query("SELECT * FROM steam_app AS app WHERE dlc_for_app_id = :appId AND depots <> '{}' AND " +
            " EXISTS (" +
            "   SELECT * FROM steam_license AS license " +
            "     WHERE license.license_type <> 0 AND " +
            "       REPLACE(REPLACE(license.app_ids, '[', ','), ']', ',') LIKE ('%,' || app.id || ',%') " +
            ")"
    )
    suspend fun findDownloadableDLCApps(appId: Int): List<SteamApp>?

    @Query("SELECT * FROM steam_app AS app WHERE dlc_for_app_id = :appId AND depots = '{}' AND " +
            " EXISTS (" +
            "   SELECT * FROM steam_license AS license " +
            "     WHERE license.license_type <> 0 AND " +
            "       REPLACE(REPLACE(license.app_ids, '[', ','), ']', ',') LIKE ('%,' || app.id || ',%') " +
            ")"
    )
    suspend fun findHiddenDLCApps(appId: Int): List<SteamApp>?

    @Query("DELETE from steam_app")
    suspend fun deleteAll()

    @Query("SELECT id FROM steam_app")
    suspend fun getAllAppIds(): List<Int>

    @Query("UPDATE steam_app SET workshop_mods = :workshopMods, enabled_workshop_item_ids = :enabledIds WHERE id = :appId")
    suspend fun updateWorkshopState(appId: Int, workshopMods: Boolean, enabledIds: String)

    @Query("SELECT workshop_mods FROM steam_app WHERE id = :appId")
    suspend fun getWorkshopMods(appId: Int): Boolean?

    @Query("SELECT enabled_workshop_item_ids FROM steam_app WHERE id = :appId")
    suspend fun getEnabledWorkshopItemIds(appId: Int): String?

    @Query("UPDATE steam_app SET workshop_download_pending = :pending WHERE id = :appId")
    suspend fun setWorkshopDownloadPending(appId: Int, pending: Boolean)

    @Query("SELECT id FROM steam_app WHERE workshop_download_pending = 1 AND workshop_mods = 1 AND enabled_workshop_item_ids != ''")
    suspend fun getAppsWithPendingWorkshopDownloads(): List<Int>

    @Query("UPDATE steam_app SET workshop_mods = 0, enabled_workshop_item_ids = '', workshop_download_pending = 0 WHERE id = :appId")
    suspend fun clearWorkshopState(appId: Int)

    @Query("SELECT * FROM steam_app WHERE config LIKE '%\"installDir\":\"' || :dirName || '\",%'")
    suspend fun findSteamAppWithInstallDir(dirName: String): List<SteamApp>

    @Query("SELECT * FROM steam_app WHERE id IN (:appIds)")
    suspend fun findSteamAppWithAppIds(appIds: List<Int>): List<SteamApp>
}
