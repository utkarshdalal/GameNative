package app.gamenative.html5.host

import android.app.Activity
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * Tells Android's CPU scheduler the WebViewScreen main thread runs latency-critical work
 * (JS bridge dispatch, input forwarding, frame production into the WebView). The scheduler
 * boosts CPU frequency / pins to a perf core when the thread runs, reducing the chance the
 * audio renderer thread (in the WebView sandbox process) starves and trips a sync_reader
 * timeout → CHECK SIGTRAP.
 *
 * Available on API 30+ (Android 11). No-op on older devices.
 *
 * Lifecycle: create() once when the WebView is alive, [reportActualWorkDuration] each frame
 * with the measured frametime ns, [close] on teardown.
 */
class PerformanceHintHelper private constructor(
    private val session: PerformanceHintManager.Session,
) {
    // queued FrameMetrics callbacks can fire AFTER close() -- removing the
    // listener doesn't drain queued Handler messages. once close() returns, the native
    // C++ object backing `session` is freed, so any subsequent native call deref's a
    // dangling pointer (SIGSEGV at +0x28). gate every native call on this flag.
    @Volatile
    private var closed = false

    fun reportActualWorkDuration(actualNs: Long) {
        if (closed || actualNs <= 0L) return
        runCatching { session.reportActualWorkDuration(actualNs) }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { session.close() }
    }

    companion object {
        // 8ms target = half of a 60fps frame budget. covers the JS-bridge work the main
        // thread does between vsyncs without overstating the requirement (which would
        // otherwise pin a perf core unnecessarily and burn battery).
        private const val DEFAULT_TARGET_WORK_NANOS = 8_000_000L

        fun create(activity: Activity): PerformanceHintHelper? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return tryCreate(activity)
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun tryCreate(activity: Activity): PerformanceHintHelper? {
            val manager = activity.getSystemService(PerformanceHintManager::class.java) ?: return null
            val tids = intArrayOf(Process.myTid())
            val session = runCatching {
                manager.createHintSession(tids, DEFAULT_TARGET_WORK_NANOS)
            }.getOrNull() ?: return null
            Timber.tag("PerfHint").i("hint session created: tid=%d targetNs=%d", tids[0], DEFAULT_TARGET_WORK_NANOS)
            return PerformanceHintHelper(session)
        }
    }
}
