package app.gamenative.ui.util

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

object SnackbarManager {
    /**
     * A snackbar request. [actionLabel] and [onAction] are optional; when both are provided the
     * snackbar shows an action button (e.g. "Undo") that invokes [onAction] when tapped.
     */
    data class Event(
        val message: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
    )

    private val _events = Channel<Event>(capacity = Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun show(message: String) = show(Event(message))

    fun show(message: String, actionLabel: String?, onAction: () -> Unit) =
        show(Event(message, actionLabel, onAction))

    fun show(event: Event) {
        if (_events.trySend(event).isFailure) {
            Timber.w("[Snackbar]: Dropping message because the buffer is full")
        }
    }
}

class SnackbarHostController {
    val hostState = SnackbarHostState()
    private val overlayOwners = mutableStateListOf<Any>()

    val rootOwnsHost: Boolean get() = overlayOwners.isEmpty()

    fun register(owner: Any) {
        overlayOwners.indexOfFirst { it === owner }
            .takeIf { it >= 0 }
            ?.let(overlayOwners::removeAt)
        overlayOwners.add(owner)
    }

    fun unregister(owner: Any) {
        overlayOwners.indexOfFirst { it === owner }
            .takeIf { it >= 0 }
            ?.let(overlayOwners::removeAt)
    }

    fun ownsHost(owner: Any): Boolean = overlayOwners.lastOrNull() === owner
}

val LocalSnackbarHostController = staticCompositionLocalOf<SnackbarHostController> {
    error("SnackbarHostController was not provided")
}
