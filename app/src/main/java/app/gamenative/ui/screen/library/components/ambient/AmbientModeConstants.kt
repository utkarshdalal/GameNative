package app.gamenative.ui.screen.library.components.ambient

internal object AmbientModeConstants {
    const val IDLE_TIMEOUT_MS = 10_000L
    const val ENTER_DURATION_MS = 800
    const val EXIT_DURATION_MS = 400
    const val SHIMMER_PERIOD_MS = 2_000
    const val DRIFT_PERIOD_MS = 30_000
    const val DRIFT_AMPLITUDE_PX = 16f
    const val BAR_HEIGHT_DP = 4f
    const val BAR_BASE_ALPHA = 0.25f
    const val BAR_TRACK_ALPHA = 0.15f
    const val SHIMMER_WIDTH_FRACTION = 0.15f
    const val TEXT_MAX_ALPHA = 0.5f
    const val TEXT_BOTTOM_OFFSET_DP = 60f

    // DVD bouncing screensaver easter egg
    const val DVD_SPEED_DP_PER_SEC = 90f
    const val DVD_ICON_SIZE_DP = 48f
    const val DVD_COLOR_CROSSFADE_MS = 300
}
