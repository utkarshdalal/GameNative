package app.gamenative.data

import app.gamenative.db.serializers.OsEnumSetSerializer
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.service.SteamService
import java.util.EnumSet
import kotlinx.serialization.Serializable

@Serializable
data class DepotInfo(
    val depotId: Int,
    val dlcAppId: Int,
    val optionalDlcId: Int = SteamService.INVALID_APP_ID,
    val depotFromApp: Int,
    val sharedInstall: Boolean,
    @Serializable(with = OsEnumSetSerializer::class)
    val osList: EnumSet<OS>,
    val osArch: OSArch,
    val manifests: Map<String, ManifestInfo>,
    val encryptedManifests: Map<String, ManifestInfo>,
    val language: String = "",
    val realm: String = "",
    val systemDefined: Boolean = false,
    val steamDeck: Boolean = false,
) {
    /** Windows or OS-untagged (neither Linux nor macOS) */
    val isWindowsCompatible: Boolean
        get() = osList.contains(OS.windows) ||
            (!osList.contains(OS.linux) && !osList.contains(OS.macos))
}
