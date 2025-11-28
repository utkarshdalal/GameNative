package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import `in`.dragonbra.javasteam.enums.EResult

@Entity("encrypted_app_ticket")
data class EncryptedAppTicket(
    @PrimaryKey
    @ColumnInfo("app_id")
    val appId: Int,
    
    @ColumnInfo("result")
    val result: Int, // EResult stored as Int
    
    @ColumnInfo("ticket_version_no")
    val ticketVersionNo: Int,
    
    @ColumnInfo("crc_encrypted_ticket")
    val crcEncryptedTicket: Int,
    
    @ColumnInfo("cb_encrypted_user_data")
    val cbEncryptedUserData: Int,
    
    @ColumnInfo("cb_encrypted_app_ownership_ticket")
    val cbEncryptedAppOwnershipTicket: Int,
    
    @ColumnInfo("encrypted_ticket")
    val encryptedTicket: ByteArray,
    
    @ColumnInfo("timestamp")
    val timestamp: Long,
) {
    // Helper to get EResult
    fun getResult(): EResult = EResult.from(result)
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedAppTicket

        if (appId != other.appId) return false
        if (result != other.result) return false
        if (ticketVersionNo != other.ticketVersionNo) return false
        if (crcEncryptedTicket != other.crcEncryptedTicket) return false
        if (cbEncryptedUserData != other.cbEncryptedUserData) return false
        if (cbEncryptedAppOwnershipTicket != other.cbEncryptedAppOwnershipTicket) return false
        if (!encryptedTicket.contentEquals(other.encryptedTicket)) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result1 = appId
        result1 = 31 * result1 + result
        result1 = 31 * result1 + ticketVersionNo
        result1 = 31 * result1 + crcEncryptedTicket
        result1 = 31 * result1 + cbEncryptedUserData
        result1 = 31 * result1 + cbEncryptedAppOwnershipTicket
        result1 = 31 * result1 + encryptedTicket.contentHashCode()
        result1 = 31 * result1 + timestamp.hashCode()
        return result1
    }
}

