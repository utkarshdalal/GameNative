package app.gamenative.ui.model

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.service.SteamService
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.EnumSet
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val steamAppDao: SteamAppDao,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Keep the library scroll state. This will last longer as the VM will stay alive.
    var listState: LazyListState by mutableStateOf(LazyListState(0, 0))

    // How many items loaded on one page of results
    private val paginationSize: Int = 20;
    private var paginationCurrentPage: Int = 0;
    private var lastPageInCurrentFilter: Int = 0;

    // Complete and unfiltered app list
    private var appList: List<SteamApp> = emptyList()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            steamAppDao.getAllOwnedApps(
                // ownerIds = SteamService.familyMembers.ifEmpty { listOf(SteamService.userSteamId!!.accountID.toInt()) },
            ).collect { apps ->
                Timber.tag("LibraryViewModel").d("Collecting ${apps.size} apps")

                appList = apps

                onFilterApps()
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

            val firstItem = paginationPage * paginationSize;
            val lastItem = firstItem + paginationSize - 1;
            paginationCurrentPage = paginationPage;

            val filteredList = appList
                .asSequence()
                .filter { item ->
                    SteamService.familyMembers.ifEmpty {
                        listOf(SteamService.userSteamId!!.accountID.toInt())
                    }.map {
                        item.ownerAccountId.contains(it)
                    }.any()
                }
                .filter { item ->
                    currentFilter.any { item.type == it }
                }
                .filter { item ->
                    if (currentState.appInfoSortType.contains(AppFilter.SHARED)) {
                        true
                    } else {
                        item.ownerAccountId.contains(SteamService.userSteamId!!.accountID.toInt())
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
                        SteamService.isAppInstalled(item.id)
                    } else {
                        true
                    }
                }
                // Can sort by downloaded status later. Performance hit currently is massive.
//                .sortedWith(
//                    compareByDescending<SteamApp> { SteamService.isAppInstalled(it.id) }
//                        .thenBy { it.name.lowercase() }
//                );

            // Split here to get a count of the amount of games the filter includes
            val totalFound = filteredList.count()
            lastPageInCurrentFilter =  totalFound / paginationSize // Rounds down, correctly

            val filteredListPage = filteredList
                .filterIndexed { index, steamApp ->
                    // Paginate to only show one page of games
                    index in firstItem..lastItem
                }
                .mapIndexed { idx, item ->
                    LibraryItem(
                        index = idx,
                        appId = item.id,
                        name = item.name,
                        iconHash = item.clientIconHash,
                        isShared = !item.ownerAccountId.contains(SteamService.userSteamId!!.accountID.toInt()),
                    )
                }
                .toList()

            Timber.tag("LibraryViewModel").d("Filtered list size: ${totalFound}")
            _state.update {
                it.copy(
                    appInfoList = filteredListPage,
                    currentPaginationPage = paginationPage + 1,
                    lastPaginationPage = lastPageInCurrentFilter + 1,
                    )
            }
        }
    }
}
