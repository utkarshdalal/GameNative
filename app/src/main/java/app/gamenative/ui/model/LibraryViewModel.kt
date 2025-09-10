package app.gamenative.ui.model

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.data.Game
import app.gamenative.data.GameSource
import app.gamenative.service.GameManagerService
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.EnumSet
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class LibraryViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Keep the library scroll state. This will last longer as the VM will stay alive.
    var listState: LazyListState by mutableStateOf(LazyListState(0, 0))

    // How many items loaded on one page of results
    private var paginationCurrentPage: Int = 0
    private var lastPageInCurrentFilter: Int = 0

    // Complete and unfiltered games from all sources
    private var allGames: List<Game> = emptyList()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            GameManagerService.getAllGames().collect { games ->
                if (allGames.size != games.size) {
                    allGames = games
                    onFilterApps(paginationCurrentPage)
                }
            }
        }
    }

    fun onModalBottomSheet(value: Boolean) {
        _state.update { it.copy(modalBottomSheet = value) }
    }

    fun onIsSearching(value: Boolean) {
        _state.update { it.copy(isSearching = value) }
        if (!value) {
            onSearchQuery("")
        }
    }

    fun onSearchQuery(value: String) {
        _state.update { it.copy(searchQuery = value) }
        onFilterApps()
    }

    // TODO: include other sort types
    fun onFilterChanged(value: AppFilter) {
        _state.update { currentState ->
            val updatedFilter = EnumSet.copyOf(currentState.appInfoSortType)

            if (updatedFilter.contains(value)) {
                updatedFilter.remove(value)
            } else {
                updatedFilter.add(value)
            }

            PrefManager.libraryFilter = updatedFilter

            currentState.copy(appInfoSortType = updatedFilter)
        }

        onFilterApps()
    }

    fun onPageChange(pageIncrement: Int) {
        // Amount to change by
        var toPage = max(0, paginationCurrentPage + pageIncrement)
        toPage = min(toPage, lastPageInCurrentFilter)
        onFilterApps(toPage)
    }

    private fun onFilterApps(paginationPage: Int = 0) {
        // May be filtering 1000+ apps - in future should paginate at the point of DAO request
        Timber.tag("LibraryViewModel").d("onFilterApps")
        viewModelScope.launch {
            val currentState = _state.value
            val currentFilter = AppFilter.getAppType(currentState.appInfoSortType)

            val filteredGames = allGames
                .asSequence()
                .filter { game ->
                    when {
                        currentState.appInfoSortType.contains(AppFilter.STEAM) -> game.source == GameSource.STEAM
                        currentState.appInfoSortType.contains(AppFilter.GOG) -> game.source == GameSource.GOG
                        else -> true
                    }
                }
                .filter { item ->
                    if (currentState.appInfoSortType.contains(AppFilter.SHARED)) {
                        true
                    } else {
                        !item.isShared
                    }
                }
                .filter { item ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        item.name.contains(currentState.searchQuery, ignoreCase = true)
                    } else {
                        true
                    }
                }
                .filter { item ->
                    if (currentState.appInfoSortType.contains(AppFilter.INSTALLED)) {
                        item.isInstalled
                    } else {
                        true
                    }
                }
                .filter { item ->
                    if (currentFilter.isNotEmpty()) {
                        currentFilter.contains(item.appType)
                    } else {
                        true
                    }
                }
                .sortedWith(
                    compareByDescending<Game> { it.isInstalled }
                        .thenBy { it.name.lowercase() },
                )
                .toList()

            // Convert to LibraryItems
            val libraryItems = filteredGames.mapIndexed { index, item ->
                item.toLibraryItem(index)
            }

            // Total count for the current filter
            val totalFound = libraryItems.size

            // Determine how many pages and slice the list for incremental loading
            val pageSize = PrefManager.itemsPerPage
            // Update internal pagination state
            paginationCurrentPage = paginationPage
            lastPageInCurrentFilter = if (totalFound > 0) (totalFound - 1) / pageSize else 0
            // Calculate how many items to show: (pagesLoaded * pageSize)
            val endIndex = min((paginationPage + 1) * pageSize, totalFound)
            val pagedLibraryItems = libraryItems.take(endIndex)

            Timber.tag("LibraryViewModel").d("Filtered list size: $totalFound")
            _state.update {
                it.copy(
                    appInfoList = pagedLibraryItems,
                    currentPaginationPage = paginationPage + 1, // visual display is not 0 indexed
                    lastPaginationPage = lastPageInCurrentFilter + 1,
                    totalAppsInFilter = totalFound,
                )
            }
        }
    }
}
