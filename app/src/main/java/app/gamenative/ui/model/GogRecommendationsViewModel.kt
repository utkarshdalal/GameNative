package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.gog.GogRecCard
import app.gamenative.data.gog.GogRecommendationsRepository
import app.gamenative.data.gog.GogSeedCollector
import app.gamenative.data.gog.OwnedGameRef
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.service.SteamService
import app.gamenative.service.gog.GOGAuthManager
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.DeviceGameStatsCache
import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import app.gamenative.utils.GameCompatibilityCache
import app.gamenative.utils.GameCompatibilityService
import app.gamenative.utils.GpuGameStatsCache
import com.winlator.core.GPUInformation
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
import kotlin.math.ln
import timber.log.Timber

private const val GPU_REVIEW_WEIGHT = 0.3

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
        val compatibilityMap: Map<String, GameCompatibilityStatus> = emptyMap(),
        val deviceGameStats: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap(),
        val gpuGameStats: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap(),
        val error: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loaded = false

    private val gpuName: String by lazy {
        runCatching { GPUInformation.getRenderer(context) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "Unknown GPU"
    }

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
                    daySeed = System.currentTimeMillis() / (24L * 60 * 60 * 1000),
                )
                _state.update { it.copy(loading = false, cards = cards) }
                loadStats(cards.map { it.title })
            } catch (e: Exception) {
                Timber.tag("GogRec").w(e, "Failed to load GOG recommendations")
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    private suspend fun loadStats(names: List<String>) {
        if (names.isEmpty()) return

        // Compatibility (keyed by name, cache-first then batched fetch)
        val responses = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()
        val uncached = mutableListOf<String>()
        for (name in names.toSet()) {
            val cached = GameCompatibilityCache.getCached(name)
            if (cached != null) responses[name] = cached else uncached.add(name)
        }
        if (gpuName != "Unknown GPU") {
            uncached.chunked(25).forEach { batch ->
                GameCompatibilityService.fetchCompatibility(batch, gpuName)?.let {
                    GameCompatibilityCache.cacheAll(it)
                    responses.putAll(it)
                }
            }
        }
        val compatibilityMap = responses.mapValues { compatibilityStatusFor(it.value) }

        // Device / GPU stats are keyed by source+name; recommendations are GOG, but community
        // stats mostly live under other sources, so look each name up across every source.
        val deviceAll = DeviceGameStatsCache.getAll()
        val gpuAll = GpuGameStatsCache.getAll()
        val deviceForRec = mapOf(GameSource.GOG to statsForNames(deviceAll, names))
        val gpuForRec = mapOf(GameSource.GOG to statsForNames(gpuAll, names))
        val gpuReviews = gpuForRec[GameSource.GOG].orEmpty()

        _state.update { state ->
            val reranked = state.cards.sortedByDescending { card ->
                val fiveStar = gpuReviews[card.title]?.fiveStarReviews ?: 0
                card.score * (1.0 + GPU_REVIEW_WEIGHT * ln(1.0 + fiveStar.toDouble()))
            }
            state.copy(
                cards = reranked,
                compatibilityMap = compatibilityMap,
                deviceGameStats = deviceForRec,
                gpuGameStats = gpuForRec,
            )
        }
    }

    private fun statsForNames(
        all: Map<GameSource, Map<String, DeviceGameStats>>,
        names: List<String>,
    ): Map<String, DeviceGameStats> {
        return names.toSet().mapNotNull { name ->
            GameSource.entries.firstNotNullOfOrNull { source -> all[source]?.get(name) }?.let { name to it }
        }.toMap()
    }

    private fun compatibilityStatusFor(
        r: GameCompatibilityService.GameCompatibilityResponse,
    ): GameCompatibilityStatus = when {
        r.isNotWorking -> GameCompatibilityStatus.NOT_COMPATIBLE
        !r.hasBeenTried -> GameCompatibilityStatus.UNKNOWN
        r.gpuPlayableCount > 0 -> GameCompatibilityStatus.GPU_COMPATIBLE
        r.totalPlayableCount > 0 -> GameCompatibilityStatus.COMPATIBLE
        else -> GameCompatibilityStatus.UNKNOWN
    }

    private suspend fun collectOwnedGames(): List<OwnedGameRef> =
        GogSeedCollector.collect(context, libraryPlayHistoryDao, gogGameDao, epicGameDao, amazonGameDao)
}
