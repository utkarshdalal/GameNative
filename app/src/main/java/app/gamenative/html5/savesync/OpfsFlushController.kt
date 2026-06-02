package app.gamenative.html5.savesync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// PHC-per-WebView flush controller. holds the latch consumed by the
// OpfsMirrorBridge.markFlushDone callback so onDispose can synchronously await
// the JS-side flush completion. lifecycle matches WebView (one per launch).

// CountDownLatch.countDown is idempotent after the count reaches 0 -- multiple JS-side
// markFlushDone calls don't throw. awaitFlush is also idempotent -- repeated post-signal
// calls all return true. matches the failure-soft expectation: even if the JS shim
// double-fires, kotlin still observes a single release event.
class OpfsFlushController {
    private val latch = CountDownLatch(1)

    fun signalFlushDone() {
        latch.countDown()
    }

    fun awaitFlush(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
