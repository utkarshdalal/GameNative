package app.gamenative.powercontrol.metrics

/**
 * GPU time per frame, in milliseconds, from a source that measures it directly (for
 * example present-path timestamps). Null while no such source runs. Readers (HUD,
 * bottleneck classifier) prefer it over the busy-percent reading when it is set.
 */
object GpuFrameTime {
    @Volatile
    var lastFrameMs: Float? = null

    /** Last value when it is a usable duration; null otherwise. */
    fun current(): Float? = lastFrameMs?.takeIf { it.isFinite() && it > 0f }

    fun reset() {
        lastFrameMs = null
    }
}
