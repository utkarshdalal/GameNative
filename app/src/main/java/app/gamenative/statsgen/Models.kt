package app.gamenative.statsgen

data class Achievement(
    val name: String,
    val displayName: Map<String, String>? = null,
    val description: Map<String, String>? = null,
    val hidden: Int = 0,
    val icon: String? = null,
    val iconGray: String? = null,
    val icongray: String? = null,
    val progress: Map<String, Any>? = null
)

data class Stat(
    val name: String,
    val type: String,
    val default: String = "0",
    val global: String = "0",
    val min: String? = null
)

data class ProcessingResult(
    val achievements: List<Achievement>,
    val stats: List<Stat>,
    val copyDefaultUnlockedImg: Boolean,
    val copyDefaultLockedImg: Boolean
)

object StatType {
    const val INT = "1"
    const val FLOAT = "2"
    const val AVGRATE = "3"
    const val BITS = "4"
}
