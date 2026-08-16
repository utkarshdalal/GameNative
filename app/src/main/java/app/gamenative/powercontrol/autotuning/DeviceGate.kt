package app.gamenative.powercontrol.autotuning

import android.os.Build

/**
 * Devices whose power control settings were measured and tuned by hand. The list decides the
 * defaults a fresh profile starts with, never whether a feature is available: availability comes
 * from the driver and from the nodes it can reach.
 */
object DeviceGate {
    const val MODEL_RETROID_POCKET_6 = "retroid pocket 6"
    const val MODEL_RETROID_POCKET_NOVA = "retroid pocket nova"
    const val MODEL_ODIN_3 = "odin3"

    private val testedModels = arrayOf(
        MODEL_RETROID_POCKET_6,
        MODEL_RETROID_POCKET_NOVA,
        MODEL_ODIN_3,
    )

    fun isDeviceSupported(model: String = Build.MODEL ?: ""): Boolean {
        val normalized = normalize(model)
        return testedModels.any { normalized.contains(it) }
    }

    fun normalizedModel(model: String = Build.MODEL ?: ""): String {
        return normalize(model)
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
