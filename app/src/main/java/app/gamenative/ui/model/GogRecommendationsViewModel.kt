package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.data.gog.GogRecCard
import app.gamenative.data.gog.GogRecommendationsRepository
import app.gamenative.data.gog.OwnedGameRef
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.service.SteamService
import app.gamenative.service.gog.GOGAuthManager
import app.gamenative.utils.CustomGameScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class GogRecommendationsViewModel @Inject constructor(
    private val libraryPlayHistoryDao: LibraryPlayHistoryDao,
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val cards: List<GogRecCard> = emptyList(),
        val error: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loaded = false

    fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, error = false) }
            try {
                val owned = collectOwnedGames()
                val userId = GOGAuthManager.getStoredCredentials(context).getOrNull()?.userId
                val cards = GogRecommendationsRepository.getRecommendations(
                    context = context,
                    owned = owned,
                    userId = userId,
                )
                _state.update { it.copy(loading = false, cards = cards) }
            } catch (e: Exception) {
                Timber.tag("GogRec").w(e, "Failed to load GOG recommendations")
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    private suspend fun collectOwnedGames(): List<OwnedGameRef> {
        val refs = mutableListOf<OwnedGameRef>()
        val history = libraryPlayHistoryDao.getAll().first().associate { it.appId to it.lastPlayed }

        val steamId = PrefManager.steamUserSteamId64
        if (steamId != 0L) {
            runCatching { SteamService.getOwnedGames(steamId) }.getOrNull()?.forEach { g ->
                refs += OwnedGameRef(
                    name = g.name,
                    steamAppId = g.appId,
                    playtime = g.playtimeForever.toLong(),
                    lastPlayed = history["STEAM_${g.appId}"] ?: (g.rtimeLastPlayed.toLong() * 1000L),
                )
            }
        }

        runCatching { gogGameDao.getAllAsList() }.getOrNull()?.forEach { g ->
            refs += OwnedGameRef(name = g.title, gogId = g.id, playtime = g.playTime, lastPlayed = g.lastPlayed)
        }

        runCatching { epicGameDao.getAllAsList() }.getOrNull()?.forEach { g ->
            refs += OwnedGameRef(
                name = g.title,
                epicNamespace = g.namespace.takeIf { it.isNotBlank() },
                playtime = g.playTime,
                lastPlayed = g.lastPlayed,
            )
        }

        runCatching { amazonGameDao.getAllAsList() }.getOrNull()?.forEach { g ->
            refs += OwnedGameRef(name = g.title, playtime = g.playTimeMinutes, lastPlayed = g.lastPlayed)
        }

        runCatching { CustomGameScanner.scanAsLibraryItems() }.getOrNull()?.forEach { item ->
            refs += OwnedGameRef(name = item.name, lastPlayed = history[item.appId] ?: 0L)
        }

        return refs
    }
}
