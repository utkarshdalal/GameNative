package app.gamenative.steamcontroller

import android.content.Context

/**
 * Placement + size of the menu HUD overlay ([ScMenuOverlayView]). [scale] multiplies the built-in ring/grid
 * size; [cx]/[cy] are the HUD center as a fraction of the screen (0..1, 0.5 = centered). Persisted by
 * [ScOverlayStore]; tweakable globally and per-game via the drag+pinch editor.
 */
data class ScOverlayLayout(
    val scale: Float = ScOverlayStore.DEFAULT_SCALE,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
) {
    fun clamped() = ScOverlayLayout(
        scale = scale.coerceIn(ScOverlayStore.MIN_SCALE, ScOverlayStore.MAX_SCALE),
        cx = cx.coerceIn(0.05f, 0.95f),
        cy = cy.coerceIn(0.05f, 0.95f),
    )
}

/**
 * Persists [ScOverlayLayout] for the menu HUD. Resolution mirrors [ScConfigStore]: a per-game entry (keyed by
 * container/appId) wins, else the shared global default ([DEFAULT_KEY]), else the built-in [ScOverlayLayout].
 * Backed by SharedPreferences (small, no file/JSON parsing).
 */
object ScOverlayStore {
    const val DEFAULT_KEY = "_default"
    const val DEFAULT_SCALE = 0.7f
    const val MIN_SCALE = 0.3f
    const val MAX_SCALE = 2.0f
    private const val PREFS = "sc_overlay"
    // The split keyboard is a second overlay with its own placement; namespaced under "kb_" so it doesn't
    // collide with the menu HUD's entries. Default = full size, centered low (matches the original layout).
    private const val KB = "kb_"
    val KEYBOARD_DEFAULT = ScOverlayLayout(scale = 1f, cx = 0.5f, cy = 0.755f)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Resolved layout for [key]: own entry → global default → built-in. */
    fun forKey(context: Context, key: String?): ScOverlayLayout {
        val p = prefs(context)
        if (key != null && has(p, key)) return read(p, key)
        if (has(p, DEFAULT_KEY)) return read(p, DEFAULT_KEY)
        return ScOverlayLayout()
    }

    /** The stored layout for exactly [key] (no fallback), or null if none — for the editor's initial state. */
    fun rawFor(context: Context, key: String): ScOverlayLayout? {
        val p = prefs(context)
        return if (has(p, key)) read(p, key) else null
    }

    fun save(context: Context, key: String, layout: ScOverlayLayout) {
        val l = layout.clamped()
        prefs(context).edit()
            .putFloat("$key.scale", l.scale)
            .putFloat("$key.cx", l.cx)
            .putFloat("$key.cy", l.cy)
            .apply()
    }

    /** Remove [key]'s entry (e.g. a per-game override reverting to the global default). */
    fun clear(context: Context, key: String) {
        prefs(context).edit()
            .remove("$key.scale").remove("$key.cx").remove("$key.cy")
            .apply()
    }

    // ---- Per-menu placement ("m_<menuId>_" namespace) ----
    // Each menu (identified by its host surface, e.g. "LEFT_PAD") can sit in its own spot. Resolution falls back
    // gracefully so nothing regresses: per-menu-per-game → per-menu-global → the game-wide menu HUD placement
    // ([forKey], keyed bare) → the global menu default → built-in. So an existing whole-HUD placement still
    // applies to every menu until a per-menu override refines one.
    private fun menuKey(menuId: String, key: String) = "m_${menuId}_$key"

    /** Resolved layout for one menu instance on [gameKey]. See the fallback chain above. */
    fun forMenu(context: Context, gameKey: String?, menuId: String): ScOverlayLayout {
        if (menuId.isBlank()) return forKey(context, gameKey)
        val p = prefs(context)
        if (gameKey != null && has(p, menuKey(menuId, gameKey))) return read(p, menuKey(menuId, gameKey))
        if (has(p, menuKey(menuId, DEFAULT_KEY))) return read(p, menuKey(menuId, DEFAULT_KEY))
        return forKey(context, gameKey) // fall back to the whole-HUD placement (per-game → global → built-in)
    }

    fun saveMenu(context: Context, gameKey: String, menuId: String, layout: ScOverlayLayout) =
        save(context, menuKey(menuId, gameKey), layout)

    /** Whether a per-menu override exists for exactly this (game, menu) — for the editor's "Use global" state. */
    fun hasMenu(context: Context, gameKey: String, menuId: String) = has(prefs(context), menuKey(menuId, gameKey))

    fun clearMenu(context: Context, gameKey: String, menuId: String) = clear(context, menuKey(menuId, gameKey))

    // ---- Keyboard overlay (separate placement, "kb_" namespace) ----
    /** Resolved keyboard layout for [key]: own → global default → built-in [KEYBOARD_DEFAULT]. */
    fun forKeyboard(context: Context, key: String?): ScOverlayLayout {
        val p = prefs(context)
        if (key != null && has(p, KB + key)) return read(p, KB + key)
        if (has(p, KB + DEFAULT_KEY)) return read(p, KB + DEFAULT_KEY)
        return KEYBOARD_DEFAULT
    }

    fun saveKeyboard(context: Context, key: String, layout: ScOverlayLayout) = save(context, KB + key, layout)
    fun clearKeyboard(context: Context, key: String) = clear(context, KB + key)

    private fun has(p: android.content.SharedPreferences, key: String) = p.contains("$key.scale")

    private fun read(p: android.content.SharedPreferences, key: String) = ScOverlayLayout(
        scale = p.getFloat("$key.scale", DEFAULT_SCALE),
        cx = p.getFloat("$key.cx", 0.5f),
        cy = p.getFloat("$key.cy", 0.5f),
    ).clamped()
}
