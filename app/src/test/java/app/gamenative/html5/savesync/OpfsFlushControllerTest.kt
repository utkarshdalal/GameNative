package app.gamenative.html5.savesync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// PHC- latch-release semantic for OpfsMirrorBridge.markFlushDone wiring.
// pure-jvm — no Android types involved. exercises the unit-testable surface that 
// wires into onDispose. JS markFlushDone -> bridge.markFlushDone -> controller.signalFlushDone
// -> latch.countDown is the actual hot path; this test covers the controller half. 1
// covers the JS->Kotlin call half end-to-end.
class OpfsFlushControllerTest {

    @Test fun awaitFlush_returnsFalse_whenNotSignaled() {
        val c = OpfsFlushController()
        assertFalse("latch should not be released without signal", c.awaitFlush(50L))
    }

    @Test fun signalFlushDone_releasesLatch() {
        val c = OpfsFlushController()
        c.signalFlushDone()
        assertTrue("await must return true after signal", c.awaitFlush(0L))
    }

    @Test fun awaiter_unblocksOnSignalFromOtherThread() {
        val c = OpfsFlushController()
        val started = CountDownLatch(1)
        val resultHolder = arrayOf<Boolean?>(null)
        val awaiter = thread(start = true) {
            started.countDown()
            resultHolder[0] = c.awaitFlush(2_000L)
        }
        assertTrue("awaiter thread must start", started.await(500L, TimeUnit.MILLISECONDS))
        c.signalFlushDone()
        awaiter.join(500L)
        assertTrue("awaiter must have completed within 500ms after signal", !awaiter.isAlive)
        assertTrue("awaitFlush must have returned true", resultHolder[0] == true)
    }

    @Test fun signalFlushDone_isIdempotent() {
        val c = OpfsFlushController()
        c.signalFlushDone()
        c.signalFlushDone()
        c.signalFlushDone()
        assertTrue(c.awaitFlush(0L))
    }

    @Test fun awaitFlush_isIdempotentAfterSignal() {
        val c = OpfsFlushController()
        c.signalFlushDone()
        assertTrue(c.awaitFlush(0L))
        assertTrue(c.awaitFlush(0L))
        assertTrue(c.awaitFlush(0L))
    }
}
