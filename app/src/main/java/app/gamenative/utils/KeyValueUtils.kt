package app.gamenative.utils

import app.gamenative.data.BranchInfo
import app.gamenative.data.ConfigInfo
import app.gamenative.data.DepotInfo
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryAssetsInfo
import app.gamenative.data.LibraryCapsuleInfo
import app.gamenative.data.LibraryHeroInfo
import app.gamenative.data.LibraryLogoInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamControllerConfigDetail
import app.gamenative.data.SteamApp
import app.gamenative.data.UFS
import app.gamenative.enums.AppType
import app.gamenative.enums.ControllerSupport
import app.gamenative.enums.Language
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.enums.PathType
import app.gamenative.enums.ReleaseState
import app.gamenative.service.SteamService.Companion.INVALID_APP_ID
import `in`.dragonbra.javasteam.types.KeyValue
import java.util.Date
import timber.log.Timber

/**
 * Extension functions relating to [KeyValue] as the receiver type.
 */

fun KeyValue.generateSteamApp(): SteamApp {
    return SteamApp(
        id = this["appid"].asInteger(INVALID_APP_ID),
        depots = this["depots"].children
            .filter { currentDepot ->
                currentDepot.name?.toIntOrNull() != null
            }
            .associate { currentDepot ->
                val depotId = currentDepot.name!!.toInt()

                val manifests = currentDepot["manifests"].children.generateManifest()

                val encryptedManifests = currentDepot["encryptedManifests"].children.generateManifest()

                depotId to DepotInfo(
                    depotId = depotId,
                    dlcAppId = currentDepot["dlcappid"].asInteger(INVALID_APP_ID),
                    depotFromApp = currentDepot["depotfromapp"].asInteger(
                        INVALID_APP_ID,
                    ),
                    sharedInstall = currentDepot["sharedinstall"].asBoolean(),
                    osList = OS.from(currentDepot["config"]["oslist"].value),
                    osArch = OSArch.from(currentDepot["config"]["osarch"].value),
                    manifests = manifests,
                    encryptedManifests = encryptedManifests,
                    language = currentDepot["config"]["language"].value.orEmpty(),
                    realm = currentDepot["config"]["realm"].value.orEmpty(),
                    optionalDlcId = currentDepot["config"]["optionaldlc"].asInteger(INVALID_APP_ID),
                )
            },
        branches = this["depots"]["branches"].children.associate {
            it.name!! to BranchInfo(
                name = it.name!!,
                buildId = it["buildid"].asLong(),
                pwdRequired = it["pwdrequired"].asBoolean(),
                timeUpdated = Date(it["timeupdated"].asLong() * 1000L),
            )
        },
        name = this["common"]["name"].value.orEmpty(),
        type = AppType.from(this["common"]["type"].value),
        osList = OS.from(this["common"]["oslist"].value),
        releaseState = ReleaseState.from(this["common"]["releasestate"].value),
        releaseDate = this["common"]["steam_release_date"].asLong(),
        metacriticScore = this["common"]["metacritic_score"].asByte(),
        metacriticFullUrl = this["common"]["metacritic_fullurl"].value.orEmpty(),
        logoHash = this["common"]["logo"].value.orEmpty(),
        logoSmallHash = this["common"]["logo_small"].value.orEmpty(),
        iconHash = this["common"]["icon"].value.orEmpty(),
        clientIconHash = this["common"]["clienticon"].value.orEmpty(),
        clientTgaHash = this["common"]["clienttga"].value.orEmpty(),
        smallCapsule = this["common"]["small_capsule"].children.toLangImgMap(),
        headerImage = this["common"]["header_image"].children.toLangImgMap(),
        libraryAssets = LibraryAssetsInfo(
            libraryCapsule = LibraryCapsuleInfo(
                image = this["common"]["library_assets_full"]["library_capsule"]["image"].children.toLangImgMap(),
                image2x = this["common"]["library_assets_full"]["library_capsule"]["image2x"].children.toLangImgMap(),
            ),
            libraryHero = LibraryHeroInfo(
                image = this["common"]["library_assets_full"]["library_hero"]["image"].children.toLangImgMap(),
                image2x = this["common"]["library_assets_full"]["library_hero"]["image2x"].children.toLangImgMap(),
            ),
            libraryLogo = LibraryLogoInfo(
                image = this["common"]["library_assets_full"]["library_logo"]["image"].children.toLangImgMap(),
                image2x = this["common"]["library_assets_full"]["library_logo"]["image2x"].children.toLangImgMap(),
            ),
        ),
        primaryGenre = this["common"]["primary_genre"].asBoolean(),
        reviewScore = this["common"]["review_score"].asByte(),
        reviewPercentage = this["common"]["review_percentage"].asByte(),
        controllerSupport = ControllerSupport.from(this["common"]["controller_support"].value),
        demoOfAppId = this["common"]["extended"]["demoofappid"].asInteger(),
        developer = this["extended"]["developer"].value.orEmpty(),
        publisher = this["extended"]["publisher"].value.orEmpty(),
        homepageUrl = this["extended"]["homepage"].value.orEmpty(),
        gameManualUrl = this["common"]["extended"]["gamemanualurl"].value.orEmpty(),
        loadAllBeforeLaunch = this["common"]["extended"]["loadallbeforelaunch"].asBoolean(),
        // dlcAppIds = (this["common"]["extended"]["listofdlc"].value).Split(",").Select(uint.Parse).ToArray(),
        dlcAppIds = emptyList(),
        isFreeApp = this["common"]["extended"]["isfreeapp"].asBoolean(),
        dlcForAppId = this["extended"]["dlcforappid"].asInteger(this["common"]["extended"]["dlcforappid"].asInteger()),
        mustOwnAppToPurchase = this["common"]["extended"]["mustownapptopurchase"].asInteger(),
        dlcAvailableOnStore = this["common"]["extended"]["dlcavailableonstore"].asBoolean(),
        optionalDlc = this["common"]["extended"]["optionaldlc"].asBoolean(),
        gameDir = this["common"]["extended"]["gamedir"].value.orEmpty(),
        installScript = this["common"]["extended"]["installscript"].value.orEmpty(),
        noServers = this["common"]["extended"]["noservers"].asBoolean(),
        order = this["common"]["extended"]["order"].asBoolean(),
        primaryCache = this["common"]["extended"]["primarycache"].asInteger(),
        validOSList = OS.from(this["common"]["extended"]["validoslist"].value),
        thirdPartyCdKey = this["common"]["extended"]["thirdpartycdkey"].asBoolean(),
        visibleOnlyWhenInstalled = this["common"]["extended"]["visibleonlywheninstalled"].asBoolean(),
        visibleOnlyWhenSubscribed = this["common"]["extended"]["visibleonlywhensubscribed"].asBoolean(),
        launchEulaUrl = this["common"]["extended"]["launcheula"].value.orEmpty(),
        requireDefaultInstallFolder = this["common"]["config"]["requiredefaultinstallfolder"].asBoolean(),
        contentType = this["common"]["config"]["contentType"].asInteger(),
        installDir = this["common"]["config"]["installdir"].value.orEmpty(),
        useLaunchCmdLine = this["common"]["config"]["uselaunchcommandline"].asBoolean(),
        launchWithoutWorkshopUpdates = this["common"]["config"]["launchwithoutworkshopupdates"].asBoolean(),
        useMms = this["common"]["config"]["usemms"].asBoolean(),
        installScriptSignature = this["common"]["config"]["installscriptsignature"].value.orEmpty(),
        installScriptOverride = this["common"]["config"]["installscriptoverride"].asBoolean(),
        config = ConfigInfo(
            installDir = this["config"]["installdir"].value.orEmpty(),
            launch = this["config"]["launch"].children.map {
                LaunchInfo(
                    executable = it["executable"].value?.replace('\\', '/').orEmpty(),
                    workingDir = it["workingdir"].value?.replace('\\', '/').orEmpty(),
                    description = it["description"].value.orEmpty(),
                    type = it["type"].value.orEmpty(),
                    configOS = OS.from(it["config"]["oslist"].value),
                    configArch = OSArch.from(it["config"]["osarch"].value),
                )
            },
            steamControllerTemplateIndex = this["config"]["steamcontrollertemplateindex"].asInteger(),
            steamControllerTouchTemplateIndex = this["config"]["steamcontrollertouchtemplateindex"].asInteger(),
            steamInputManifestPath = this["config"]["steaminputmanifestpath"].value.orEmpty(),
            steamControllerConfigDetails = parseSteamControllerConfigDetails(),
        ),
        ufs = UFS(
            quota = this["ufs"]["quota"].asInteger(),
            maxNumFiles = this["ufs"]["maxnumfiles"].asInteger(),
            saveFilePatterns = this["ufs"]["savefiles"].children.map {
                SaveFilePattern(
                    root = PathType.from(it["root"].value),
                    path = it["path"].value.orEmpty(),
                    pattern = it["pattern"].value.orEmpty(),
                    recursive = it["recursive"].asInteger(0),
                )
            },
        ),
    )
}

