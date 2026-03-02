package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.gamenative.data.SteamApp
import app.gamenative.service.SteamService.Companion.INVALID_PKG_ID
import kotlinx.coroutines.flow.Flow

@Dao
interface SteamAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(apps: SteamApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<SteamApp>)

    @Update
    suspend fun update(app: SteamApp)

    @Query(
        "SELECT * FROM steam_app " +
            "WHERE id != 480 " + // Actively filter out Spacewar
            // "AND (owner_account_id IN (:ownerIds) OR license_flags & :borrowedCode = :borrowedCode) " +
            "AND package_id != :invalidPkgId " +
            "AND type != 0 " +
            "ORDER BY LOWER(name)",
    )
    fun getAllOwnedApps(
        // ownerIds: List<Int>,
        invalidPkgId: Int = INVALID_PKG_ID,
        // borrowedCode: Int = ELicenseFlags.Borrowed.code(),
    ): Flow<List<SteamApp>>

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
}
