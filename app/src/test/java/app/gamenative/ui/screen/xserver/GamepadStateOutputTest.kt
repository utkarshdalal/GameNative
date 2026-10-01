package app.gamenative.ui.screen.xserver

import android.os.Looper
import com.winlator.inputcontrols.GamepadState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit.MILLISECONDS

@RunWith(RobolectricTestRunner::class)
class GamepadStateOutputTest {
    @Test
    fun `a state equal to the last one sent is not sent again, unless forced`() {
        val sent = mutableListOf<Short>()
        val output = GamepadStateOutput(InputThrottling()) { state -> sent += state!!.buttons }
        val state = GamepadState()
        state.setPressed(0, true)

        output.send(state)
        output.send(state)
        output.sendMotion(state)
        state.thumbLX = 0.00001f // below the 16-bit precision Wine gets
        output.sendMotion(state)
        assertEquals(1, sent.size)

        output.sendForced(state)
        assertEquals(2, sent.size)
    }

    @Test
    fun `throttled motion waits for a later frame and goes out as it is by then`() {
        val sent = mutableListOf<Float>()
        val throttling = InputThrottling().apply {
            enabled = true
            setRateHz(60)
        }
        val output = GamepadStateOutput(throttling) { state -> sent += state!!.thumbLX }
        val state = GamepadState()

        state.thumbLX = 0.5f
        output.sendMotion(state)
        state.thumbLX = 0.7f
        output.sendMotion(state)
        state.thumbLX = 0.9f
        output.sendMotion(state)
        assertEquals(listOf(0.5f), sent)

        shadowOf(Looper.getMainLooper()).idleFor(50, MILLISECONDS)
        assertEquals(listOf(0.5f, 0.9f), sent)
    }

    @Test
    fun `buttons are not held back by throttling`() {
        val sent = mutableListOf<Short>()
        val throttling = InputThrottling().apply {
            enabled = true
            setRateHz(15)
        }
        val output = GamepadStateOutput(throttling) { state -> sent += state!!.buttons }
        val state = GamepadState()

        state.thumbLX = 0.5f
        output.sendMotion(state)
        state.setPressed(0, true)
        output.send(state)

        assertEquals(2, sent.size)
    }

    @Test
    fun `a newer state sent meanwhile drops held-back motion of another instance`() {
        val sent = mutableListOf<Float>()
        val throttling = InputThrottling().apply {
            enabled = true
            setRateHz(15)
        }
        val output = GamepadStateOutput(throttling) { state -> sent += state!!.thumbLX }
        val oldProfileState = GamepadState()
        val newProfileState = GamepadState()

        oldProfileState.thumbLX = 0.5f
        output.sendMotion(oldProfileState)
        oldProfileState.thumbLX = 0.9f
        output.sendMotion(oldProfileState) // held back
        output.sendForced(newProfileState) // e.g. the profile switched
        shadowOf(Looper.getMainLooper()).idleFor(200, MILLISECONDS)

        assertEquals(listOf(0.5f, 0f), sent)
    }

    @Test
    fun `nothing counts as sent while the sender can't deliver`() {
        val sent = mutableListOf<Short>()
        var available = false
        val output = GamepadStateOutput(
            InputThrottling(),
            object : GamepadStateOutput.Sender {
                override fun send(state: GamepadState?) {
                    sent += state!!.buttons
                }

                override fun canSend(): Boolean = available
            },
        )
        val state = GamepadState()
        state.setPressed(0, true)

        output.send(state)
        available = true
        output.send(state)

        assertEquals(1, sent.size)
    }
}
