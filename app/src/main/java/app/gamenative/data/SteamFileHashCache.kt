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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SteamFileHashCache) return false
        return appId == other.appId && absPath == other.absPath &&
            sizeBytes == other.sizeBytes && mtimeMillis == other.mtimeMillis &&
            sha.contentEquals(other.sha)
    }

    override fun hashCode(): Int {
        var result = appId
        result = 31 * result + absPath.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + mtimeMillis.hashCode()
        result = 31 * result + sha.contentHashCode()
        return result
    }
}
