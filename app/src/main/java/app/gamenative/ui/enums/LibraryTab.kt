package app.gamenative.ui.enums

import androidx.annotation.StringRes
import app.gamenative.BuildConfig
import app.gamenative.R

enum class LibraryTab(
    @get:StringRes val labelResId: Int,
    val showCustom: Boolean,
    val showSteam: Boolean,
    val showGoG: Boolean,
    val showEpic: Boolean,
    val showAmazon: Boolean,
    val installedOnly: Boolean,
) {
    ALL(
        labelResId = R.string.tab_all,
        showCustom = true,
        showSteam = true,
        showGoG = true,
        showEpic = true,
        showAmazon = true,
        installedOnly = false,
    ),
    STEAM(
        labelResId = R.string.tab_steam,
        showCustom = false,
        showSteam = true,
        showGoG = false,
        showEpic = false,
        showAmazon = false,
        installedOnly = false,
    ),
    GOG(
        labelResId = R.string.tab_gog,
        showCustom = false,
        showSteam = false,
        showGoG = true,
        showEpic = false,
        showAmazon = false,
        installedOnly = false,
    ),
    EPIC(
        labelResId = R.string.tab_epic,
        showCustom = false,
        showSteam = false,
        showGoG = false,
        showEpic = true,
        showAmazon = false,
        installedOnly = false,
    ),
    AMAZON(
        labelResId = R.string.tab_amazon,
        showCustom = false,
        showSteam = false,
        showGoG = false,
        showEpic = false,
        showAmazon = true,
        installedOnly = false,
    ),
    LOCAL(
        labelResId = R.string.tab_local,
        showCustom = true,
        showSteam = false,
        showGoG = false,
        showEpic = false,
        showAmazon = false,
        installedOnly = false,
    );

    companion object {
        fun availableTabs(): List<LibraryTab> =
            if (BuildConfig.ENABLE_CUSTOM_GAMES) {
                entries
            } else {
                entries.filterNot { it == LOCAL }
            }

        fun sanitize(tab: LibraryTab): LibraryTab =
            availableTabs().find { it == tab } ?: ALL

        fun LibraryTab.next(availableTabs: List<LibraryTab> = LibraryTab.availableTabs()): LibraryTab {
            val currentIndex = availableTabs.indexOf(this)
            if (currentIndex == -1) return ALL
            val nextIndex = (currentIndex + 1) % availableTabs.size
            return availableTabs[nextIndex]
        }

        fun LibraryTab.previous(availableTabs: List<LibraryTab> = LibraryTab.availableTabs()): LibraryTab {
            val currentIndex = availableTabs.indexOf(this)
            if (currentIndex == -1) return ALL
            val prevIndex = if (currentIndex == 0) availableTabs.size - 1 else currentIndex - 1
            return availableTabs[prevIndex]
        }
    }
}
