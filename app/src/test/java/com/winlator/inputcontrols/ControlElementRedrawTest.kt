package com.winlator.inputcontrols

import com.winlator.widget.InputControlsView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlElementRedrawTest {
    private fun view(): InputControlsView = mock<InputControlsView>().also {
        whenever(it.snappingSize).thenReturn(10)
    }

    private fun element(view: InputControlsView, type: ControlElement.Type) =
        ControlElement(view).apply {
            setType(type)
            setX(100)
            setY(100)
        }

    @Test
    fun `d-pad redraws only when the held directions change`() {
        val view = view()
        // D-pad radius is 70 here: y = 58 is 0.6 up, x = 150 adds 0.71 right.
        val dpad = element(view, ControlElement.Type.D_PAD)

        assertTrue(dpad.handleTouchDown(1, 100f, 58f))
        verify(view, times(2)).invalidate()

        assertTrue(dpad.handleTouchMove(1, 101f, 57f))
        assertTrue(dpad.handleTouchMove(1, 99f, 59f))
        verify(view, times(2)).invalidate()

        assertTrue(dpad.handleTouchMove(1, 150f, 58f))
        verify(view, times(3)).invalidate()
    }

    @Test
    fun `stick ignores sub-pixel finger jitter`() {
        val view = view()
        val stick = element(view, ControlElement.Type.STICK)

        assertTrue(stick.handleTouchDown(1, 130f, 100f))
        verify(view, times(2)).invalidate()

        assertTrue(stick.handleTouchMove(1, 130.3f, 100.2f))
        verify(view, times(2)).invalidate()

        assertTrue(stick.handleTouchMove(1, 131f, 100f))
        verify(view, times(3)).invalidate()
    }
}
