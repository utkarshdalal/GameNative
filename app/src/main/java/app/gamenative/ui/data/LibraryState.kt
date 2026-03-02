package app.gamenative.ui.data

import app.gamenative.PrefManager
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.LibraryItem
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.SortOption
import java.util.EnumSet

data class LibraryState(
    val appInfoSortType: EnumSet<AppFilter> = PrefManager.libraryFilter,
    val appInfoList: List<LibraryItem> = emptyList(),
    val isRefreshing: Boolean = false,

    // Human readable, not 0-indexed
    val totalAppsInFilter: Int = 0,
    val currentPaginationPage: Int = 1,
    val lastPaginationPage: Int = 1,

    val modalBottomSheet: Boolean = false,

    val isSearching: Boolean = false,
    val searchQuery: String = "",

    // App Source filters (Steam / Custom Games / GOG / Epic / Amazon)
    val showSteamInLibrary: Boolean = PrefManager.showSteamInLibrary,
    val showCustomGamesInLibrary: Boolean = PrefManager.showCustomGamesInLibrary,
    val showGOGInLibrary: Boolean = PrefManager.showGOGInLibrary,
    val showEpicInLibrary: Boolean = PrefManager.showEpicInLibrary,
    val showAmazonInLibrary: Boolean = PrefManager.showAmazonInLibrary,

    // Loading state for skeleton loaders
    val isLoading: Boolean = false,

    // Refresh counter that increments when custom game images are fetched
    // Used to trigger UI recomposition to show newly downloaded images
    val imageRefreshCounter: Long = 0,

    // Compatibility status map: game name -> compatibility status
    val compatibilityMap: Map<String, GameCompatibilityStatus> = emptyMap(),

    // Sort option for the library
    val currentSortOption: SortOption = PrefManager.librarySortOption,

    // Options panel open state
    val isOptionsPanelOpen: Boolean = false,

    // Current library tab for quick filter access
    val currentTab: LibraryTab = LibraryTab.ALL,

    // Per-source game counts for tab badges
    val allCount: Int = 0,
    val steamCount: Int = 0,
    val gogCount: Int = 0,
    val epicCount: Int = 0,
    val amazonCount: Int = 0,
    val localCount: Int = 0,
)
