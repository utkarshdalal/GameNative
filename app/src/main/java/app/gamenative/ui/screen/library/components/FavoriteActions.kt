package app.gamenative.ui.screen.library.components

import android.content.Context
import app.gamenative.R
import app.gamenative.data.FavoritesManager
import app.gamenative.ui.util.SnackbarManager

/**
 * Toggles the favorite state for [appId] and shows a confirmation snackbar.
 *
 * [gameName] is used to make the message specific ("Removed <game> from favorites"); when it is
 * null or blank a generic message is shown instead.
 */
internal fun toggleFavorite(context: Context, appId: String, gameName: String?) {
    val favorite = FavoritesManager.toggle(appId) ?: return
    val message = when {
        favorite && gameName.isNullOrBlank() -> context.getString(R.string.favorite_added)
        favorite -> context.getString(R.string.favorite_added_named, gameName)
        gameName.isNullOrBlank() -> context.getString(R.string.favorite_removed)
        else -> context.getString(R.string.favorite_removed_named, gameName)
    }
    SnackbarManager.show(message)
}
