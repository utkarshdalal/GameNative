package app.gamenative.ui.screen.xserver

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PluviaApp
import com.winlator.container.Container
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.widget.InputControlsView
import com.winlator.winhandler.WinHandler
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [DeferredControlsVisibilityTest.ShadowWinHandler::class])
class DeferredControlsVisibilityTest {
    /** The rendering tests use a mock handler; its Android-only native library is not needed. */
    @Implements(value = WinHandler::class, isInAndroidSdk = false)
    class ShadowWinHandler {
        companion object {
            @JvmStatic
            @Implementation
            fun __staticInitializer__() {}
        }
    }

    @Test
    fun aHiddenOverlayIsMeasuredBeforeTheUserEnablesIt() = withDeferredShow { _, _, _, _ ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = InputControlsView(context)
        val host = FrameLayout(context).apply { addView(view) }
        PluviaApp.inputControlsView = view

        hideInputControls()
        host.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, 1000, 500)

        assertEquals(View.INVISIBLE, view.visibility)
        assertEquals(1000, view.maxWidth)
        assertEquals(500, view.maxHeight)
    }

    @Test
    fun hidingControlsWhileWaitingForLayoutCancelsThePendingShow() = withDeferredShow { view, profile, handler, callbacks ->
        var requested = true
        showInputControls(profile, handler, Container("STEAM_1")) { requested }
        assertEquals(1, callbacks.size)

        requested = false
        callbacks.single().run()

        verify(view, never()).setShowTouchscreenControls(true)
        verify(profile, never()).loadElements(any())
    }

    @Test
    fun recreatingTheViewDiscardsTheOldViewsPendingShow() = withDeferredShow { view, profile, handler, callbacks ->
        showInputControls(profile, handler, Container("STEAM_1")) { true }
        PluviaApp.inputControlsView = mock()
        callbacks.single().run()

        verify(view, never()).setShowTouchscreenControls(true)
        verify(profile, never()).loadElements(any())
    }

    @Test
    fun anUnchangedShowRequestStillLoadsAndDisplaysControls() = withDeferredShow { view, profile, handler, callbacks ->
        showInputControls(profile, handler, Container("STEAM_1")) { true }
        callbacks.single().run()

        verify(profile).loadElements(view)
        verify(view).setShowTouchscreenControls(true)
        verify(handler).refreshControllerMappings()
    }

    private fun withDeferredShow(block: (InputControlsView, ControlsProfile, WinHandler, List<Runnable>) -> Unit) {
        val previousView = PluviaApp.inputControlsView
        val previousTouchpad = PluviaApp.touchpadView
        val previousCoordinator = PluviaApp.radialMenuCoordinator
        val callbacks = mutableListOf<Runnable>()
        val view = mock<InputControlsView>()
        whenever(view.post(any())).doAnswer {
            callbacks.add(it.getArgument(0))
            true
        }
        PluviaApp.inputControlsView = view
        PluviaApp.touchpadView = null
        PluviaApp.radialMenuCoordinator = null
        try {
            block(view, mock(), mock(), callbacks)
        } finally {
            PluviaApp.inputControlsView = previousView
            PluviaApp.touchpadView = previousTouchpad
            PluviaApp.radialMenuCoordinator = previousCoordinator
        }
    }
}
