package app.gamenative.ui.component

import android.app.Application
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class LibraryScrollTrackingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothLayoutsBlockReorderingUntilBothAreIdleAndDisposalReleasesTheGate() {
        val gridMoving = mutableStateOf(false)
        val carouselMoving = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val grid = mockk<ScrollableState>()
        val carousel = mockk<ScrollableState>()
        every { grid.isScrollInProgress } answers { gridMoving.value }
        every { carousel.isScrollInProgress } answers { carouselMoving.value }
        var isScrolling: () -> Boolean = { false }
        compose.setContent {
            if (visible.value) LibraryScrollTracking(grid, carousel) { isScrolling = it }
        }
        compose.runOnIdle {
            assertEquals(false, isScrolling())
            carouselMoving.value = true
        }
        compose.runOnIdle {
            assertEquals(true, isScrolling())
            gridMoving.value = true
        }
        compose.runOnIdle { carouselMoving.value = false }
        compose.runOnIdle {
            assertEquals(true, isScrolling())
            gridMoving.value = false
        }
        compose.runOnIdle {
            assertEquals(false, isScrolling())
            carouselMoving.value = true
        }
        compose.runOnIdle {
            assertEquals(true, isScrolling())
            visible.value = false
        }
        compose.runOnIdle { assertEquals(false, isScrolling()) }
    }

    @Test fun callbackUpdatesDoNotResetAnActiveScrollAndRemountReportsCurrentState() {
        val moving = mutableStateOf(true)
        val visible = mutableStateOf(true)
        val useSecondCallback = mutableStateOf(false)
        val grid = mockk<ScrollableState>()
        val carousel = mockk<ScrollableState>()
        every { grid.isScrollInProgress } returns false
        every { carousel.isScrollInProgress } answers { moving.value }
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        compose.setContent {
            if (visible.value) {
                val callback: (() -> Boolean) -> Unit = if (useSecondCallback.value) ({ second += it() }) else ({ first += it() })
                LibraryScrollTracking(grid, carousel, callback)
            }
        }
        compose.runOnIdle {
            assertEquals(listOf(true), first)
            useSecondCallback.value = true
        }
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), second)
            visible.value = false
        }
        compose.runOnIdle {
            assertEquals(listOf(false), second)
            visible.value = true
        }
        compose.runOnIdle {
            assertEquals(listOf(false, true), second)
            moving.value = false
        }
        compose.runOnIdle { assertEquals(listOf(false, true), second) }
    }
}
