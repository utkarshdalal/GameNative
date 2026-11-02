package app.gamenative.powercontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GameCoreSetTest {

    // Retroid Pocket 6 (Snapdragon 8 Gen 2): c0-2 little, c3-6 big, c7 prime.
    private val rp6 = listOf(listOf(0, 1, 2), listOf(3, 4, 5, 6), listOf(7))
    private val rp6Capacity = mapOf(0 to 325, 1 to 325, 2 to 325, 3 to 825, 4 to 825, 5 to 825, 6 to 825, 7 to 1024)
    private val online = (0..7).toSet()

    @Test
    fun `gamescope tri-cluster game runs on every cluster above the little one`() {
        assertEquals(listOf(3, 4, 5, 6, 7), PowerManager.clusterGameCores(rp6, gamescope = true))
    }

    @Test
    fun `x11 keeps all cores but the two slowest`() {
        assertEquals(listOf(2, 3, 4, 5, 6, 7), PowerManager.clusterGameCores(rp6, gamescope = false))
        assertEquals(
            listOf(2, 3, 4, 5, 6, 7),
            PowerManager.clusterGameCores(listOf(emptyList(), listOf(0, 1, 2, 3), listOf(4, 5, 6, 7)), gamescope = false),
        )
    }

    @Test
    fun `gamescope four-four dual cluster runs on the big cluster`() {
        val cores = PowerManager.clusterGameCores(listOf(emptyList(), listOf(0, 1, 2, 3), listOf(4, 5, 6, 7)), gamescope = true)
        assertEquals(listOf(4, 5, 6, 7), cores)
    }

    @Test
    fun `gamescope with a small big cluster keeps all but the two slowest`() {
        val cores = PowerManager.clusterGameCores(listOf(emptyList(), listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)), gamescope = true)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), cores)
    }

    @Test
    fun `gamescope with a single cluster keeps all but the two slowest`() {
        val cores = PowerManager.clusterGameCores(listOf(emptyList(), listOf(0, 1, 2, 3), emptyList()), gamescope = true)
        assertEquals(listOf(2, 3), cores)
    }

    @Test
    fun `compositor chain leaves SurfaceFlinger's little cluster`() {
        val placement = PowerManager.compositorCoreSet(online, setOf(0, 1, 2), rp6Capacity)
        assertEquals(listOf(3, 4, 5, 6, 7), placement.cores)
        assertNull(placement.fallbackReason)
    }

    @Test
    fun `a 0-6 SurfaceFlinger mask leaves the chain on all cores`() {
        val placement = PowerManager.compositorCoreSet(online, (0..6).toSet(), rp6Capacity)
        assertEquals((0..7).toList(), placement.cores)
        assertNotNull(placement.fallbackReason)
    }

    @Test
    fun `SurfaceFlinger on the big cores leaves the chain on all cores`() {
        val placement = PowerManager.compositorCoreSet(online, (3..7).toSet(), rp6Capacity)
        assertEquals((0..7).toList(), placement.cores)
        assertNotNull(placement.fallbackReason)
    }

    @Test
    fun `unreadable SurfaceFlinger leaves out the lowest-capacity cluster`() {
        val placement = PowerManager.compositorCoreSet(online, emptySet(), rp6Capacity)
        assertEquals(listOf(3, 4, 5, 6, 7), placement.cores)
    }

    @Test
    fun `tuner aims for the panel rate when a gamescope cap is above it`() {
        assertEquals(60, PowerManager.tunerTargetFps(120, gamescope = true, panelHz = 60))
        assertEquals(60, PowerManager.tunerTargetFps(60, gamescope = true, panelHz = 120))
        assertEquals(45, PowerManager.tunerTargetFps(45, gamescope = true, panelHz = 60))
        assertEquals(120, PowerManager.tunerTargetFps(120, gamescope = false, panelHz = 60))
        assertEquals(120, PowerManager.tunerTargetFps(120, gamescope = true, panelHz = 0))
        assertEquals(0, PowerManager.tunerTargetFps(0, gamescope = true, panelHz = 60))
    }
}
