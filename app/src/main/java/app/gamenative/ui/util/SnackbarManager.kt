package app.gamenative.ui.util

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

object SnackbarManager {
    private val _messages = Channel<String>(capacity = Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun show(message: String) {
        if (_messages.trySend(message).isFailure) {
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
