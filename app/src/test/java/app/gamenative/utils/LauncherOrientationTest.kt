package app.gamenative.utils

import android.content.pm.ActivityInfo
import app.gamenative.ui.enums.Orientation
import java.util.EnumSet
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

    private fun next(stored: Int, naturalPortrait: Boolean, portraitOnScreen: Boolean) =
        LauncherOrientation.nextState(stored, naturalPortrait, portraitOnScreen)

    @Test
    fun `portrait natural ring starts one quarter turn after the natural orientation`() {
        assertEquals(
            listOf(landscape, reversePortrait, reverseLandscape, portrait, auto),
            LauncherOrientation.ring(naturalPortrait = true).toList(),
        )
    }

    @Test
    fun `landscape natural ring starts one quarter turn after the natural orientation`() {
        assertEquals(
            listOf(portrait, reverseLandscape, reversePortrait, landscape, auto),
            LauncherOrientation.ring(naturalPortrait = false).toList(),
        )
    }

    @Test
    fun `first press from auto never keeps the on-screen shape`() {
        // Regression for the P2 review note: with system rotation locked to a non-natural
        // orientation, entering at ring[0] could request the orientation already displayed.
        listOf(true, false).forEach { naturalPortrait ->
            listOf(true, false).forEach { portraitOnScreen ->
                val first = next(auto, naturalPortrait, portraitOnScreen)
                assertTrue(LauncherOrientation.isConcrete(first))
                assertNotEquals(
                    "first press kept the on-screen shape (naturalPortrait=$naturalPortrait, " +
                        "portraitOnScreen=$portraitOnScreen)",
                    portraitOnScreen,
                    LauncherOrientation.isPortraitOrientation(first),
                )
            }
        }
    }

    @Test
    fun `portrait natural device showing landscape enters at a portrait state`() {
        assertEquals(reversePortrait, next(auto, naturalPortrait = true, portraitOnScreen = false))
    }

    @Test
    fun `portrait natural device showing portrait enters at landscape`() {
        assertEquals(landscape, next(auto, naturalPortrait = true, portraitOnScreen = true))
    }

    @Test
    fun `landscape natural device showing landscape enters at portrait`() {
        assertEquals(portrait, next(auto, naturalPortrait = false, portraitOnScreen = false))
    }

    @Test
    fun `landscape natural device showing portrait enters at reverse landscape`() {
        assertEquals(reverseLandscape, next(auto, naturalPortrait = false, portraitOnScreen = true))
    }

    @Test
    fun `a locked launcher walks the ring and releases to auto`() {
        // portrait-natural ring: landscape, reverse portrait, reverse landscape, portrait, auto
        assertEquals(reversePortrait, next(landscape, naturalPortrait = true, portraitOnScreen = false))
        assertEquals(reverseLandscape, next(reversePortrait, naturalPortrait = true, portraitOnScreen = true))
        assertEquals(portrait, next(reverseLandscape, naturalPortrait = true, portraitOnScreen = false))
        assertEquals(auto, next(portrait, naturalPortrait = true, portraitOnScreen = true))
    }

    @Test
    fun `locked states ignore what is on screen`() {
        // Once locked, the screen follows the request, so the walk must not depend on it.
        assertEquals(
            next(landscape, naturalPortrait = true, portraitOnScreen = false),
            next(landscape, naturalPortrait = true, portraitOnScreen = true),
        )
    }

    @Test
    fun `every press changes the state from any locked starting point`() {
        listOf(true, false).forEach { naturalPortrait ->
            LauncherOrientation.ring(naturalPortrait)
                .filter { LauncherOrientation.isConcrete(it) }
                .forEach { start ->
                    val nextState = next(start, naturalPortrait, portraitOnScreen = false)
                    assertNotEquals("press from $start did nothing", start, nextState)
                }
        }
    }

    @Test
    fun `four presses from the first locked state reach auto`() {
        var stored = landscape // portrait-natural, entering from auto while landscape on screen
        var presses = 0

        repeat(4) {
            stored = next(stored, naturalPortrait = true, portraitOnScreen = false)
            presses++
        }

        assertEquals(auto, stored)
        assertEquals(4, presses)
    }

    @Test
    fun `unknown stored value behaves like auto`() {
        assertEquals(
            next(auto, naturalPortrait = true, portraitOnScreen = false),
            next(99, naturalPortrait = true, portraitOnScreen = false),
        )
        assertEquals(
            next(auto, naturalPortrait = false, portraitOnScreen = true),
            next(99, naturalPortrait = false, portraitOnScreen = true),
        )
    }

    @Test
    fun `only the launcher auto set is treated as launcher owned`() {
        // Regression for the P1 review note: a game's set must never take the launcher path,
        // including the concrete request MainViewModel sends before the container exists.
        assertTrue(LauncherOrientation.isAuto(EnumSet.of(Orientation.UNSPECIFIED)))
        assertTrue(LauncherOrientation.isAuto(EnumSet.noneOf(Orientation::class.java)))
        assertEquals(
            false,
            LauncherOrientation.isAuto(EnumSet.of(Orientation.PORTRAIT)),
        )
        assertEquals(
            false,
            LauncherOrientation.isAuto(EnumSet.of(Orientation.LANDSCAPE, Orientation.REVERSE_LANDSCAPE)),
        )
    }

    @Test
    fun `each stored state maps to a distinct request except auto which releases`() {
        assertEquals(portrait, LauncherOrientation.launcherTarget(portrait))
        assertEquals(landscape, LauncherOrientation.launcherTarget(landscape))
        assertEquals(reversePortrait, LauncherOrientation.launcherTarget(reversePortrait))
        assertEquals(reverseLandscape, LauncherOrientation.launcherTarget(reverseLandscape))
        assertEquals(auto, LauncherOrientation.launcherTarget(auto))
        assertEquals(auto, LauncherOrientation.launcherTarget(99))
    }

    @Test
    fun `shape predicates cover all four orientations`() {
        assertTrue(LauncherOrientation.isPortraitOrientation(portrait))
        assertTrue(LauncherOrientation.isPortraitOrientation(reversePortrait))
        assertEquals(false, LauncherOrientation.isPortraitOrientation(landscape))
        assertEquals(false, LauncherOrientation.isPortraitOrientation(reverseLandscape))

        assertTrue(LauncherOrientation.isConcrete(portrait))
        assertTrue(LauncherOrientation.isConcrete(landscape))
        assertTrue(LauncherOrientation.isConcrete(reversePortrait))
        assertTrue(LauncherOrientation.isConcrete(reverseLandscape))
        assertEquals(false, LauncherOrientation.isConcrete(auto))
        assertEquals(false, LauncherOrientation.isConcrete(99))
    }
}
