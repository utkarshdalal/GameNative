package app.gamenative.utils

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherOrientationTest {
    private val portrait = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    private val landscape = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    private val reversePortrait = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    private val reverseLandscape = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    private val auto = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @Test
    fun `portrait natural ring turns 90 degrees away from auto first`() {
        // Auto shows portrait on a phone, so the first press must leave portrait.
        assertEquals(
            listOf(landscape, reversePortrait, reverseLandscape, portrait, auto),
            LauncherOrientation.ring(naturalPortrait = true).toList(),
        )
    }

    @Test
    fun `landscape natural ring turns 90 degrees away from auto first`() {
        // Auto shows landscape on a handheld, so the first press must leave landscape.
        assertEquals(
            listOf(portrait, reverseLandscape, reversePortrait, landscape, auto),
            LauncherOrientation.ring(naturalPortrait = false).toList(),
        )
    }

    @Test
    fun `first press from auto always changes the screen`() {
        listOf(true, false).forEach { naturalPortrait ->
            val first = LauncherOrientation.nextState(auto, naturalPortrait)
            assertNotEquals("first press was a no-op", auto, first)
            assertTrue(LauncherOrientation.isConcrete(first))
        }
    }

    @Test
    fun `five presses from auto visit every state and return to auto`() {
        listOf(true, false).forEach { naturalPortrait ->
            var stored = auto
            val seen = mutableListOf<Int>()

            repeat(5) {
                stored = LauncherOrientation.nextState(stored, naturalPortrait)
                seen += stored
            }

            assertEquals("naturalPortrait=$naturalPortrait", 5, seen.size)
            assertEquals(auto, seen.last())
            assertEquals(5, (seen + auto).distinct().size)
            assertTrue(seen.take(4).all { LauncherOrientation.isConcrete(it) })
        }
    }

    @Test
    fun `the natural orientation is reachable as a locked state`() {
        // Regression: on the Retroid this locked "plain landscape" state was unreachable.
        val handheldRing = LauncherOrientation.ring(naturalPortrait = false)
        assertEquals(landscape, handheldRing[3])

        val phoneRing = LauncherOrientation.ring(naturalPortrait = true)
        assertEquals(portrait, phoneRing[3])
    }

    @Test
    fun `every press changes the state from any starting point`() {
        listOf(true, false).forEach { naturalPortrait ->
            LauncherOrientation.ring(naturalPortrait).forEach { start ->
                val next = LauncherOrientation.nextState(start, naturalPortrait)
                assertNotEquals("press from $start did nothing", start, next)
            }
        }
    }

    @Test
    fun `unknown stored value restarts at the first ring entry`() {
        assertEquals(landscape, LauncherOrientation.nextState(99, naturalPortrait = true))
        assertEquals(portrait, LauncherOrientation.nextState(99, naturalPortrait = false))
    }

    @Test
    fun `each state maps to a distinct request except auto which releases`() {
        assertEquals(portrait, LauncherOrientation.launcherTarget(portrait))
        assertEquals(landscape, LauncherOrientation.launcherTarget(landscape))
        assertEquals(reversePortrait, LauncherOrientation.launcherTarget(reversePortrait))
        assertEquals(reverseLandscape, LauncherOrientation.launcherTarget(reverseLandscape))
        assertEquals(auto, LauncherOrientation.launcherTarget(auto))
    }

    @Test
    fun `only the four locked orientations count as concrete`() {
        assertTrue(LauncherOrientation.isConcrete(portrait))
        assertTrue(LauncherOrientation.isConcrete(landscape))
        assertTrue(LauncherOrientation.isConcrete(reversePortrait))
        assertTrue(LauncherOrientation.isConcrete(reverseLandscape))
        assertEquals(false, LauncherOrientation.isConcrete(auto))
        assertEquals(false, LauncherOrientation.isConcrete(99))
    }
}
