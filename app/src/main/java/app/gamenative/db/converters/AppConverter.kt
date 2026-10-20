package app.gamenative.db.converters

import androidx.room.TypeConverter
import app.gamenative.data.BranchInfo
import app.gamenative.data.ConfigInfo
import app.gamenative.data.DepotInfo
import app.gamenative.data.EulaInfo
import app.gamenative.data.LibraryAssetsInfo
import app.gamenative.data.UFS
import app.gamenative.enums.AppType
import app.gamenative.enums.ControllerSupport
import app.gamenative.enums.Language
import app.gamenative.enums.OS
import app.gamenative.enums.ReleaseState
import java.util.EnumSet
import kotlinx.serialization.json.Json

class AppConverter {

    @TypeConverter
    fun toAppType(appType: Int): AppType = AppType.fromCode(appType)

    @TypeConverter
    fun fromAppType(appType: AppType): Int = appType.code

    @TypeConverter
    fun toOS(os: Int): EnumSet<OS> = OS.from(os)

    @TypeConverter
    fun fromOS(os: EnumSet<OS>): Int = OS.code(os)

    @TypeConverter
    fun toReleaseState(releaseState: Int): ReleaseState = ReleaseState.from(releaseState)

    @TypeConverter
    fun fromReleaseState(releaseState: ReleaseState): Int = releaseState.code

    @TypeConverter
    fun toControllerSupport(controllerSupport: Int): ControllerSupport = ControllerSupport.from(controllerSupport)

    @TypeConverter
    fun fromControllerSupport(controllerSupport: ControllerSupport): Int = controllerSupport.code

    @TypeConverter
    fun toDepots(depots: String): Map<Int, DepotInfo> = Json.decodeFromString<Map<Int, DepotInfo>>(depots)

    @TypeConverter
    fun fromDepots(depots: Map<Int, DepotInfo>): String = Json.encodeToString(depots)

    @TypeConverter
    fun toBranches(branches: String): Map<String, BranchInfo> = Json.decodeFromString<Map<String, BranchInfo>>(branches)

    @TypeConverter
    fun fromBranches(branches: Map<String, BranchInfo>): String = Json.encodeToString(branches)

    @TypeConverter
    fun toLangMap(langMap: String): Map<Language, String> = Json.decodeFromString<Map<Language, String>>(langMap)

    @TypeConverter
    fun fromLangMap(langMap: Map<Language, String>): String = Json.encodeToString(langMap)

    @TypeConverter
    fun toLibraryAssetsInfo(langMap: String): LibraryAssetsInfo = Json.decodeFromString<LibraryAssetsInfo>(langMap)

    @TypeConverter
    fun fromLibraryAssetsInfo(langMap: LibraryAssetsInfo): String = Json.encodeToString(langMap)

    @TypeConverter
    fun toConfigInfo(configInfo: String): ConfigInfo = Json.decodeFromString<ConfigInfo>(configInfo)

    @TypeConverter
    fun fromConfigInfo(configInfo: ConfigInfo): String = Json.encodeToString(configInfo)

    @TypeConverter
    fun toUFS(ufs: String): UFS = Json.decodeFromString<UFS>(ufs)

    @TypeConverter
    fun fromUFS(ufs: UFS): String = Json.encodeToString(ufs)

    @TypeConverter
    fun toEulas(eulas: String): List<EulaInfo> = Json.decodeFromString<List<EulaInfo>>(eulas)

    @TypeConverter
    fun fromEulas(eulas: List<EulaInfo>): String = Json.encodeToString(eulas)
}
