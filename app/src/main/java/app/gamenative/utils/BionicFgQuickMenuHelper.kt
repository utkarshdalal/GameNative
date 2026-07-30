package app.gamenative.utils

import com.winlator.container.Container
import java.util.Locale

/** Helpers for Quick Menu bionic-fg state persistence and runtime hot-reload. */
object BionicFgQuickMenuHelper {
    data class Settings(
        val multiplier: Int,
        val flowScale: Float,
        val model: Int,
    )

    fun isAvailable(container: Container): Boolean =
        BionicFgManager.isArmed(container)

    fun readSettings(container: Container): Settings = Settings(
        multiplier = BionicFgManager.multiplier(container),
        flowScale = BionicFgManager.flowScale(container),
        model = BionicFgManager.model(container),
    )

    fun sanitizeMultiplier(multiplier: Int): Int =
        if (multiplier < 2) 0 else multiplier.coerceIn(2, 4)

    fun sanitizeFlowScale(flowScale: Float): Float =
        flowScale.coerceIn(0.2f, 1.0f)

    fun sanitizeModel(model: Int): Int =
        model.coerceIn(0, 1)

    fun applySettings(container: Container, settings: Settings) {
        val multiplier = sanitizeMultiplier(settings.multiplier)
        val flowScale = sanitizeFlowScale(settings.flowScale)
        val model = sanitizeModel(settings.model)

        container.putExtra(BionicFgManager.EXTRA_MULTIPLIER, multiplier.toString())
        container.putExtra(BionicFgManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale))
        container.putExtra(BionicFgManager.EXTRA_MODEL, model.toString())
        container.saveData()

        BionicFgManager.updateConfigAtRuntime(container)
    }
}
