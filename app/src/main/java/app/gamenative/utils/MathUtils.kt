package app.gamenative.utils

object MathUtils {
    fun normalizedProgress(
        value: Float,
        min: Float,
        max: Float,
    ): Float {
        if (max <= min) {
            return 0f
        }

        return ((value - min) / (max - min)).coerceIn(0f, 1f)
    }
}
