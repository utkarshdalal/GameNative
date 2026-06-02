package app.gamenative.html5.host

import android.app.Activity
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi
import timber.log.Timber

// marks the main thread latency-critical so the scheduler boosts it; reduces the chance the WebView
// audio renderer thread starves and trips a sync_reader timeout CHECK. API 30+, no-op below.
class PerformanceHintHelper private constructor(
    private val session: PerformanceHintManager.Session,
) {
    // queued FrameMetrics callbacks can fire AFTER close(), and the native session is freed by then
    // (SIGSEGV). gate every native call on this flag.
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
        // half a 60fps frame; a tighter target would pin a perf core for no gain and burn battery.
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
