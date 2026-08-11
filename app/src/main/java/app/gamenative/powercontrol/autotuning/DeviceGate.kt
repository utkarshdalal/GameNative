package app.gamenative.powercontrol.autotuning

import android.os.Build

/**
 * Devices whose tuning is handled by [ClusterTuner] instead of [PerformanceAutoTuner].
 */
object DeviceGate {
    private const val RETROID_POCKET_6_MODEL = "retroid pocket 6"

    fun isRetroidPocket6(model: String = Build.MODEL ?: ""): Boolean {
        return normalize(model).contains(RETROID_POCKET_6_MODEL)
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
