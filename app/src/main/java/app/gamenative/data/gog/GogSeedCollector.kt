package app.gamenative.data.gog

import android.content.Context
import app.gamenative.Constants
import app.gamenative.PrefManager
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.service.SteamService
import app.gamenative.utils.CustomGameScanner
import kotlinx.coroutines.flow.first

/**
 * Gathers the user's owned/played games across every source into [OwnedGameRef]s (with icons),
 * for use as GOG recommendation seeds. Shared by the recommendations tab and the app-load prefetch.
 */
object GogSeedCollector {

    suspend fun collect(
        context: Context,
        libraryPlayHistoryDao: LibraryPlayHistoryDao,
        gogGameDao: GOGGameDao,
        epicGameDao: EpicGameDao,
        amazonGameDao: AmazonGameDao,
    ): List<OwnedGameRef> {
        val refs = mutableListOf<OwnedGameRef>()
        val history = libraryPlayHistoryDao.getAll().first().associate { it.appId to it.lastPlayed }

        val steamId = PrefManager.steamUserSteamId64
        if (steamId != 0L) {
            runCatching { SteamService.getOwnedGames(steamId) }.getOrNull()?.forEach { g ->
                val icon = g.imgIconUrl.takeIf { it.isNotBlank() }
                    ?.let { "${Constants.Library.ICON_URL}${g.appId}/$it.jpg" }
                refs += OwnedGameRef(
                    name = g.name,
                    steamAppId = g.appId,
                    playtime = g.playtimeForever.toLong(),
                    lastPlayed = history["STEAM_${g.appId}"] ?: (g.rtimeLastPlayed.toLong() * 1000L),
                    iconUrl = icon,
                )
            }
        }

        runCatching { gogGameDao.getAllAsList() }.getOrNull()?.forEach { g ->
            refs += OwnedGameRef(
                name = g.title,
                gogId = g.id,
                playtime = g.playTime,
                lastPlayed = g.lastPlayed,
                iconUrl = g.iconUrl.takeIf { it.isNotBlank() },
            )
        }

        runCatching { epicGameDao.getAllAsList() }.getOrNull()?.forEach { g ->
            refs += OwnedGameRef(
                name = g.title,
                epicNamespace = g.namespace.takeIf { it.isNotBlank() },
                playtime = g.playTime,
                lastPlayed = g.lastPlayed,
                iconUrl = g.iconUrl.takeIf { it.isNotBlank() },
            )
        }

        runCatching { amazonGameDao.getAllAsList() }.getOrNull()?.forEach { g ->
            refs += OwnedGameRef(
                name = g.title,
                playtime = g.playTimeMinutes,
                lastPlayed = g.lastPlayed,
                iconUrl = g.artUrl.takeIf { it.isNotBlank() },
            )
        }

        runCatching { CustomGameScanner.scanAsLibraryItems() }.getOrNull()?.forEach { item ->
            refs += OwnedGameRef(name = item.name, lastPlayed = history[item.appId] ?: 0L)
        }

        return refs
    }
}
