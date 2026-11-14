package app.gamenative.html5.shim

import app.gamenative.data.GameSource
import app.gamenative.data.SteamApp
import app.gamenative.html5.host.WebViewScreenViewModel
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import app.gamenative.service.gog.GOGService
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber

// greenworks DLC queries answered from the launching store's records. games pass STEAM dlc ids even in
// GOG builds; those builds ship GalaxyConfig.json mapping each to its GOG product id.
object Html5DlcResolver {

    // available = owned. installed = owned AND content present, what Steam's BIsDlcInstalled answers.
    data class Dlc(val appId: Int, val name: String, val available: Boolean, val installed: Boolean)

    private const val TAG = "Html5DlcResolver"
    private const val GALAXY_CONFIG = "GalaxyConfig.json"

    fun forContainer(containerId: String): List<Dlc> = runCatching {
        when {
            GameSource.STEAM.matches(containerId) ->
                GameSource.STEAM.idOf(containerId).toIntOrNull()?.let { steamDlcs(it) }.orEmpty()
            GameSource.GOG.matches(containerId) -> {
                // the wine Container's installPath is empty for html5; the sidecar carries it.
                val installPath = WebViewScreenViewModel.slugFromAppId(containerId)
                    ?.let { WebViewContainer.load(it) }?.installPath.orEmpty()
                val config = File(installPath, GALAXY_CONFIG)
                if (config.isFile) {
                    fromGalaxyConfig(
                        json = config.readText(),
                        owns = { GOGService.getGOGGameOf(it) != null },
                        isInstalled = { File(installPath, "goggame-$it.info").isFile },
                    )
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }.onFailure { Timber.tag(TAG).w(it, "dlc lookup failed containerId=%s", containerId) }
        .getOrDefault(emptyList())
        .also { Timber.tag(TAG).i("containerId=%s dlcs=%s", containerId, it) }

    private fun steamDlcs(appId: Int): List<Dlc> = fromSteam(
        baseAppId = appId,
        downloadable = SteamService.getDownloadableDlcAppsOf(appId).orEmpty(),
        hidden = SteamService.getHiddenDlcAppsOf(appId).orEmpty(),
        installedDlcAppIds = SteamService.getInstalledDlcDepotsOf(appId).orEmpty(),
        depotDlcAppIds = SteamService.getAppInfoOf(appId)?.depots?.values?.map { it.dlcAppId }.orEmpty(),
        isAppInstalled = { SteamService.getInstalledApp(it) != null },
    )

    // same installed rules as the wine path's goldberg configs.app.ini (SteamUtils): dlc ids recorded on the
    // base install, separately installed dlc apps, and hidden dlc with at most one depot of its own. the dlc
    // app lists are licence-gated, so every listed entry is owned.
    internal fun fromSteam(
        baseAppId: Int,
        downloadable: List<SteamApp>,
        hidden: List<SteamApp>,
        installedDlcAppIds: List<Int>,
        depotDlcAppIds: List<Int>,
        isAppInstalled: (Int) -> Boolean,
    ): List<Dlc> {
        val out = LinkedHashMap<Int, Dlc>()
        downloadable.filter { it.id != baseAppId }.forEach { app ->
            out[app.id] = Dlc(app.id, app.name, true, app.id in installedDlcAppIds || isAppInstalled(app.id))
        }
        hidden.filter { it.id != baseAppId && it.id !in out }.forEach { app ->
            val installed = app.id in installedDlcAppIds || depotDlcAppIds.count { it == app.id } <= 1
            out[app.id] = Dlc(app.id, app.name, true, installed)
        }
        installedDlcAppIds.filter { it != baseAppId && it !in out }.forEach { id -> out[id] = Dlc(id, "", true, true) }
        return out.values.toList()
    }

    // {"dlcs":[{"steam_id":..,"name":..,"galaxy_id":..}]}. GOG downloads include owned dlc depots, and each
    // installed product leaves goggame-<productId>.info in the install root -- a dlc bought after the install
    // is owned but has no .info until the files are verified or downloaded again.
    internal fun fromGalaxyConfig(json: String, owns: (String) -> Boolean, isInstalled: (String) -> Boolean): List<Dlc> =
        Json.parseToJsonElement(json).jsonObject["dlcs"]?.jsonArray.orEmpty().mapNotNull { element ->
            val entry = element.jsonObject
            val steamId = entry["steam_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val galaxyId = entry["galaxy_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val owned = owns(galaxyId)
            Dlc(steamId, entry["name"]?.jsonPrimitive?.content.orEmpty(), owned, owned && isInstalled(galaxyId))
        }

    fun toJson(dlcs: List<Dlc>): String = buildJsonArray {
        dlcs.forEach { dlc ->
            add(
                buildJsonObject {
                    put("appId", dlc.appId)
                    put("name", dlc.name)
                    put("available", dlc.available)
                    put("installed", dlc.installed)
                },
            )
        }
    }.toString()
}
