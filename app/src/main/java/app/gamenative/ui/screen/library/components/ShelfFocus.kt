package app.gamenative.ui.screen.library.components

/**
 * Where the Shelf's focus and scroll go when a recommendation leads the list: the Shelf opens on the game after it
 * (the most recently played one), with the row kept at its start so the recommendation shows at its left.
 */

/** Whether the list starts with a recommended card followed by a game. */
internal fun shelfLeadsWithRecommendation(size: Int, isRecommended: (Int) -> Boolean): Boolean =
    size > 1 && isRecommended(0) && !isRecommended(1)

/** The game the Shelf opens on. */
internal fun shelfHomeIndex(leadsWithRecommendation: Boolean): Int = if (leadsWithRecommendation) 1 else 0

/** The item scrolled to the row's start to show [index]: the row start for the game after a leading recommendation. */
internal fun shelfScrollAnchor(index: Int, leadsWithRecommendation: Boolean): Int =
    if (leadsWithRecommendation && index == 1) 0 else index

/**
 * The Shelf's current game, given the item at the row's start and the focused one. With the row at its start
 * behind a leading recommendation, the current game is the one after it unless the recommendation itself has focus.
 */
internal fun shelfCurrentIndex(startIndex: Int, focusIndex: Int?, leadsWithRecommendation: Boolean): Int =
    if (leadsWithRecommendation && startIndex == 0 && focusIndex != 0) 1 else startIndex

/** A Shelf cover's shear and turn in degrees. */
internal data class ShelfCoverPose(val tiltDeg: Float, val turnDeg: Float)

/**
 * The pose of the cover at [index] against the focused one: covers before it take the base values, covers after it
 * the after values (falling back to the base ones), and the focused cover the base values unless [focusedFlat].
 */
internal fun shelfCoverPose(
    index: Int,
    focusIndex: Int,
    tilt: Float,
    tiltAfter: Float?,
    turn: Float,
    turnAfter: Float?,
    focusedFlat: Boolean,
): ShelfCoverPose = when {
    index < focusIndex -> ShelfCoverPose(tilt, turn)
    index > focusIndex -> ShelfCoverPose(tiltAfter ?: tilt, turnAfter ?: turn)
    focusedFlat -> ShelfCoverPose(0f, 0f)
    else -> ShelfCoverPose(tilt, turn)
}
