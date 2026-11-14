package app.gamenative.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Skip button on the launch screens. A launch step that may take long and is not needed to
 * start the game offers an action while it runs; the loading dialog and the booting splash show
 * the button for as long as one is offered.
 */
object LaunchSkip {
    private val current = MutableStateFlow<(() -> Unit)?>(null)

    /** The action of the step that can be skipped now, or null. */
    val action: StateFlow<(() -> Unit)?> = current

    /** Shows the button; [onSkip] runs once when it is pressed and the button goes away. */
    fun offer(onSkip: () -> Unit) {
        lateinit var once: () -> Unit
        once = {
            if (current.compareAndSet(once, null)) onSkip()
        }
        current.value = once
    }

    fun withdraw() {
        current.value = null
    }
}
