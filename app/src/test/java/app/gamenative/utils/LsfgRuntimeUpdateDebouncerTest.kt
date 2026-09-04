package app.gamenative.utils

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgRuntimeUpdateDebouncerTest {
    @Test
    fun submit_keepsOnlyLatestSettingsUpdateDuringAdjustmentBurst() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val observed = CopyOnWriteArrayList<Int>()
        val completed = CountDownLatch(1)
        val debouncer = LsfgRuntimeUpdateDebouncer(scheduler, 40L)

        try {
            debouncer.submit { observed += 1 }
            Thread.sleep(10L)
            debouncer.submit {
                observed += 2
                completed.countDown()
            }

            assertTrue("Latest settings update did not execute", completed.await(500L, TimeUnit.MILLISECONDS))
            Thread.sleep(60L)
            assertEquals(listOf(2), observed.toList())
        } finally {
            debouncer.cancel()
            scheduler.shutdownNow()
        }
    }
}
