package app.gamenative.enums

import java.util.EnumSet
import timber.log.Timber

enum class OS(val code: Int) {
    none(0),
    windows(0x01),
    macos(0x02),
    linux(0x04),
    android(0x08),
    ;

    companion object {
        fun from(keyValue: String?): EnumSet<OS> {
            val osses = keyValue?.takeUnless { it.isEmpty() }
                ?.split(',')
                ?.map {
                    try {
                        OS.valueOf(it.trim())
                    } catch (_: Exception) {
                        Timber.w("Could not identify OS $it")
                        none
                    }
                }
                ?.toCollection(EnumSet.noneOf(OS::class.java))

            return osses ?: EnumSet.of(none)
        }

        fun from(code: Int): EnumSet<OS> {
            val result = EnumSet.noneOf(OS::class.java)
            OS.entries.forEach { appType ->
                if (code and appType.code == appType.code) {
                    result.add(appType)
                }
            }
            return result
        }

        fun code(value: EnumSet<OS>): Int {
            return value.map { it.code }.reduceOrNull { first, second -> first or second } ?: none.code
        }
    }
}
