package com.winlator.inputcontrols

import android.graphics.Rect
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class RangeScrollerTest {
    @Test
    fun `queued long press is ignored after touch cancellation`() {
        verifyQueuedLongPressIsIgnored { it.cancelTouch() }
    }

    @Test
    fun `queued long press is ignored after normal touch up`() {
        verifyQueuedLongPressIsIgnored { it.handleTouchUp() }
    }

    private fun verifyQueuedLongPressIsIgnored(endTouch: (RangeScroller) -> Unit) {
        val currentTime = AtomicLong(0L)
        val scheduledLongPress = AtomicReference<Runnable>()
        val postedCallback = AtomicReference<Runnable>()
        val view = mockk<InputControlsView>(relaxed = true)
        every { view.post(any<Runnable>()) } answers {
            postedCallback.set(firstArg())
            true
        }
        val element = mockk<ControlElement>(relaxed = true)
        every { element.boundingBox } returns Rect(0, 0, 100, 100)
        every { element.bindingCount } returns 1
        every { element.range } returns ControlElement.Range.FROM_A_TO_Z
        every { element.orientation } returns 0.toByte()
        val scroller = object : RangeScroller(view, element) {
            override fun scheduleLongPress(callback: Runnable) {
                scheduledLongPress.set(callback)
            }

            override fun currentTimeMillis(): Long = currentTime.get()
        }

        scroller.handleTouchDown(50f, 50f)
        currentTime.set(TouchpadView.MAX_TAP_MILLISECONDS.toLong())
        endTouch(scroller)
        scheduledLongPress.get().run()
        postedCallback.get().run()

        verify(exactly = 0) { view.handleInputEvent(Binding.KEY_A, true) }
        verify(exactly = 1) { view.handleInputEvent(Binding.KEY_A, false) }
    }
}
