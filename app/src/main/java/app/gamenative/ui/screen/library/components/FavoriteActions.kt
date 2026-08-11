package app.gamenative.ui.screen.library.components

import android.content.Context
import app.gamenative.R
import app.gamenative.data.FavoritesManager
import app.gamenative.ui.util.SnackbarManager

/**
 * Toggles the favorite state for [appId], and when a game is being *removed* shows a snackbar with
 * an "Undo" action that puts it back. Adding a favorite is silent (the gold card outline confirms
 * it); only removals get the safety net, since an accidental un-favorite is easy to miss.
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
    }
}
