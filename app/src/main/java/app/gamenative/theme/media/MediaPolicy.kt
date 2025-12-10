package app.gamenative.theme.media

import app.gamenative.theme.model.VideoPreloadPolicy

/**
 * Centralized media playback policy used by the theme runtime.
 *
 * This is platform-agnostic and testable. Rendering code can consume the
 * computed intent (play/pause/preload/poster) without knowing about caps logic.
 */
object MediaPolicy {

    /** Describes device capabilities used to tune concurrency caps. */
    data class DeviceProfile(
        val isTvUi: Boolean = true,
        val totalRamGb: Int? = null,
        val cpuCores: Int? = null,
        val abi64: Boolean = true,
        val vendor: String? = null,
        val model: String? = null,
    )

    /** Policy knobs with sensible defaults. */
    data class Config(
        /** Maximum concurrent video decodes allowed by policy. */
        val maxConcurrentVideos: Int,
        /** Whether autoplay is allowed only when item is focused or selected. */
        val requireFocusOrSelectionForAutoplay: Boolean = true,
        /** Default preload policy for videos that are eligible but not playing. */
        val defaultPreload: VideoPreloadPolicy = VideoPreloadPolicy.METADATA,
        /** Always mute autoplayed videos by default. */
        val defaultMuted: Boolean = true,
        /** Loop videos by default. */
        val defaultLoop: Boolean = true,
    )

    /** Heuristic: returns 2 for strong devices, else 1, per step requirements. */
    fun computeDefaultConfig(device: DeviceProfile = DeviceProfile()): Config {
        val strong = isStrongDevice(device)
        val cap = if (strong) 2 else 1
        return Config(maxConcurrentVideos = cap)
    }

    /**
     * Simple heuristic for a "strong" device: 64-bit + >= 6 cores or >= 6 GB RAM.
     * Tuned conservatively; returns false if information is missing.
     */
    fun isStrongDevice(d: DeviceProfile): Boolean {
        val coresStrong = (d.cpuCores ?: 0) >= 6
        val ramStrong = (d.totalRamGb ?: 0) >= 6
        return d.abi64 && (coresStrong || ramStrong)
    }

    /** Returns true if a video may autoplay under current UI state. */
    fun canAutoplay(
        visibleInViewport: Boolean,
        isFocused: Boolean,
        isSelected: Boolean,
        requireFocusOrSelection: Boolean = true,
    ): Boolean {
        if (!visibleInViewport) return false
        if (!requireFocusOrSelection) return true
        return isFocused || isSelected
    }
}
