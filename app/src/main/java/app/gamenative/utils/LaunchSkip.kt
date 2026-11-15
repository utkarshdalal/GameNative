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

    // Who made the current offer; only that owner can withdraw it.
    private var owner: Any? = null

    /** The action of the step that can be skipped now, or null. */
    val action: StateFlow<(() -> Unit)?> = current

    /**
     * Shows the button for [owner], replacing any earlier offer; [onSkip] runs once when it is
     * pressed and the button goes away.
     */
    fun offer(owner: Any, onSkip: () -> Unit) {
        lateinit var once: () -> Unit
        once = {
            if (current.compareAndSet(once, null)) onSkip()
        }
        synchronized(this) {
            this.owner = owner
            current.value = once
        }
    }

    /** Hides the button if [owner] made the current offer; a later offer by another step stays. */
    fun withdraw(owner: Any) {
        synchronized(this) {
            if (this.owner !== owner) return
            this.owner = null
            current.value = null
        }
    }
}
