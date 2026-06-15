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
        /**
         * Tabs shown in the UI. Custom (LOCAL) games rely on all-files access, which only the
         * legacy storage flavors have, so the tab is hidden on modern (scoped-storage) builds.
         */
        val visibleEntries: List<LibraryTab>
            get() = if (BuildConfig.MODERN_ANDROID) entries.filter { it != LOCAL } else entries

        fun LibraryTab.next(): LibraryTab {
            val values = visibleEntries
            val index = values.indexOf(this).coerceAtLeast(0)
            return values[(index + 1) % values.size]
        }

        fun LibraryTab.previous(): LibraryTab {
            val values = visibleEntries
            val index = values.indexOf(this).coerceAtLeast(0)
            return values[if (index == 0) values.size - 1 else index - 1]
        }
    }
}
