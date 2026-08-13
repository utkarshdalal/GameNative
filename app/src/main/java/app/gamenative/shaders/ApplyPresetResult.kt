package app.gamenative.shaders

/**
 * What applying a preset from the browser actually did (used by the double-click gesture
 * to decide whether it may arm — spec 2026-08-12, UX review fix 3).
 */
enum class ApplyPresetResult {
    /** The preset was LOADED into the renderer (it is now the active shader). */
    Applied,

    /** The preset was already active: it was cleared ONLY (toggle-off, spec 2026-08-11). */
    Cleared,

    /** The preset's files are not present (not downloaded / broken) — nothing happened. */
    Missing,
}
