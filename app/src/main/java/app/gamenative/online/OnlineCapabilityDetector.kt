package app.gamenative.online

import android.content.Context
import app.gamenative.data.StoreGameDetails
import java.util.Locale

/**
 * Resolves whether a game should offer Online/Offline install and which badge to show.
 *
 * Priority: matrix → store tags → SteamApp.noServers hint → offline-only.
 */
object OnlineCapabilityDetector {
    private val ONLINE_TAG_MARKERS = listOf(
        "multi-player",
        "multiplayer",
        "online pvp",
        "online co-op",
        "online coop",
        "co-op",
        "coop",
        "mmo",
        "massively multiplayer",
        "cross-platform multiplayer",
        "online multiplayer",
    )

    fun resolveSteam(
        context: Context,
        steamAppId: Int,
        storeDetails: StoreGameDetails? = null,
    ): OnlineGameInfo {
        val appId = steamAppId.toString()
        OnlineMatrixRepository.get(context, appId, store = "steam")?.let { return it }

        val tagsIndicateOnline = hasOnlineTags(storeDetails?.tags.orEmpty())
        return if (tagsIndicateOnline) {
            OnlineGameInfo.untestedOnline(appId)
        } else {
            OnlineGameInfo.offlineOnly(appId)
        }
    }

    fun hasOnlineTags(tags: Collection<String>): Boolean {
        return tags.any { tag ->
            val normalized = tag.lowercase(Locale.ROOT).trim()
            ONLINE_TAG_MARKERS.any { marker -> normalized.contains(marker) }
        }
    }
}
