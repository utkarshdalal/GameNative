package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.gamenative.data.SteamLicense
import kotlin.math.min

val SQLITE_MAX_VARS = 999

@Dao
interface SteamLicenseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(license: List<SteamLicense>)

    @Update
    suspend fun update(license: SteamLicense)

    @Query("UPDATE steam_license SET app_ids = :appIds WHERE packageId = :packageId")
    suspend fun updateApps(packageId: Int, appIds: List<Int>)

    @Query("UPDATE steam_license SET depot_ids = :depotIds WHERE packageId = :packageId")
    suspend fun updateDepots(packageId: Int, depotIds: List<Int>)

    @Query("SELECT * FROM steam_license")
    suspend fun getAllLicenses(): List<SteamLicense>

    @Query("SELECT * FROM steam_license WHERE packageId = :packageId")
    suspend fun findLicense(packageId: Int): SteamLicense?

    /* ----------------------------------------------------------
       INTERNAL queries that Room generates.  Keep them abstract.
       ---------------------------------------------------------- */

    @Query(
        "SELECT * FROM steam_license " +
                "WHERE packageId NOT IN (:packageIds)"
    )
    suspend fun _findStaleLicences(packageIds: List<Int>): List<SteamLicense>

    @Query(
        "DELETE FROM steam_license " +
                "WHERE packageId IN (:packageIds)"
    )
    suspend fun _deleteStaleLicenses(packageIds: List<Int>)

    /* ----------------------------------------------------------
       PUBLIC wrappers – chunk the list so we never exceed
       SQLite’s 999-parameter ceiling.  These replace the old
       direct queries at call-sites.
       ---------------------------------------------------------- */

    @Transaction
    suspend fun findStaleLicences(packageIds: List<Int>): List<SteamLicense> {
        if (packageIds.isEmpty()) return getAllLicenses()

        val out = mutableListOf<SteamLicense>()
        for (i in packageIds.indices step SQLITE_MAX_VARS) {
            val end = min(i + SQLITE_MAX_VARS, packageIds.size)
            out += _findStaleLicences(packageIds.subList(i, end))
        }
        return out.distinct()
    }

    @Transaction
    suspend fun deleteStaleLicenses(packageIds: List<Int>) {
        for (i in packageIds.indices step SQLITE_MAX_VARS) {
            val end = min(i + SQLITE_MAX_VARS, packageIds.size)
            _deleteStaleLicenses(packageIds.subList(i, end))
        }
    }

    @Query("DELETE from steam_license")
    suspend fun deleteAll()
}
