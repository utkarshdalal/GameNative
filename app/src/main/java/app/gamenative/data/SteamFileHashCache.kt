package app.gamenative.data

import androidx.room.Entity

@Entity(
    tableName = "steam_file_hash_cache",
    primaryKeys = ["appId", "absPath"],
)
data class SteamFileHashCache(
    val appId: Int,
    val absPath: String,
    val sizeBytes: Long,
    val mtimeMillis: Long,
    val sha: ByteArray,
)
