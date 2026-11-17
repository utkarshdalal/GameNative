package app.gamenative.powercontrol.autotuning

import android.os.Build

/**
 * Device defaults are enabled only for known/tested device families. Availability of privileged
 * controls still comes from the actual driver and readable kernel interfaces.
 */
object DeviceGate {
    const val MODEL_RETROID_POCKET_6 = "retroid pocket 6"
    const val MODEL_RETROID_POCKET_NOVA = "retroid pocket nova"
    const val MODEL_ODIN_3 = "odin3"

    private val testedModels = arrayOf(
        MODEL_RETROID_POCKET_6,
        MODEL_RETROID_POCKET_NOVA,
    )

    fun isDeviceSupported(model: String = Build.MODEL ?: ""): Boolean {
        val normalized = normalize(model)
        return testedModels.any { normalized.contains(it) }
    }

    /**
     * Helio G99/G100 devices commonly expose the MT6789 family code in HARDWARE, BOARD,
     * PRODUCT or model strings. Detection enables software defaults only; it never assumes
     * root access or the presence of vendor-specific clock-control drivers.
     */
    fun isHelioG99G100(
        model: String = Build.MODEL ?: "",
        hardware: String = Build.HARDWARE ?: "",
        board: String = Build.BOARD ?: "",
        product: String = Build.PRODUCT ?: "",
    ): Boolean {
        val signature = normalize(listOf(model, hardware, board, product).joinToString(" "))
        return listOf("mt6789", "helio g99", "helio g100").any(signature::contains)
    }

    fun normalizedModel(model: String = Build.MODEL ?: ""): String = normalize(model)

    private fun normalize(value: String): String = value.lowercase()
        .replace('_', ' ')
        .replace('-', ' ')
        .replace("\\s+".toRegex(), " ")
        .trim()
}
