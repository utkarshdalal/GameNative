package app.gamenative

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// guards the IO-pool starvation deadlock: PrefManager reads prefs with
// `runBlocking { dataStore.data.first() }`, so if DataStore's own scope also runs on
// Dispatchers.IO, enough concurrent readers park every worker in the pool and none of
// them can ever be served.
class PrefDataStoreDispatcherTest {

    // Dispatchers.IO's default parallelism is max(64, ncores) -- saturate all of it.
    private val ioPoolSize = maxOf(64, Runtime.getRuntime().availableProcessors())

    @Test
    fun prefManager_datastore_runs_on_a_private_named_thread() {
        val field = PrefManager::class.java.getDeclaredField("dataStoreDispatcher")
        field.isAccessible = true
        val dispatcher = field.get(PrefManager) as kotlinx.coroutines.CoroutineDispatcher

        // the deadlock returns the moment this is Dispatchers.IO again
        assertTrue("datastore must not share the IO pool", dispatcher !== Dispatchers.IO)

        // kotlinx's debug agent appends " @coroutine#n" to the thread name under test
        val threadName = runBlocking(dispatcher) { Thread.currentThread().name }
        assertTrue("unexpected datastore thread: $threadName", threadName.startsWith("PrefDataStore"))
    }

    // a blocking read served by a private dispatcher completes even when every IO worker is
    // parked. against a Dispatchers.IO scope this hangs until the timeout.
    @Test(timeout = 60_000)
    fun blocking_read_completes_while_every_IO_worker_is_parked() {
        val file = File.createTempFile("prefs-starvation", ".preferences_pb").apply {
            delete()
            deleteOnExit()
        }
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "TestPrefDataStore") }
        val dispatcher = executor.asCoroutineDispatcher()
        val key = intPreferencesKey("answer")
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + SupervisorJob()),
        ) { file }
        runBlocking { store.edit { it[key] = 42 } }

        val parked = CountDownLatch(ioPoolSize)
        val release = CountDownLatch(1)
        val hogs = CoroutineScope(Dispatchers.IO + SupervisorJob())
        repeat(ioPoolSize) {
            hogs.launch {
                parked.countDown()
                release.await()
            }
        }

        try {
            assertTrue("IO pool never saturated", parked.await(30, TimeUnit.SECONDS))

            val value = runBlocking { store.data.first()[key] }

            assertEquals(42, value)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
