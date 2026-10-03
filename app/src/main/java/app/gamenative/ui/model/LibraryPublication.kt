package app.gamenative.ui.model

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The final idle check and publication share the UI thread, with no suspension between them. */
internal suspend fun publishLibraryWhenIdle(
    isScrolling: () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    publish: () -> Unit,
) = withContext(dispatcher) {
    while (isScrolling()) delay(100L)
    publish()
}
