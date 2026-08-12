package app.gamenative.powercontrol.autotuning

import android.os.Build

/**
 * Devices whose power control settings were measured and tuned by hand. The list decides the
 * defaults a fresh profile starts with, never whether a feature is available: availability comes
 * from the driver and from the nodes it can reach.
 */
object DeviceGate {
    private const val RETROID_POCKET_6_MODEL = "retroid pocket 6"

    private val testedModels = arrayOf(RETROID_POCKET_6_MODEL)

    fun isDeviceSupported(model: String = Build.MODEL ?: ""): Boolean {
        val normalized = normalize(model)
        return testedModels.any { normalized.contains(it) }
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
