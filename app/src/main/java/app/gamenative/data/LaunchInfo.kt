package app.gamenative.data

import app.gamenative.db.serializers.OsEnumSetSerializer
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import java.util.EnumSet
import kotlinx.serialization.Serializable

@Serializable
data class LaunchInfo(
    val executable: String,
    val workingDir: String,
    val description: String,
    val type: String,
    @Serializable(with = OsEnumSetSerializer::class)
    val configOS: EnumSet<OS>,
    val configArch: OSArch,
    val arguments: String = "",
)
