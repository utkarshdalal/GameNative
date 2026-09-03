package com.winlator.inputcontrols

import android.graphics.Rect
import com.winlator.widget.InputControlsView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        val postedCallback = AtomicReference<Runnable>()
        val callbackPosted = CountDownLatch(1)
        val view = mockk<InputControlsView>(relaxed = true)
        every { view.post(any<Runnable>()) } answers {
            postedCallback.set(firstArg())
            callbackPosted.countDown()
            true
        }
        val element = mockk<ControlElement>(relaxed = true)
        every { element.boundingBox } returns Rect(0, 0, 100, 100)
        every { element.bindingCount } returns 1
        every { element.range } returns ControlElement.Range.FROM_A_TO_Z
        every { element.orientation } returns 0.toByte()
        val scroller = RangeScroller(view, element)

        scroller.handleTouchDown(50f, 50f)
        assertTrue(callbackPosted.await(1, TimeUnit.SECONDS))

        endTouch(scroller)
        postedCallback.get().run()

        verify(exactly = 0) { view.handleInputEvent(Binding.KEY_A, true) }
        verify(exactly = 1) { view.handleInputEvent(Binding.KEY_A, false) }
    }
}
