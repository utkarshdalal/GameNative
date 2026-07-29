package app.gamenative.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gamenative.utils.BionicFgManager
import app.gamenative.utils.BionicFgQuickMenuHelper
import com.winlator.container.Container

/**
 * Quick Menu state + hot-reload callbacks for bionic-fg, passed to QuickMenu
 * as a single object (null = bionic-fg not armed for this container). Kept out
 * of the XServerScreen composable because that function sits at the dex
 * verifier's register limit; adding loose locals there produced a VerifyError
 * at class load.
 */
class BfgMenuState(private val container: Container) {
    var multiplier by mutableIntStateOf(BionicFgManager.multiplier(container))
        private set
    var flowScale by mutableStateOf(BionicFgManager.flowScale(container))
        private set
    var model by mutableIntStateOf(BionicFgManager.model(container))
        private set

    private fun apply() {
        BionicFgQuickMenuHelper.applySettings(
            container,
            BionicFgQuickMenuHelper.Settings(multiplier, flowScale, model),
        )
    }

    fun applyMultiplier(value: Int) {
        multiplier = BionicFgQuickMenuHelper.sanitizeMultiplier(value)
        apply()
    }

    fun applyFlowScale(value: Float) {
        flowScale = BionicFgQuickMenuHelper.sanitizeFlowScale(value)
        apply()
    }

    fun applyModel(value: Int) {
        model = BionicFgQuickMenuHelper.sanitizeModel(value)
        apply()
    }

    companion object {
        /** Returns a state holder when bionic-fg is armed for this container, else null. */
        fun createIfAvailable(container: Container): BfgMenuState? =
            if (BionicFgQuickMenuHelper.isAvailable(container)) BfgMenuState(container) else null
    }
}
