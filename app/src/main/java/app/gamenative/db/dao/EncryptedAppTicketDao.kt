package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.EncryptedAppTicket

@Dao
interface EncryptedAppTicketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ticket: EncryptedAppTicket)

    @Query("SELECT * FROM encrypted_app_ticket WHERE app_id = :appId")
    suspend fun getByAppId(appId: Int): EncryptedAppTicket?

    @Delete
    suspend fun delete(ticket: EncryptedAppTicket)

    @Query("DELETE FROM encrypted_app_ticket WHERE app_id = :appId")
    suspend fun deleteByAppId(appId: Int)

    @Query("DELETE from encrypted_app_ticket")
    suspend fun deleteAll()
}