private fun KeyValue.parseSteamControllerConfigDetails(): List<SteamControllerConfigDetail> {
    val details = this["config"]["steamcontrollerconfigdetails"]
    if (details.children.isEmpty()) return emptyList()

    return details.children.mapNotNull { detail ->
        val publishedFileId = detail.name?.toLongOrNull() ?: return@mapNotNull null
        val controllerType = detail["controller_type"].value.orEmpty()
        val enabledBranches = detail["enabled_branches"]
            .value
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        SteamControllerConfigDetail(
            publishedFileId = publishedFileId,
            controllerType = controllerType,
            enabledBranches = enabledBranches,
        )
    }
}

fun List<KeyValue>.generateManifest(): Map<String, ManifestInfo> = associate { manifest ->
    manifest.name!! to ManifestInfo(
        name = manifest.name!!,
        gid = manifest["gid"].asLong(),
        size = manifest["size"].asLong(),
        download = manifest["download"].asLong(),
    )
}

fun List<KeyValue>.toLangImgMap(): Map<Language, String> = mapNotNull { kv ->
    Language.from(kv.name!!)
        .takeIf { it != Language.unknown }
        ?.to(kv.value!!)
}.toMap()

@Suppress("unused")
fun KeyValue.printAllKeyValues(depth: Int = 0) {
    val parent = this
    var tabString = ""

    for (i in 0..depth) {
        tabString += "\t"
    }

    if (parent.children.isNotEmpty()) {
        Timber.i("$tabString${parent.name}")

        for (child in parent.children) {
            child.printAllKeyValues(depth + 1)
        }
    } else {
        Timber.i("$tabString${parent.name}: ${parent.value}")
    }
}
