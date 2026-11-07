package app.gamenative.html5.savesync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// one per WebView launch: lets onDispose block until the JS-side OPFS flush reports done.
// countDown/await are idempotent, so a double-firing shim is harmless.
class OpfsFlushController {
    private val latch = CountDownLatch(1)

    fun signalFlushDone() {
        latch.countDown()
    }

    fun awaitFlush(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
