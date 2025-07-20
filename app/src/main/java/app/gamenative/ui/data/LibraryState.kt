package app.gamenative.ui.data

import app.gamenative.PrefManager
import app.gamenative.data.LibraryItem
import app.gamenative.ui.enums.AppFilter
import java.util.EnumSet

data class LibraryState(
    val appInfoSortType: EnumSet<AppFilter> = PrefManager.libraryFilter,
    val appInfoList: List<LibraryItem> = emptyList(),

    // Human readable, not 0-indexed
    val totalAppsInFilter: Int = 0,
    val currentPaginationPage: Int = 1,
    val lastPaginationPage: Int = 1,

    val modalBottomSheet: Boolean = false,

    val isSearching: Boolean = false,
    val searchQuery: String = "",
)
