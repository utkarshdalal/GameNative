package app.gamenative.theme

import kotlin.math.pow

/**
 * The one rule for text (and handles) on a theme fill: keep the theme's own color when it is readable on the fill,
 * else the first of the theme's other colors that is, else black or white, whichever reads better.
 * Colors are ARGB ints; alpha is ignored, so composite a translucent fill over what is under it first.
 */
object ThemeContrast {
    /** Between WCAG's large-text (3) and body-text (4.5) levels, so a theme's own readable pairings are kept. */
    const val TEXT = 3.5

    /** A switch handle only has to stand out from its track. */
    const val HANDLE = 1.5

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** WCAG relative luminance. */
    fun luminance(argb: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin((argb shr 16) and 0xff) + 0.7152 * lin((argb shr 8) and 0xff) + 0.0722 * lin(argb and 0xff)
    }

    /** WCAG contrast ratio, 1..21. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Black or white, whichever contrasts more with [fill]. */
    fun blackOrWhite(fill: Int): Int = if (ratio(WHITE, fill) >= ratio(BLACK, fill)) WHITE else BLACK

    /**
     * The first of [candidates] (the theme's color first, then its alternatives) with at least [min] contrast on
     * [fill], with its own alpha; else [blackOrWhite]. Null candidates are skipped.
     */
    fun onFill(fill: Int, min: Double, candidates: List<Int?>): Int =
        candidates.firstOrNull { it != null && ratio(it, fill) >= min } ?: blackOrWhite(fill)

    /** [top] drawn over the opaque [under], as one opaque color. */
    fun over(top: Int, under: Int): Int {
        val a = ((top ushr 24) and 0xff) / 255.0
        if (a >= 1.0) return top
        fun ch(shift: Int) = (((top shr shift) and 0xff) * a + ((under shr shift) and 0xff) * (1 - a)).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
