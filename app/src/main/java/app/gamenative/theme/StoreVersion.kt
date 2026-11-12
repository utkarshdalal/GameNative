package app.gamenative.theme

import java.math.BigInteger

object StoreVersion {

    /** True when store version [latest] is newer than [installed]; versions without digits only need to differ. */
    fun isNewer(latest: String, installed: String?): Boolean {
        if (installed == null) return true
        val a = Regex("\\d+").findAll(latest).map { it.value.toBigInteger() }.toList()
        val b = Regex("\\d+").findAll(installed).map { it.value.toBigInteger() }.toList()
        if (a.isEmpty() || b.isEmpty()) return latest.trim() != installed.trim()
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { BigInteger.ZERO }
            val y = b.getOrElse(i) { BigInteger.ZERO }
            if (x != y) return x > y
        }
        return false
    }
}
