package com.winlator.widget

import android.view.MotionEvent
import com.winlator.inputcontrols.ExternalControllerBinding
import org.junit.Assert.assertEquals
import org.junit.Test

class InputControlsViewStickMixingTest {
    @Test
    fun `vertical physical stick returns to center for both directions`() {
        val rawDownSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, 1)
        val rawUpSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, -1)

        val down = InputControlsView.updatePhysicalBaseAxis(0f, true, 0.8f, rawDownSource)
        val downReleased = InputControlsView.updatePhysicalBaseAxis(down, false, 0f, rawDownSource)
        val up = InputControlsView.updatePhysicalBaseAxis(0f, true, -0.7f, rawUpSource)
        val upReleased = InputControlsView.updatePhysicalBaseAxis(up, false, 0f, rawUpSource)

        assertEquals(0f, downReleased, 0f)
        assertEquals(0f, upReleased, 0f)
    }

    @Test
    fun `releasing old direction does not clear newly active direction`() {
        val rawDownSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, 1)
        val rawUpSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, -1)

        val down = InputControlsView.updatePhysicalBaseAxis(0f, true, 0.8f, rawDownSource)
        val up = InputControlsView.updatePhysicalBaseAxis(down, true, -0.7f, rawUpSource)

        assertEquals(-0.7f, InputControlsView.updatePhysicalBaseAxis(up, false, 0f, rawDownSource), 0f)
    }
}
