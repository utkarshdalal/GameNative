package app.gamenative.utils

import android.content.Context
import android.content.Intent
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.loop.PerfLoop
import app.gamenative.powercontrol.loop.PerfPreset
import timber.log.Timber

/**
 * Debug-only hook for the OEM tuning harness: toggles the closed-loop performance controller
 * (PerfLoop) so a host-side session can drive it on/off and select a preset.
 *
 * NOT a product feature — gated to debuggable builds by the caller (MainActivity). The loop is
 * OFF by default; only this intent starts it.
 *
 * Intent: action app.gamenative.PERF_LOOP, extras:
 *   "enable"  = boolean (true starts the loop, false stops it and restores full clock range)
 *   "preset"  = string, one of battery/balanced/performance (default balanced)
 */
object PerfLoopIntentManager {

    const val ACTION_PERF_LOOP = "app.gamenative.PERF_LOOP"
    private const val EXTRA_ENABLE = "enable"
    private const val EXTRA_PRESET = "preset"

    fun isPerfLoopIntent(intent: Intent): Boolean = intent.action == ACTION_PERF_LOOP

    fun handle(context: Context, intent: Intent): String {
        val enable = intent.getBooleanExtra(EXTRA_ENABLE, true)
        return try {
            if (enable) {
                if (!PowerManager.isPServerAvailable()) {
                    return "PERF_LOOP: PServer not available on this device"
                }
                val preset = PerfPreset.fromString(intent.getStringExtra(EXTRA_PRESET))
                PerfLoop.start(context.applicationContext, preset)
                val msg = "PERF_LOOP started preset=$preset"
                Timber.tag("PerfLoopIntentManager").i(msg)
                msg
            } else {
                PerfLoop.stop()
                val msg = "PERF_LOOP stopped"
                Timber.tag("PerfLoopIntentManager").i(msg)
                msg
            }
        } catch (e: Exception) {
            val msg = "PERF_LOOP failed: ${e.message}"
            Timber.tag("PerfLoopIntentManager").e(e, msg)
            msg
        }
    }
}
