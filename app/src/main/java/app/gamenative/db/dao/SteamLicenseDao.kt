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
                "WHERE packageId IN (:packageIds)"
    )
    suspend fun _findLicenses(packageIds: List<Int>): List<SteamLicense>

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

    // batched to stay under SQLite's 999 bind-variable limit
    @Transaction
    suspend fun findLicenses(packageIds: List<Int>): List<SteamLicense> {
        if (packageIds.isEmpty()) return emptyList()
        val results = mutableListOf<SteamLicense>()
        for (chunkStart in packageIds.indices step SQLITE_MAX_VARS) {
            val chunkEnd = min(chunkStart + SQLITE_MAX_VARS, packageIds.size)
            results += _findLicenses(packageIds.subList(chunkStart, chunkEnd))
        }
        return results
    }

    // batched NOT IN — intersects chunks so only licenses absent from ALL chunks are returned
    @Transaction
    suspend fun findStaleLicences(packageIds: List<Int>): List<SteamLicense> {
        if (packageIds.isEmpty()) return getAllLicenses()
        val results = mutableListOf<SteamLicense>()
        for (chunkStart in packageIds.indices step SQLITE_MAX_VARS) {
            val chunkEnd = min(chunkStart + SQLITE_MAX_VARS, packageIds.size)
            val chunkResult = _findStaleLicences(packageIds.subList(chunkStart, chunkEnd))
            if (results.isEmpty()) {
                results += chunkResult
            } else {
                results.retainAll(chunkResult)
            }
        }
        return results.distinct()
    }

    @Transaction
    suspend fun deleteStaleLicenses(packageIds: List<Int>) {
        for (chunkStart in packageIds.indices step SQLITE_MAX_VARS) {
            val chunkEnd = min(chunkStart + SQLITE_MAX_VARS, packageIds.size)
            _deleteStaleLicenses(packageIds.subList(chunkStart, chunkEnd))
        }
    }

    @Query("DELETE from steam_license")
    suspend fun deleteAll()
}
