package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity to store raw License objects from JavaSteam.
 * Each license is stored in its own row.
 * Used by DepotDownloader which requires List<License>.
 */
@Entity("cached_license")
data class CachedLicense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo("license_json")
    val licenseJson: String, // Serialized License object as Base64
)

