package app.gamenative.ui.enums

import androidx.annotation.StringRes
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
        fun LibraryTab.next(): LibraryTab {
            val values = entries
            val nextIndex = (ordinal + 1) % values.size
            return values[nextIndex]
        }

        fun LibraryTab.previous(): LibraryTab {
            val values = entries
            val prevIndex = if (ordinal == 0) values.size - 1 else ordinal - 1
            return values[prevIndex]
        }
    }
}
