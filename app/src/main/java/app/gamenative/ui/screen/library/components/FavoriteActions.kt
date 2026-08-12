package app.gamenative.ui.screen.library.components

import android.content.Context
import app.gamenative.R
import app.gamenative.data.FavoritesManager
import app.gamenative.ui.util.SnackbarManager

/**
 * Toggles the favorite state for [appId]. Both actions show confirmation, while removal also offers
 * an "Undo" action because an accidental un-favorite is easy to miss.
 *
 * [gameName] is used to make the message specific ("Removed <game> from favorites"); when it is
 * null or blank a generic message is shown instead.
 */
internal fun toggleFavoriteWithUndo(context: Context, appId: String, gameName: String?) {
    val wasFavorite = FavoritesManager.isFavorite(appId)
    FavoritesManager.toggle(appId)
    if (wasFavorite) {
        val message = if (gameName.isNullOrBlank()) {
            context.getString(R.string.favorite_removed)
        } else {
            context.getString(R.string.favorite_removed_named, gameName)
        }
        SnackbarManager.show(
            message = message,
            actionLabel = context.getString(R.string.undo),
            onAction = { FavoritesManager.setFavorite(appId, true) },
        )
    } else {
        val message = if (gameName.isNullOrBlank()) {
            context.getString(R.string.favorite_added)
        } else {
            context.getString(R.string.favorite_added_named, gameName)
        }
        SnackbarManager.show(message)
    }
}
