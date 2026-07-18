package app.gamenative.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.gamenative.BuildConfig
import timber.log.Timber

/**
 * Debug-only broadcast receiver for the OEM tuning harness. Power commands MUST arrive as
 * broadcasts, not activity-start intents: an `am start` pokes the Activity/task lifecycle
 * (refocus, back-action re-registration, occasional teardown) and disrupts a running game,
 * whereas a broadcast never touches the task stack. Gated to debuggable builds.
 *
 *   adb shell am broadcast -a app.gamenative.SET_POWER --es power_state '<json>'
 *   adb shell am broadcast -a app.gamenative.PERF_LOOP --ez enable true --es preset battery
 *   adb shell am broadcast -a app.gamenative.DUMP_THREADS
 *   adb shell am broadcast -a app.gamenative.SET_PLACEMENT --es rules 'dxvk-cs:4-7:-8;hot2:4-7:-'
 *   adb shell am broadcast -a app.gamenative.SET_PLACEMENT --ez reset true
 */
class PerfControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when {
            PowerIntentManager.isSetPowerIntent(intent) ->
                Timber.tag("PerfControl").i(PowerIntentManager.handle(intent))
            PerfLoopIntentManager.isPerfLoopIntent(intent) ->
                Timber.tag("PerfControl").i(PerfLoopIntentManager.handle(context, intent))
            ThreadIntentManager.isDumpThreadsIntent(intent) ->
                Timber.tag("PerfControl").i(ThreadIntentManager.handleDumpThreads(intent))
            ThreadIntentManager.isSetPlacementIntent(intent) ->
                Timber.tag("PerfControl").i(ThreadIntentManager.handleSetPlacement(intent))
        }
    }
}
