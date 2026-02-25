package app.gamenative.ui.model

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.PluviaApp
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.data.GOGGame
import app.gamenative.data.EpicGame
import app.gamenative.data.AmazonGame
import app.gamenative.data.GameSource
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.events.AndroidEvent
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.GameCompatibilityCache
import app.gamenative.utils.GameCompatibilityService
import app.gamenative.data.GameCompatibilityStatus
import com.winlator.core.GPUInformation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import app.gamenative.ui.enums.PaneType
import java.io.File
import java.util.EnumSet
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val steamAppDao: SteamAppDao,
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState(isLoading = true))
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Keep the library scroll state. This will last longer as the VM will stay alive.
    var listState: LazyGridState by mutableStateOf(LazyGridState(0, 0))

    private val onInstallStatusChanged: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = {
        onFilterApps(paginationCurrentPage)
    }

    private val onCustomGameImagesFetched: (AndroidEvent.CustomGameImagesFetched) -> Unit = {
        // Increment refresh counter and refresh the library list to pick up newly fetched images
        _state.update { it.copy(imageRefreshCounter = it.imageRefreshCounter + 1) }
        onFilterApps(paginationCurrentPage)
    }

    // How many items loaded on one page of results
    private var paginationCurrentPage: Int = 0;
    private var lastPageInCurrentFilter: Int = 0;

    // Complete and unfiltered app list
    private var appList: List<SteamApp> = emptyList()
    private var gogGameList: List<GOGGame> = emptyList()
    private var epicGameList: List<EpicGame> = emptyList()
    private var amazonGameList: List<AmazonGame> = emptyList()

    // Track if this is the first load to apply minimum load time
    private var isFirstLoad = true

    // Track debounce job for search
    private var searchDebounceJob: Job? = null
    private val SEARCH_DEBOUNCE_MS = 500L // 500ms debounce

    // Cache GPU name to avoid repeated calls
    private val gpuName: String by lazy {
        try {
            val gpu = GPUInformation.getRenderer(context)
            if (gpu.isNullOrEmpty()) {
                Timber.tag("LibraryViewModel").w("GPU name is null or empty")
                "Unknown GPU"
            } else {
                Timber.tag("LibraryViewModel").d("Retrieved GPU name: $gpu")
                gpu
            }
        } catch (e: Exception) {
            Timber.tag("LibraryViewModel").e(e, "Failed to get GPU name")
            "Unknown GPU"
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            steamAppDao.getAllOwnedApps(
                // ownerIds = SteamService.familyMembers.ifEmpty { listOf(SteamService.userSteamId!!.accountID.toInt()) },
            ).collect { apps ->
                Timber.tag("LibraryViewModel").d("Collecting ${apps.size} apps")
                // Check if the list has actually changed before triggering a re-filter
                if (appList.size != apps.size) {
                    appList = apps
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        // Collect GOG games
        viewModelScope.launch(Dispatchers.IO) {
            gogGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} GOG games")
                // Check if the list has actually changed before triggering a re-filter
                if (gogGameList != games) {
                    gogGameList = games
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            epicGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} Epic games")

                val hasChanges = epicGameList.size != games.size || epicGameList != games
                epicGameList = games

                if (hasChanges) {
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            amazonGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} Amazon games")
                val hasChanges = amazonGameList.size != games.size || amazonGameList != games
                amazonGameList = games
                if (hasChanges) {
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.on<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
    }

    override fun onCleared() {
        searchDebounceJob?.cancel()
        PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.off<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
        super.onCleared()
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

    fun onSourceToggle(source: GameSource) {
        val current = _state.value
        when (source) {
            GameSource.STEAM -> {
                val newValue = !current.showSteamInLibrary
                PrefManager.showSteamInLibrary = newValue
                _state.update { it.copy(showSteamInLibrary = newValue) }
            }
            GameSource.CUSTOM_GAME -> {
                val newValue = !current.showCustomGamesInLibrary
                PrefManager.showCustomGamesInLibrary = newValue
                _state.update { it.copy(showCustomGamesInLibrary = newValue) }
            }
            GameSource.GOG -> {
                val newValue = !current.showGOGInLibrary
                PrefManager.showGOGInLibrary = newValue
                _state.update { it.copy(showGOGInLibrary = newValue) }
            }
            GameSource.EPIC -> {
                val newValue = !current.showEpicInLibrary
                PrefManager.showEpicInLibrary = newValue
                _state.update { it.copy(showEpicInLibrary = newValue) }
            }
            GameSource.AMAZON -> {
                val newValue = !current.showAmazonInLibrary
                PrefManager.showAmazonInLibrary = newValue
                _state.update { it.copy(showAmazonInLibrary = newValue) }
            }
        }
        onFilterApps(paginationCurrentPage)
    }

    fun onSearchQuery(value: String) {
        // Update UI immediately for responsive typing
        _state.update { it.copy(searchQuery = value) }

        // Cancel previous debounce job
        searchDebounceJob?.cancel()

        // Start new debounce job
        searchDebounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            // Only trigger filter after user stops typing
            onFilterApps()
        }
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

    fun onRefresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }

            // Clear compatibility cache on manual refresh to get fresh data
            GameCompatibilityCache.clear()

            try {
                val newApps = SteamService.refreshOwnedGamesFromServer()
                if (newApps > 0) {
                    Timber.tag("LibraryViewModel").i("Queued $newApps newly owned games for PICS sync")
                } else {
                    Timber.tag("LibraryViewModel").d("No newly owned games discovered during refresh")
                }
                if (app.gamenative.service.gog.GOGService.hasStoredCredentials(context)) {
                    Timber.tag("LibraryViewModel").i("Triggering GOG library refresh")
                    app.gamenative.service.gog.GOGService.triggerLibrarySync(context)
                }
                if (AmazonService.hasStoredCredentials(context)) {
                    Timber.tag("LibraryViewModel").i("Triggering Amazon library refresh")
                    AmazonService.triggerLibrarySync(context)
                }
            } catch (e: Exception) {
                Timber.tag("LibraryViewModel").e(e, "Failed to refresh owned games from server")
            } finally {
                onFilterApps(0).join()
                // Fetch compatibility for current page after refresh
                val currentPageGames = _state.value.appInfoList.map { it.name }
                if (currentPageGames.isNotEmpty()) {
                    fetchCompatibilityForPage(currentPageGames)
                }
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun addCustomGameFolder(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedPath = File(path).absolutePath
            val libraryItem = CustomGameScanner.createLibraryItemFromFolder(normalizedPath)
            if (libraryItem == null) {
                Timber.tag("LibraryViewModel").w("Selected folder is not a valid custom game: $normalizedPath")
                return@launch
            }

            val manualFolders = PrefManager.customGameManualFolders.toMutableSet()
            if (!manualFolders.contains(normalizedPath)) {
                manualFolders.add(normalizedPath)
                PrefManager.customGameManualFolders = manualFolders
            }

            CustomGameScanner.invalidateCache()
            onFilterApps(paginationCurrentPage)
        }
    }

    private fun onFilterApps(paginationPage: Int = 0): Job {
        Timber.tag("LibraryViewModel").d("onFilterApps - appList.size: ${appList.size}, isFirstLoad: $isFirstLoad")
        return viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true) }

            val currentState = _state.value
            val currentFilter = AppFilter.getAppType(currentState.appInfoSortType)

            // Fetch download directory apps once on IO thread and cache as a HashSet for O(1) lookups
            val downloadDirectoryApps = DownloadService.getDownloadDirectoryApps()
            val downloadDirectorySet = downloadDirectoryApps.toHashSet()

            // Filter Steam apps first (no pagination yet)
            // Note: Don't sort individual lists - we'll sort the combined list for consistent ordering
            val filteredSteamApps: List<SteamApp> = appList
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
                    compareByDescending<SteamApp> {
                        downloadDirectorySet.contains(SteamService.getAppDirName(it))
                    }.thenBy { it.name.lowercase() }
                )
                .toList()

            // Map Steam apps to UI items
            data class LibraryEntry(val item: LibraryItem, val isInstalled: Boolean)
            val steamEntries: List<LibraryEntry> = filteredSteamApps.map { item ->
                val isInstalled = downloadDirectorySet.contains(SteamService.getAppDirName(item))
                LibraryEntry(
                    item = LibraryItem(
                        index = 0, // temporary, will be re-indexed after combining and paginating
                        appId = "${GameSource.STEAM.name}_${item.id}",
                        name = item.name,
                        iconHash = item.clientIconHash,
                        capsuleImageUrl = item.getCapsuleUrl(),
                        headerImageUrl = item.headerUrl,
                        heroImageUrl = item.getHeroUrl(),
                        isShared = (PrefManager.steamUserAccountId != 0 && !item.ownerAccountId.contains(PrefManager.steamUserAccountId)),
                    ),
                    isInstalled = isInstalled,
                )
            }

            // Scan Custom Games roots and create UI items (filtered by search query inside scanner)
            // Only include custom games if GAME filter is selected
            val customGameItems = if (currentState.appInfoSortType.contains(AppFilter.GAME)) {
                CustomGameScanner.scanAsLibraryItems(
                    query = currentState.searchQuery
                )
            } else {
                emptyList()
            }
            val customEntries = customGameItems.map { LibraryEntry(it, true) }

            // Filter GOG games
            val filteredGOGGames = gogGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        game.title.contains(currentState.searchQuery, ignoreCase = true)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    if (currentState.appInfoSortType.contains(AppFilter.INSTALLED)) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val gogEntries = filteredGOGGames.map { game ->
                LibraryEntry(
                    item = LibraryItem(
                        index = 0,
                        appId = "${GameSource.GOG.name}_${game.id}",
                        name = game.title,
                        iconHash = game.imageUrl.ifEmpty { game.iconUrl },
                        isShared = false,
                        gameSource = GameSource.GOG,
                    ),
                    isInstalled = game.isInstalled,
                )
            }

            // Filter Epic games
            val filteredEpicGames = epicGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        game.title.contains(currentState.searchQuery, ignoreCase = true)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    if (currentState.appInfoSortType.contains(AppFilter.INSTALLED)) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val epicEntries = filteredEpicGames.map { game ->
                LibraryEntry(
                    item = LibraryItem(
                        index = 0,
                        appId = "EPIC_${game.id}",
                        name = game.title,
                        iconHash = game.artCover,
                        isShared = false,
                        gameSource = GameSource.EPIC,
                    ),
                    isInstalled = game.isInstalled,
                )
            }

            // Amazon games
            val filteredAmazonGames = amazonGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        game.title.contains(currentState.searchQuery, ignoreCase = true)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    if (currentState.appInfoSortType.contains(AppFilter.INSTALLED)) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val amazonEntries = filteredAmazonGames.map { game ->
                LibraryEntry(
                    item = LibraryItem(
                        index = 0,
                        appId = "AMAZON_${game.appId}",
                        name = game.title,
                        iconHash = game.artUrl,
                        isShared = false,
                        gameSource = GameSource.AMAZON,
                    ),
                    isInstalled = game.isInstalled,
                )
            }

            // Calculate installed counts
            val gogInstalledCount = filteredGOGGames.count { it.isInstalled }
            val epicInstalledCount = filteredEpicGames.count { it.isInstalled }
            val amazonInstalledCount = filteredAmazonGames.count { it.isInstalled }
            // Save game counts for skeleton loaders (only when not searching, to get accurate counts)
            // This needs to happen before filtering by source, so we save the total counts
            if (currentState.searchQuery.isEmpty()) {
                PrefManager.customGamesCount = customGameItems.size
                PrefManager.steamGamesCount = filteredSteamApps.size
                PrefManager.gogGamesCount = filteredGOGGames.size
                PrefManager.gogInstalledGamesCount = gogInstalledCount
                PrefManager.epicGamesCount = filteredEpicGames.size
                PrefManager.epicInstalledGamesCount = epicInstalledCount
                PrefManager.amazonInstalledGamesCount = amazonInstalledCount
                Timber.tag("LibraryViewModel").d("Saved counts - Custom: ${customGameItems.size}, Steam: ${filteredSteamApps.size}, GOG: ${filteredGOGGames.size}, GOG installed: $gogInstalledCount, Epic: ${filteredEpicGames.size}, Epic installed: $epicInstalledCount, Amazon installed: $amazonInstalledCount")
            }

            // Apply App Source filters
            val includeSteam = _state.value.showSteamInLibrary
            val includeOpen = _state.value.showCustomGamesInLibrary
            val includeGOG = _state.value.showGOGInLibrary
            val includeEpic = _state.value.showEpicInLibrary
            val includeAmazon = _state.value.showAmazonInLibrary

            // Combine all lists and sort: installed games first, then alphabetically
            val combined = buildList<LibraryEntry> {
                if (includeSteam) addAll(steamEntries)
                if (includeOpen) addAll(customEntries)
                if (includeGOG) addAll(gogEntries)
                if (includeEpic) addAll(epicEntries)
                if (includeAmazon) addAll(amazonEntries)
            }.sortedWith(
                // Primary sort: installed status (0 = installed at top, 1 = not installed at bottom)
                // Secondary sort: alphabetically by name (case-insensitive)
                compareBy<LibraryEntry> { entry ->
                    if (entry.isInstalled) 0 else 1
                }.thenBy { it.item.name.lowercase() }
            ).also { sortedList ->
                if (sortedList.isNotEmpty()) {
                    val installedCount = sortedList.count { it.isInstalled }
                    val first10 = sortedList.take(10)
                }
            }.mapIndexed { idx, entry -> entry.item.copy(index = idx) }

            // Total count for the current filter
            val totalFound = combined.size

            // Determine how many pages and slice the list for incremental loading
            val pageSize = PrefManager.itemsPerPage
            // Update internal pagination state
            paginationCurrentPage = paginationPage
            lastPageInCurrentFilter = if (totalFound == 0) 0 else (totalFound - 1) / pageSize
            // Calculate how many items to show: (pagesLoaded * pageSize)
            val endIndex = min((paginationPage + 1) * pageSize, totalFound)
            val pagedList = combined.take(endIndex)

            Timber.tag("LibraryViewModel").d("Filtered list size (with Custom Games): ${totalFound}")

            if (isFirstLoad) {
                isFirstLoad = false
            }

            // Fetch compatibility for current page games
            fetchCompatibilityForPage(pagedList.map { it.name })

            _state.update {
                it.copy(
                    appInfoList = pagedList,
                    currentPaginationPage = paginationPage + 1, // visual display is not 0 indexed
                    lastPaginationPage = lastPageInCurrentFilter + 1,
                    totalAppsInFilter = totalFound,
                    isLoading = false, // Loading complete
                )
            }
        }
    }

    /**
     * Fetches compatibility information for games in paginated batches.
     * Checks cache first, then fetches uncached games in batches of 50.
     */
    private fun fetchCompatibilityForPage(gameNames: List<String>) {
        if (gameNames.isEmpty()) {
            Timber.tag("LibraryViewModel").d("fetchCompatibilityForPage: No game names provided")
            return
        }

        Timber.tag("LibraryViewModel").d("fetchCompatibilityForPage: Fetching compatibility for ${gameNames.size} games, GPU: $gpuName")

        // Don't make API calls if GPU name is unknown
        if (gpuName == "Unknown GPU") {
            Timber.tag("LibraryViewModel").w("Skipping compatibility fetch - GPU name is unknown")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Separate cached and uncached games
                val uncachedGames = mutableListOf<String>()
                val cachedResults = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

                for (gameName in gameNames) {
                    val cached = GameCompatibilityCache.getCached(gameName)
                    if (cached != null) {
                        cachedResults[gameName] = cached
                        Timber.tag("LibraryViewModel").d("Using cached result for: $gameName")
                    } else {
                        uncachedGames.add(gameName)
                    }
                }

                Timber.tag("LibraryViewModel").d("Cached: ${cachedResults.size}, Uncached: ${uncachedGames.size}")

                // Update state with cached results immediately (for instant UI update)
                if (cachedResults.isNotEmpty()) {
                    updateCompatibilityState(cachedResults)
                }

                // Only fetch if there are uncached games
                if (uncachedGames.isEmpty()) {
                    Timber.tag("LibraryViewModel").d("All games in page are cached, skipping API call")
                    return@launch
                }

                // Fetch uncached games in batches of 25
                val batchSize = 25
                val fetchedResults = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

                for (i in uncachedGames.indices step batchSize) {
                    val batch = uncachedGames.subList(i, min(i + batchSize, uncachedGames.size))
                    Timber.tag("LibraryViewModel").d("Fetching batch ${i / batchSize + 1} with ${batch.size} games")
                    val batchResults = GameCompatibilityService.fetchCompatibility(batch, gpuName)

                    if (batchResults != null) {
                        Timber.tag("LibraryViewModel").d("Received ${batchResults.size} results from API")
                        // Cache all results using batch caching
                        GameCompatibilityCache.cacheAll(batchResults)
                        fetchedResults.putAll(batchResults)
                    } else {
                        Timber.tag("LibraryViewModel").w("API returned null for batch")
                    }
                }

                // Update state with newly fetched results
                if (fetchedResults.isNotEmpty()) {
                    updateCompatibilityState(fetchedResults)
                }
            } catch (e: Exception) {
                Timber.tag("LibraryViewModel").e(e, "Error fetching compatibility data: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Updates the state with compatibility results.
     */
    private fun updateCompatibilityState(
        results: Map<String, GameCompatibilityService.GameCompatibilityResponse>
    ) {
        val compatibilityMap = results.mapValues { (gameName, response) ->
            val status = when {
                response.isNotWorking -> GameCompatibilityStatus.NOT_COMPATIBLE
                !response.hasBeenTried -> GameCompatibilityStatus.UNKNOWN
                response.gpuPlayableCount > 0 -> GameCompatibilityStatus.GPU_COMPATIBLE
                response.totalPlayableCount > 0 -> GameCompatibilityStatus.COMPATIBLE
                else -> GameCompatibilityStatus.UNKNOWN
            }
            status
        }

        // Update state with compatibility map (merge with existing)
        _state.update { currentState ->
            val mergedMap = currentState.compatibilityMap.toMutableMap()
            mergedMap.putAll(compatibilityMap)
            Timber.tag("LibraryViewModel").d("Updated state with ${compatibilityMap.size} compatibility entries, total: ${mergedMap.size}")
            currentState.copy(compatibilityMap = mergedMap)
        }
    }
}
