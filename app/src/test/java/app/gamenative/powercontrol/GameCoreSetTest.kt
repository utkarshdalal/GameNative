package app.gamenative.powercontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class GameCoreSetTest {

    @Test
    fun `tri-cluster game runs on every cluster above the little one`() {
        // Retroid Pocket 6 (Snapdragon 8 Gen 2): c0-2 little, c3-6 big, c7 prime.
        val cores = PowerManager.clusterGameCores(listOf(listOf(0, 1, 2), listOf(3, 4, 5, 6), listOf(7)))
        assertEquals(listOf(3, 4, 5, 6, 7), cores)
    }

    @Test
    fun `four-four dual cluster runs on the big cluster`() {
        val cores = PowerManager.clusterGameCores(listOf(emptyList(), listOf(0, 1, 2, 3), listOf(4, 5, 6, 7)))
        assertEquals(listOf(4, 5, 6, 7), cores)
    }

    @Test
    fun `a small big cluster keeps all but the two slowest`() {
        val cores = PowerManager.clusterGameCores(listOf(emptyList(), listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)))
        assertEquals(listOf(2, 3, 4, 5, 6, 7), cores)
    }

    @Test
    fun `a single cluster keeps all but the two slowest`() {
        val cores = PowerManager.clusterGameCores(listOf(emptyList(), listOf(0, 1, 2, 3), emptyList()))
        assertEquals(listOf(2, 3), cores)
    }
}
