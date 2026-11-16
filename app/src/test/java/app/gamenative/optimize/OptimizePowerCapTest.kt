package app.gamenative.optimize

import android.app.Application
import app.gamenative.powercontrol.PowerCap
import com.winlator.container.Container
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OptimizePowerCapTest {

    private fun lever(steps: String): Lever =
        Lever.parse(JSONObject("""{"lever_type":"power_cap","payload":{"steps":$steps}}"""))

    @Test
    fun parsesKnownDomainsAndDropsUnknownOnes() {
        val parsed = lever("""{"prime":2,"performance":1,"bus":3}""")
        assertEquals(Lever.PowerCap(mapOf("prime" to 2, "performance" to 1)), parsed)
    }

    @Test
    fun badStepValuesRejectTheLever() {
        assertTrue(lever("""{"prime":-1}""") is Lever.Unknown)
        assertTrue(lever("""{"prime":1.5}""") is Lever.Unknown)
        assertTrue(lever("""{"prime":"2"}""") is Lever.Unknown)
        assertTrue(lever("""{"prime":65}""") is Lever.Unknown)
        assertTrue(Lever.parse(JSONObject("""{"lever_type":"power_cap","payload":{}}""")) is Lever.Unknown)
    }

    @Test
    fun stepsCountFromTheTopOfTheTable() {
        val table = listOf(300_000L, 600_000L, 900_000L, 1_200_000L)
        assertEquals(1_200_000L, PowerCap.stepValue(table, 0))
        assertEquals(600_000L, PowerCap.stepValue(table, 2))
        assertEquals(300_000L, PowerCap.stepValue(table, 9))
        assertNull(PowerCap.stepValue(emptyList(), 1))
        assertEquals(0, PowerCap.stepsBelowTop(table, 1_200_000L))
        assertEquals(2, PowerCap.stepsBelowTop(table, 600_000L))
        assertEquals(2, PowerCap.stepsBelowTop(table, 700_000L))
    }

    @Test
    fun storedSettingRoundTrips() {
        val steps = mapOf("prime" to 2, "gpu" to 1)
        assertEquals(steps, PowerCap.decode(PowerCap.encode(steps)))
        assertNull(PowerCap.decode(""))
        assertNull(PowerCap.decode("{}"))
        assertNull(PowerCap.decode("not json"))
    }

    private fun container(): Container {
        val root = Files.createTempDirectory("power-cap").toFile()
        return Container("power-cap").apply { rootDir = root }
    }

    @Test
    fun applyStoresTheSettingAndRevertPutsThePriorBack() = runBlocking {
        val c = container()
        c.putExtra(PowerCap.EXTRA, PowerCap.encode(mapOf("prime" to 1)))
        val count = OptimizeApplier.OpCount()
        val record = OptimizeApplier.applyPowerCap(0, Lever.PowerCap(mapOf("prime" to 2, "gpu" to 1)), c, count, setOf("prime", "performance"))!!
        assertEquals(mapOf("prime" to 2), PowerCap.decode(c.getExtra(PowerCap.EXTRA, "")))
        assertEquals(1, count.applied)
        OptimizeReverter.revertLevers(null, c, listOf(record))
        assertEquals(mapOf("prime" to 1), PowerCap.decode(c.getExtra(PowerCap.EXTRA, "")))
    }

    @Test
    fun revertRemovesASettingThatWasNotThere() = runBlocking {
        val c = container()
        val record = OptimizeApplier.applyPowerCap(0, Lever.PowerCap(mapOf("performance" to 1)), c, OptimizeApplier.OpCount(), setOf("performance"))!!
        OptimizeReverter.revertLevers(null, c, listOf(record))
        assertEquals("", c.getExtra(PowerCap.EXTRA, ""))
    }

    @Test
    fun withoutPowerControlTheLeverIsSkipped() {
        val c = container()
        val count = OptimizeApplier.OpCount()
        assertNull(OptimizeApplier.applyPowerCap(0, Lever.PowerCap(mapOf("prime" to 2)), c, count, emptySet()))
        assertEquals(1, count.skipped)
        assertEquals("", c.getExtra(PowerCap.EXTRA, ""))
    }
}
