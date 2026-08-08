package app.gamenative.html5.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// bridge queue — drain semantics + concurrent producer/consumer (binder vs main thread).
// @JavascriptInterface is JVM-classpath metadata only, so pure-jvm tests run without Robolectric.
class Html5InputBridgeTest {

    @Test fun drainQueue_empty_returns_empty_array() {
        val bridge = Html5InputBridge()
        assertEquals("[]", bridge.drainQueue())
    }

    @Test fun drainQueue_returns_specs_then_clears() {
        val bridge = Html5InputBridge()
        bridge.enqueue("""{"type":"keydown","keyCode":38}""")
        bridge.enqueue("""{"type":"keyup","keyCode":38}""")
        val first = bridge.drainQueue()
        assertTrue(first.contains("keydown"))
        assertTrue(first.contains("keyup"))
        assertTrue(first.startsWith("[") && first.endsWith("]"))
        // second drain empty — single drain consumes everything
        assertEquals("[]", bridge.drainQueue())
    }

    @Test fun concurrent_enqueue_and_drain_does_not_lose_specs() {
        val bridge = Html5InputBridge()
        val totalSpecs = 1000
        val producerDone = CountDownLatch(1)
        val drained = mutableListOf<String>()
        val executor = Executors.newFixedThreadPool(2)

        // producer
        executor.submit {
            for (i in 0 until totalSpecs) {
                bridge.enqueue("""{"i":$i}""")
            }
            producerDone.countDown()
        }

        // consumer (busy-poll until producer finishes + final drain)
        executor.submit {
            while (true) {
                val raw = bridge.drainQueue()
                if (raw != "[]") {
                    synchronized(drained) { drained.add(raw) }
                }
                if (producerDone.count == 0L && raw == "[]") break
            }
        }

        assertTrue(producerDone.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        // count "i":N occurrences across all drained chunks
        val joined = synchronized(drained) { drained.joinToString(",") }
        var count = 0
        for (i in 0 until totalSpecs) {
            if (joined.contains("\"i\":$i,") || joined.contains("\"i\":$i}") || joined.endsWith("\"i\":$i")) count++
        }
        assertEquals(totalSpecs, count)
    }
}
