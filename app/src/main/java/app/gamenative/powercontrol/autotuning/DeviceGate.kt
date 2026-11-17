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
    )

    /**
     * MediaTek MT6789-family devices include Helio G99/G100 variants.
     * This is a conservative capability gate only; it does not write CPU/GPU clocks,
     * disable thermal protections, or assume that every vendor kernel exposes the same nodes.
     */
    fun isMediaTekG99G100(
        socModel: String = runCatching { Build.SOC_MODEL }.getOrDefault(""),
        hardware: String = Build.HARDWARE ?: "",
        board: String = Build.BOARD ?: "",
    ): Boolean {
        val fingerprint = normalize("$socModel $hardware $board")
        return fingerprint.contains("mt6789") ||
            fingerprint.contains("mt6789v") ||
            fingerprint.contains("mt6789z")
    }

    /**
     * True only for models whose CPU power-control presets have been measured.
     * G99/G100 detection alone is not proof that this kernel exposes safe writable controls.
     */
    fun isPowerControlPresetValidated(model: String = Build.MODEL ?: ""): Boolean {
        val normalized = normalize(model)
        return testedModels.any { normalized.contains(it) }
    }

    /**
     * Devices eligible for software frame pacing. This is deliberately broader than
     * [isPowerControlPresetValidated] because software pacing does not imply clock access.
     */
    fun isDeviceSupported(model: String = Build.MODEL ?: ""): Boolean {
        return isPowerControlPresetValidated(model) || isMediaTekG99G100()
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
