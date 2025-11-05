package app.gamenative.ui.model

import android.content.Context
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.data.GameSource
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.utils.ContainerUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Keep the library scroll state. This will last longer as the VM will stay alive.
    var listState: LazyGridState by mutableStateOf(LazyGridState(0, 0))

    // How many items loaded on one page of results
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

                if (appList.size != apps.size) {
                    // Don't filter if it's no change
                    appList = apps

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

            val downloadDirectoryApps = DownloadService.getDownloadDirectoryApps()
            
            // Get custom containers
            val customContainers = ContainerUtils.getCustomContainers(context)

            var filteredList = appList
                .asSequence()
                .filter { item ->
                    SteamService.familyMembers.ifEmpty {
                        // Handle the case where userSteamId might be null
                        SteamService.userSteamId?.let { steamId ->
                            listOf(steamId.accountID.toInt())
                        } ?: emptyList()
                    }.let { owners ->
                        if (owners.isEmpty()) {
                            true                       // no owner info ⇒ don’t filter the item out
                        } else {
                            owners.any { item.ownerAccountId.contains(it) }
                        }
                    }
                }
                .filter { item ->
                    currentFilter.any { item.type == it }
                }
                .filter { item ->
                    if (currentState.appInfoSortType.contains(AppFilter.SHARED)) {
                        true
                    } else {
                        item.ownerAccountId.contains(PrefManager.steamUserAccountId) || PrefManager.steamUserAccountId == 0
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
                        downloadDirectoryApps.contains(SteamService.getAppDirName(item))
                    } else {
                        true
                    }
                }
                .sortedWith(
                    // Comes from DAO in alphabetical order
                    compareByDescending<SteamApp> { downloadDirectoryApps.contains(SteamService.getAppDirName(it)) }
                );

            // Total count for the current filter (Steam apps only for now)
            val steamAppCount = filteredList.count()
            
            // Add custom containers to the list
            val containerItems = customContainers
                .filter { container ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        container.name.contains(currentState.searchQuery, ignoreCase = true)
                    } else {
                        true
                    }
                }

            val totalFound = steamAppCount + containerItems.size

            // Determine how many pages and slice the list for incremental loading
            val pageSize = PrefManager.itemsPerPage
            // Update internal pagination state
            paginationCurrentPage = paginationPage
            lastPageInCurrentFilter = (totalFound - 1) / pageSize
            // Calculate how many items to show: (pagesLoaded * pageSize)
            val endIndex = min((paginationPage + 1) * pageSize, totalFound)
            
            // Map Steam apps to LibraryItems
            val steamItems = filteredList
                .take(min(endIndex, steamAppCount))
                .mapIndexed { idx, item ->
                    LibraryItem(
                        index = idx,
                        appId = "${GameSource.STEAM.name}_${item.id}",
                        name = item.name,
                        iconHash = item.clientIconHash,
                        isShared = (PrefManager.steamUserAccountId != 0 && !item.ownerAccountId.contains(PrefManager.steamUserAccountId)),
                        gameSource = GameSource.STEAM,
                    )
                }
                .toList()
            
            // Map custom containers to LibraryItems
            val customContainerItems = containerItems
                .take(max(0, endIndex - steamAppCount))
                .mapIndexed { idx, container ->
                    LibraryItem(
                        index = steamItems.size + idx,
                        appId = "${GameSource.CONTAINER.name}_${container.id}",
                        name = "🗂️ ${container.name}",  // Add container icon emoji
                        iconHash = "",
                        isShared = false,
                        gameSource = GameSource.CONTAINER,
                    )
                }
                .toList()
            
            val filteredListPage = steamItems + customContainerItems

            Timber.tag("LibraryViewModel").d("Filtered list size: ${totalFound}")
            _state.update {
                it.copy(
                    appInfoList = filteredListPage,
                    currentPaginationPage = paginationPage + 1, // visual display is not 0 indexed
                    lastPaginationPage = lastPageInCurrentFilter + 1,
                    totalAppsInFilter = totalFound,
                    )
            }
        }
    }
}
