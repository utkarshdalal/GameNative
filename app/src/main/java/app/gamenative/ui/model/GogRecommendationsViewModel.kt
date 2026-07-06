package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.data.gog.GogRecCard
import app.gamenative.data.gog.GogRecommendationsRepository
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.service.SteamService
import app.gamenative.service.gog.GOGAuthManager
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
                val steamId = PrefManager.steamUserSteamId64
                val owned = if (steamId != 0L) SteamService.getOwnedGames(steamId) else emptyList()
                val playHistory = libraryPlayHistoryDao.getAll().first()
                    .associate { it.appId to it.lastPlayed }
                val userId = GOGAuthManager.getStoredCredentials(context).getOrNull()?.userId
                val cards = GogRecommendationsRepository.getRecommendations(
                    context = context,
                    ownedSteam = owned,
                    playHistory = playHistory,
                    userId = userId,
                )
                _state.update { it.copy(loading = false, cards = cards) }
            } catch (e: Exception) {
                Timber.tag("GogRec").w(e, "Failed to load GOG recommendations")
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }
}
