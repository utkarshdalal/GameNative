package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.gamenative.enums.AppType
import app.gamenative.enums.ControllerSupport
import app.gamenative.enums.Language
import app.gamenative.enums.OS
import app.gamenative.enums.ReleaseState
import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import java.util.EnumSet

@Entity("steam_app")
data class SteamApp(
    @PrimaryKey val id: Int,
    @ColumnInfo("package_id")
    val packageId: Int = SteamService.INVALID_PKG_ID,
    @ColumnInfo("owner_account_id")
    val ownerAccountId: List<Int> = emptyList(),
    @ColumnInfo("license_flags")
    val licenseFlags: EnumSet<ELicenseFlags> = EnumSet.noneOf(ELicenseFlags::class.java),
    @ColumnInfo("received_pics")
    val receivedPICS: Boolean = false,
    @ColumnInfo("last_change_number")
    val lastChangeNumber: Int = 0,

    @ColumnInfo("depots")
    val depots: Map<Int, DepotInfo> = emptyMap(),
    @ColumnInfo("branches")
    val branches: Map<String, BranchInfo> = emptyMap(),

    // Common
    @ColumnInfo("name")
    val name: String = "",
    @ColumnInfo("type")
    val type: AppType = AppType.invalid,
    @ColumnInfo("os_list")
    val osList: EnumSet<OS> = EnumSet.of(OS.none),
    @ColumnInfo("release_state")
    val releaseState: ReleaseState = ReleaseState.disabled,
    @ColumnInfo("release_date")
    val releaseDate: Long = 0L,
    @ColumnInfo("metacritic_score")
    val metacriticScore: Byte = 0,
    @ColumnInfo("metacritic_full_url")
    val metacriticFullUrl: String = "",
    // source: https://github.com/JosefNemec/PlayniteExtensions/blob/f2ad8c9b2ca206195e2c94b75606b56e2f6281df/source/Libraries/SteamLibrary/SteamShared/MetadataProvider.cs#L164
    @ColumnInfo("logo_hash")
    val logoHash: String = "", // https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/{appId}/{logoHash}.jpg
    @ColumnInfo("logo_small_hash")
    val logoSmallHash: String = "", // https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/{appId}/{logoSmallHash}.jpg
    @ColumnInfo("icon_hash")
    val iconHash: String = "", // https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/{appId}/{iconHash}.jpg
    @ColumnInfo("client_icon_hash")
    val clientIconHash: String = "", // https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/{appId}/{clientIconHash}.ico
    @ColumnInfo("client_tga_hash")
    val clientTgaHash: String = "", // https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/{appId}/{clientTgaHash}.tga
    @ColumnInfo("small_capsule")
    val smallCapsule: Map<Language, String> = emptyMap(),
    @ColumnInfo("header_image")
    val headerImage: Map<Language, String> = emptyMap(),
    @ColumnInfo("library_assets")
    val libraryAssets: LibraryAssetsInfo = LibraryAssetsInfo(),
    @ColumnInfo("primary_genre")
    val primaryGenre: Boolean = false,
    @ColumnInfo("review_score")
    val reviewScore: Byte = 0,
    @ColumnInfo("review_percentage")
    val reviewPercentage: Byte = 0,
    @ColumnInfo("controller_support")
    val controllerSupport: ControllerSupport = ControllerSupport.none,

    // Extended
    @ColumnInfo("demo_of_app_id")
    val demoOfAppId: Int = SteamService.INVALID_APP_ID,
    @ColumnInfo("developer")
    val developer: String = "",
    @ColumnInfo("publisher")
    val publisher: String = "",
    @ColumnInfo("homepage_url")
    val homepageUrl: String = "",
    @ColumnInfo("game_manual_url")
    val gameManualUrl: String = "",
    @ColumnInfo("load_all_before_launch")
    val loadAllBeforeLaunch: Boolean = false,
    @ColumnInfo("dlc_app_ids")
    val dlcAppIds: List<Int> = emptyList(),
    @ColumnInfo("is_free_app")
    val isFreeApp: Boolean = false,
    @ColumnInfo("dlc_for_app_id")
    val dlcForAppId: Int = SteamService.INVALID_APP_ID,
    @ColumnInfo("must_own_app_to_purchase")
    val mustOwnAppToPurchase: Int = SteamService.INVALID_APP_ID,
    @ColumnInfo("dlc_available_on_store")
    val dlcAvailableOnStore: Boolean = false,
    @ColumnInfo("optional_dlc")
    val optionalDlc: Boolean = false,
    @ColumnInfo("game_dir")
    val gameDir: String = "",
    @ColumnInfo("install_script")
    val installScript: String = "",
    @ColumnInfo("no_servers")
    val noServers: Boolean = false,
    @ColumnInfo("order")
    val order: Boolean = false,
    @ColumnInfo("primary_cache")
    val primaryCache: Int = 0,
    @ColumnInfo("valid_os_list")
    val validOSList: EnumSet<OS> = EnumSet.of(OS.none),
    @ColumnInfo("third_party_cd_key")
    val thirdPartyCdKey: Boolean = false,
    @ColumnInfo("visible_only_when_installed")
    val visibleOnlyWhenInstalled: Boolean = false,
    @ColumnInfo("visible_only_when_subscribed")
    val visibleOnlyWhenSubscribed: Boolean = false,
    @ColumnInfo("launch_eula_url")
    val launchEulaUrl: String = "",

    // Config
    @ColumnInfo("require_default_install_folder")
    val requireDefaultInstallFolder: Boolean = false,
    @ColumnInfo("content_type")
    val contentType: Int = 0,
    @ColumnInfo("install_dir")
    val installDir: String = "",
    @ColumnInfo("use_launch_cmd_line")
    val useLaunchCmdLine: Boolean = false,
    @ColumnInfo("launch_without_workshop_updates")
    val launchWithoutWorkshopUpdates: Boolean = false,
    @ColumnInfo("use_mms")
    val useMms: Boolean = false,
    @ColumnInfo("install_script_signature")
    val installScriptSignature: String = "",
    @ColumnInfo("install_script_override")
    val installScriptOverride: Boolean = false,

    @ColumnInfo("config")
    val config: ConfigInfo = ConfigInfo(),

    @ColumnInfo("ufs")
    val ufs: UFS = UFS(),
) {
    val logoUrl: String
        get() = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/$id/$logoHash.jpg"
    val logoSmallUrl: String
        get() = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/$id/$logoSmallHash.jpg"
    val iconUrl: String
        get() = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/$id/$iconHash.jpg"
    val clientIconUrl: String
        get() = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/$id/$clientIconHash.ico"
    val clientTgaUrl: String
        get() = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/$id/$clientTgaHash.tga"
    val headerUrl: String
        get() = "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg"

    // source: https://github.com/Nemirtingas/games-infos/blob/3915100198bac34553b3c862f9e295d277f5520a/steam_retriever/Program.cs#L589C43-L589C89
    fun getSmallCapsuleUrl(language: Language = Language.english): String? {
        return smallCapsule[language]?.let {
            "https://cdn.akamai.steamstatic.com/steam/apps/$id/$it"
        }
    }

    fun getHeaderImageUrl(language: Language = Language.english): String? {
        return headerImage[language]?.let {
            "https://cdn.akamai.steamstatic.com/steam/apps/$id/$it"
        }
    }

    private fun getFallbackUrl(images: Map<Language, String>, language: Language): String? {
        return if (images.contains(language)) {
            images[language]
        } else if (!images.isEmpty()) {
            // Fallback to another image in the images map
            images.values.first()
        } else if (headerImage.contains(language)) {
            // Fallback to headerImages instead
            headerImage[language]
        } else if (!headerImage.isEmpty()) {
            // Fallback to another image in the headerImage map
            headerImage.values.first()
        } else {
            // No valid hero image
            ""
        }
    }

    fun getCapsuleUrl(language: Language = Language.english, large: Boolean = false): String {
        val capsules = if (large) {
            libraryAssets.libraryCapsule.image2x
        } else {
            libraryAssets.libraryCapsule.image
        }

        val imageLink = this.getFallbackUrl(capsules, language)
        return if (imageLink.isNullOrEmpty()) "" else "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/$imageLink"
    }

    fun getHeroUrl(language: Language = Language.english, large: Boolean = false): String {
        val images = if (large) {
            libraryAssets.libraryHero.image2x
        } else {
            libraryAssets.libraryHero.image
        }

        val imageLink = this.getFallbackUrl(images, language)
        return if (imageLink.isNullOrEmpty()) "" else "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/$imageLink"
    }

    fun getLogoUrl(language: Language = Language.english, large: Boolean = false): String? {
        return if (large) {
            libraryAssets.libraryLogo.image2x[language]?.let {
                "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/$it"
            }
        } else {
            libraryAssets.libraryLogo.image[language]?.let {
                "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/$it"
            }
        }
    }
}
