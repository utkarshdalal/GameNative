package app.gamenative.steamcontroller

import android.content.Context

/**
 * Small global tuning store for Steam Controller feel values that a user adjusts by hand (hardware/grip
 * dependent). Backed by SharedPreferences. Holds the two touchpad-feel knobs:
 *  - **Deadzone** — the resting-finger freeze radius (raw pad units) for relative-mouse pads. Within this radius
 *    of the anchor the cursor doesn't move at all (kills resting jitter); higher = stiller at rest.
 *  - **Smoothing** — a low-pass (0–100%) applied to motion that's left after the deadzone, and to the on-screen
 *    keyboard cursor. Higher = smoother but laggier; 0 = off.
 * Read once when the live driver builds its interpreter (applies on next game launch).
 */
object ScTuningStore {
    private const val PREFS = "sc_tuning"
    private const val KEY_DEADZONE = "touchpad_deadzone"
    private const val KEY_SMOOTHING = "touchpad_smoothing"

    /** Deadzone default matches the built-in [ScProfile.PadMode.Mouse] jitterFloor. */
    const val DEFAULT_DEADZONE = 24
    const val MIN_DEADZONE = 0
    const val MAX_DEADZONE = 100

    // Measured BLE rest-jitter (right pad, finger still) is zero-mean noise spanning ~120–250 raw units with
    // per-report deltas spiking to ~170 — far past the deadzone range, so a low-pass is the effective tool. 70 was
    // dialed in on-device (Z Fold 7) as the lowest smoothing that kills rest-jitter without feeling floaty.
    const val DEFAULT_SMOOTHING = 70
    const val MIN_SMOOTHING = 0
    const val MAX_SMOOTHING = 100

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deadzone(context: Context): Int =
        prefs(context).getInt(KEY_DEADZONE, DEFAULT_DEADZONE).coerceIn(MIN_DEADZONE, MAX_DEADZONE)

    fun setDeadzone(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_DEADZONE, value.coerceIn(MIN_DEADZONE, MAX_DEADZONE)).apply()
    }

    fun smoothing(context: Context): Int =
        prefs(context).getInt(KEY_SMOOTHING, DEFAULT_SMOOTHING).coerceIn(MIN_SMOOTHING, MAX_SMOOTHING)

    fun setSmoothing(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SMOOTHING, value.coerceIn(MIN_SMOOTHING, MAX_SMOOTHING)).apply()
    }

    /** Map a 0–100 smoothing percent to an EMA alpha (weight of the new sample): 0% → 1.0 (no smoothing),
     *  100% → 0.15 (heavy). Shared by the pad-mouse and the keyboard cursor so one knob governs both. */
    fun emaAlpha(smoothing: Int): Float = 1f - (smoothing.coerceIn(0, 100) / 100f) * 0.85f
}
