package app.gamenative.theme.media

import java.util.concurrent.ConcurrentHashMap

/**
 * VideoController manages video playback intents for theme items, enforcing
 * concurrency caps and autoplay policies. It is platform-agnostic; UI/renderers
 * should translate callbacks into actual player operations.
 */
class VideoController(
    private var policy: MediaPolicy.Config = MediaPolicy.computeDefaultConfig(),
) {
    /** Public actions that the renderer should apply to a slot. */
    enum class Action {
        PLAY,              // start/resume playback (muted/loop as per policy)
        PRELOAD_METADATA,  // prepare/preload lightweightly; do not decode frames continuously
        PAUSE,             // pause playback but keep resources (poster should be shown by renderer)
        STOP               // fully stop/release (rare; not used aggressively here)
    }

    /** State reported for an item/slot from the UI layer. */
    data class SlotState(
        val key: String,
        val visible: Boolean,
        val focused: Boolean,
        val selected: Boolean,
        val hasVideo: Boolean = true,
        val posterAvailable: Boolean = true,
        val prefersAutoplay: Boolean = true, // resolved MediaSource.Video.autoplay combined with defaults
    )

    /** Renderer callback target. */
    interface Listener {
        fun onAction(key: String, action: Action)
    }

    private val slots = ConcurrentHashMap<String, SlotInfo>()
    private var listener: Listener? = null

    fun setListener(listener: Listener?) { this.listener = listener }
    fun setPolicy(config: MediaPolicy.Config) { this.policy = config; reschedule() }

    /** Update or register a slot state; triggers re-scheduling. */
    fun updateSlot(state: SlotState) {
        val info = slots.getOrPut(state.key) { SlotInfo(state.key) }
        info.visible = state.visible
        info.focused = state.focused
        info.selected = state.selected
        info.hasVideo = state.hasVideo
        info.posterAvailable = state.posterAvailable
        info.prefersAutoplay = state.prefersAutoplay
        info.touch() // update recency
        reschedule()
    }

    /** Remove a slot; if it was playing, free capacity. */
    fun removeSlot(key: String) {
        val info = slots.remove(key) ?: return
        if (info.playing) dispatch(key, Action.STOP)
        reschedule()
    }

    /** Pause everything (e.g., screen backgrounded). */
    fun pauseAll() {
        slots.values.forEach {
            if (it.playing) {
                it.playing = false
                dispatch(it.key, Action.PAUSE)
            }
        }
    }

    // --- Scheduling ---

    private fun reschedule() {
        // 1) Determine candidates eligible for autoplay according to policy
        val eligible = mutableListOf<SlotInfo>()
        val shouldPreload = mutableListOf<SlotInfo>()
        slots.values.forEach { s ->
            if (!s.hasVideo) {
                // Images only: ensure we don't mark as playing
                if (s.playing) {
                    s.playing = false
                    dispatch(s.key, Action.PAUSE)
                }
                return@forEach
            }
            if (!s.visible) {
                // Off-screen: pause aggressively
                if (s.playing) {
                    s.playing = false
                    dispatch(s.key, Action.PAUSE)
                }
                // no preload when off-screen
                return@forEach
            }
            val canAuto = s.prefersAutoplay && MediaPolicy.canAutoplay(
                visibleInViewport = s.visible,
                isFocused = s.focused,
                isSelected = s.selected,
                requireFocusOrSelection = policy.requireFocusOrSelectionForAutoplay,
            )
            if (canAuto) eligible += s else shouldPreload += s
        }

        // 2) Sort eligible by priority: selected > focused > recency (most recent first)
        eligible.sortWith(compareByDescending<SlotInfo> { it.selected }
            .thenByDescending { it.focused }
            .thenByDescending { it.lastTouched })

        val cap = policy.maxConcurrentVideos.coerceAtLeast(1)
        var remaining = cap

        // First, stop or pause those currently playing but not in top cap
        val stillAllowed = eligible.take(remaining).map { it.key }.toHashSet()
        slots.values.forEach { s ->
            if (s.playing && s.key !in stillAllowed) {
                s.playing = false
                dispatch(s.key, Action.PAUSE)
            }
        }

        // Now, for the top allowed eligible items: ensure PLAY
        eligible.take(remaining).forEach { s ->
            if (!s.playing) {
                s.playing = true
                dispatch(s.key, Action.PLAY)
            }
        }

        // Eligible but over cap: ensure they are paused and preload metadata
        eligible.drop(remaining).forEach { s ->
            if (s.playing) {
                s.playing = false
                dispatch(s.key, Action.PAUSE)
            }
            dispatch(s.key, Action.PRELOAD_METADATA)
        }

        // For visible but not-eligible: preload metadata only
        shouldPreload.forEach { s ->
            if (s.playing) {
                s.playing = false
                dispatch(s.key, Action.PAUSE)
            }
            dispatch(s.key, Action.PRELOAD_METADATA)
        }

        // For invisible ones: nothing else to do (already paused above)
    }

    private fun dispatch(key: String, action: Action) {
        listener?.onAction(key, action)
    }

    private class SlotInfo(val key: String) {
        var visible: Boolean = false
        var focused: Boolean = false
        var selected: Boolean = false
        var hasVideo: Boolean = true
        var posterAvailable: Boolean = true
        var prefersAutoplay: Boolean = true
        var playing: Boolean = false
        var lastTouched: Long = 0L
        fun touch() { lastTouched = ++touchCounter }
    }

    companion object {
        // Monotonic counter for recency ordering (no time source dependency).
        private var touchCounter: Long = 0
    }
}
