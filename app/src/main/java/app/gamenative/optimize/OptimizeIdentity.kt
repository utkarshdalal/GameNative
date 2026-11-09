package app.gamenative.optimize

import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Who a game is in its store, for the server: the store's own id ([storeId]) and the build installed ([build]).
 * The container's app id is the store id for Steam and GOG, but a local row id for Epic and Amazon.
 */
object OptimizeIdentity {
    private const val MAX_INFO_BYTES = 256L * 1024
    private const val MAX_BUILD_LENGTH = 64

    private val storeIds = ConcurrentHashMap<String, String>()

    /** Steam appid, GOG product id, Epic catalog id or Amazon product id; null when the store row is not there. */
    fun storeId(source: GameSource, gameId: Int, containerAppId: String): String? {
        storeIds[containerAppId]?.let { return it }
        val id = when (source) {
            GameSource.STEAM, GameSource.GOG, GameSource.CUSTOM_GAME -> gameId.toString()
            GameSource.EPIC -> runCatching { EpicService.getEpicGameOf(gameId)?.catalogId }.getOrNull()
            GameSource.AMAZON -> runCatching { AmazonService.getAmazonGameByAppId(gameId)?.productId }.getOrNull()
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        storeIds[containerAppId] = id
        return id
    }

    /**
     * The installed build: the Steam branch's buildid, the buildId (else versionName) of GOG's goggame-<id>.info in
     * [install], the Epic version or the Amazon version id; null when the store does not say.
     */
    fun build(source: GameSource, gameId: Int, install: File?): String? = when (source) {
        GameSource.STEAM -> runCatching {
            val branch = SteamService.getInstalledApp(gameId)?.branch ?: "public"
            SteamService.getAppInfoOf(gameId)?.branches?.get(branch)?.buildId?.takeIf { it > 0 }?.toString()
        }.getOrNull()
        GameSource.GOG -> runCatching { gogInfo(install, gameId)?.let { gogBuild(it) } }.getOrNull()
        GameSource.EPIC -> runCatching { EpicService.getEpicGameOf(gameId)?.version }.getOrNull()
        GameSource.AMAZON -> runCatching { AmazonService.getAmazonGameByAppId(gameId)?.versionId }.getOrNull()
        GameSource.CUSTOM_GAME -> null
    }?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_BUILD_LENGTH)

    /** goggame-<id>.info in the install folder or one folder down. */
    private fun gogInfo(install: File?, gameId: Int): JSONObject? {
        if (install == null || !install.isDirectory) return null
        val name = "goggame-$gameId.info"
        val file = sequenceOf(File(install, name)).plus(
            install.listFiles()?.asSequence()?.filter { it.isDirectory }?.map { File(it, name) }.orEmpty(),
        ).firstOrNull { it.isFile && it.length() in 1..MAX_INFO_BYTES } ?: return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    /** The build a goggame-<id>.info names: buildId, else versionName. */
    fun gogBuild(info: JSONObject): String? = info.optStringOrNull("buildId") ?: info.optStringOrNull("versionName")
}
