package app.gamenative.powercontrol.profiles

import kotlinx.serialization.Serializable

/**
 * CPU governor types available on Android devices
 */
@Serializable
enum class CpuGovernor(val governorName: String) {
    POWERSAVE("powersave"),
    CONSERVATIVE("conservative"),
    SCHEDUTIL("schedutil"),
    INTERACTIVE("interactive"),
    PERFORMANCE("performance"),
    ONDEMAND("ondemand"),
    WALT("walt");

    companion object {
        fun fromString(name: String): CpuGovernor? {
            return entries.find { it.governorName.equals(name, ignoreCase = true) }
        }
    }
}
