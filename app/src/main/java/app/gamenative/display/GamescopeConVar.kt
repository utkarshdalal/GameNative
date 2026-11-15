package app.gamenative.display

/**
 * A gamescope concommand a client may run — the typed form of the injector's
 * `ALLOWED_COMMANDS` allowlist.
 */
enum class GamescopeConVar(val id: String) {
    SCALING_FILTER("scaling_filter"),
    SCALING_SHARPNESS("scaling_sharpness"),
    DEBUG_SET_FPS_LIMIT("debug_set_fps_limit"),
    SET_LOOK("set_look"),
    SCREENSHOT("screenshot"),
    DEBUG_FORCE_REPAINT("debug_force_repaint"),

    /** Dirty the backend and re-poll it: gamescope re-reads the connector and re-selects its mode. */
    BACKEND_SET_DIRTY("backend_set_dirty"),
}
