package app.gamenative.input

import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.GamepadState
import com.winlator.winhandler.WinHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for multi-controller support (Issue #565).
 *
 * Tests GamepadState independence and ExternalController isolation to verify
 * that multiple controllers can maintain independent state simultaneously.
 */
class MultiControllerTest {

    // ========================================================================
    // GamepadState Independence
    // ========================================================================

    @Test
    fun `GamepadState instances are independent - button press`() {
        val state1 = GamepadState()
        val state2 = GamepadState()

        state1.setPressed(ExternalController.IDX_BUTTON_A.toInt(), true)

        assertTrue(state1.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
        assertFalse(state2.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
    }

    @Test
    fun `GamepadState instances are independent - analog sticks`() {
        val state1 = GamepadState()
        val state2 = GamepadState()

        state1.thumbLX = 0.75f
        state1.thumbLY = -0.5f

        assertEquals(0.75f, state1.thumbLX, 0.001f)
        assertEquals(-0.5f, state1.thumbLY, 0.001f)
        assertEquals(0f, state2.thumbLX, 0.001f)
        assertEquals(0f, state2.thumbLY, 0.001f)
    }

    @Test
    fun `GamepadState instances are independent - triggers`() {
        val state1 = GamepadState()
        val state2 = GamepadState()

        state1.triggerL = 0.8f
        state2.triggerR = 0.6f

        assertEquals(0.8f, state1.triggerL, 0.001f)
        assertEquals(0f, state1.triggerR, 0.001f)
        assertEquals(0f, state2.triggerL, 0.001f)
        assertEquals(0.6f, state2.triggerR, 0.001f)
    }

    @Test
    fun `GamepadState instances are independent - dpad`() {
        val state1 = GamepadState()
        val state2 = GamepadState()

        state1.dpad[0] = true // up

        assertTrue(state1.dpad[0])
        assertFalse(state2.dpad[0])
    }

    @Test
    fun `GamepadState copy does not link instances`() {
        val state1 = GamepadState()
        val state2 = GamepadState()

        state1.setPressed(ExternalController.IDX_BUTTON_A.toInt(), true)
        state1.thumbLX = 0.5f
        state2.copy(state1)

        // Verify copy worked
        assertTrue(state2.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
        assertEquals(0.5f, state2.thumbLX, 0.001f)

        // Modify state1 after copy - state2 should not change
        state1.setPressed(ExternalController.IDX_BUTTON_B.toInt(), true)
        state1.thumbLX = -1f

        assertFalse(state2.isPressed(ExternalController.IDX_BUTTON_B.toInt()))
        assertEquals(0.5f, state2.thumbLX, 0.001f)
    }

    @Test
    fun `multiple GamepadState instances can hold different simultaneous inputs`() {
        val p1State = GamepadState()
        val p2State = GamepadState()
        val p3State = GamepadState()
        val p4State = GamepadState()

        p1State.setPressed(ExternalController.IDX_BUTTON_A.toInt(), true)
        p1State.thumbLX = 1f

        p2State.setPressed(ExternalController.IDX_BUTTON_B.toInt(), true)
        p2State.thumbLY = -1f

        p3State.setPressed(ExternalController.IDX_BUTTON_X.toInt(), true)
        p3State.triggerL = 0.5f

        p4State.setPressed(ExternalController.IDX_BUTTON_Y.toInt(), true)
        p4State.dpad[2] = true // down

        assertTrue(p1State.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
        assertFalse(p1State.isPressed(ExternalController.IDX_BUTTON_B.toInt()))
        assertEquals(1f, p1State.thumbLX, 0.001f)

        assertTrue(p2State.isPressed(ExternalController.IDX_BUTTON_B.toInt()))
        assertFalse(p2State.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
        assertEquals(-1f, p2State.thumbLY, 0.001f)

        assertTrue(p3State.isPressed(ExternalController.IDX_BUTTON_X.toInt()))
        assertEquals(0.5f, p3State.triggerL, 0.001f)

        assertTrue(p4State.isPressed(ExternalController.IDX_BUTTON_Y.toInt()))
        assertTrue(p4State.dpad[2])
        assertFalse(p1State.dpad[2])
    }

    // ========================================================================
    // Slot Constants
    // ========================================================================

    @Test
    fun `MAX_PLAYERS is 4`() {
        assertEquals(4, WinHandler.MAX_PLAYERS)
    }

    // ========================================================================
    // GamepadState writeTo - buffer layout used by sendGamepadState UDP path
    // ========================================================================

    @Test
    fun `writeTo encodes sticks as signed shorts`() {
        val state = GamepadState()
        state.thumbLX = 1f
        state.thumbLY = -1f

        val buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        // writeTo format: buttons(2) + povHat(1) + sticks(8) + triggers(2)
        state.writeTo(buffer)
        buffer.flip()

        buffer.getShort() // skip buttons
        buffer.get()      // skip povHat
        assertEquals(Short.MAX_VALUE, buffer.getShort()) // thumbLX = 1.0
        assertEquals((-Short.MAX_VALUE).toShort(), buffer.getShort()) // thumbLY = -1.0
    }

    @Test
    fun `writeTo encodes triggers as unsigned bytes`() {
        val state = GamepadState()
        state.triggerL = 1f
        state.triggerR = 0.5f

        val buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        state.writeTo(buffer)
        buffer.flip()

        // Skip: buttons(2) + povHat(1) + sticks(8) = 11 bytes
        buffer.position(11)
        assertEquals(255.toByte(), buffer.get()) // triggerL fully pressed
        assertEquals(127.toByte(), buffer.get()) // triggerR half pressed
    }

    @Test
    fun `writeTo encodes dpad as povHat`() {
        val state = GamepadState()
        state.dpad[0] = true // up

        val buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        state.writeTo(buffer)
        buffer.flip()

        buffer.getShort() // skip buttons
        val povHat = buffer.get()
        assertEquals(0.toByte(), povHat) // up = 0 in POV hat convention
    }

    @Test
    fun `writeTo dpad diagonal encodes correctly`() {
        val state = GamepadState()
        state.dpad[0] = true // up
        state.dpad[1] = true // right

        val buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        state.writeTo(buffer)
        buffer.flip()

        buffer.getShort() // skip buttons
        val povHat = buffer.get()
        assertEquals(1.toByte(), povHat) // up+right = 1
    }

    // ========================================================================
    // GamepadState dpad helpers
    // ========================================================================

    @Test
    fun `getDPadX returns correct direction`() {
        val state = GamepadState()

        // dpad[1] = right, dpad[3] = left
        state.dpad[1] = true
        assertEquals(1.toByte(), state.dPadX)

        state.dpad[1] = false
        state.dpad[3] = true
        assertEquals((-1).toByte(), state.dPadX)

        state.dpad[3] = false
        assertEquals(0.toByte(), state.dPadX)
    }

    @Test
    fun `getDPadY returns correct direction`() {
        val state = GamepadState()

        // dpad[0] = up (-1), dpad[2] = down (1)
        state.dpad[0] = true
        assertEquals((-1).toByte(), state.dPadY)

        state.dpad[0] = false
        state.dpad[2] = true
        assertEquals(1.toByte(), state.dPadY)
    }

    // ========================================================================
    // Button index constants match expected layout
    // ========================================================================

    @Test
    fun `button indices are contiguous 0 through 11`() {
        assertEquals(0, ExternalController.IDX_BUTTON_A.toInt())
        assertEquals(1, ExternalController.IDX_BUTTON_B.toInt())
        assertEquals(2, ExternalController.IDX_BUTTON_X.toInt())
        assertEquals(3, ExternalController.IDX_BUTTON_Y.toInt())
        assertEquals(4, ExternalController.IDX_BUTTON_L1.toInt())
        assertEquals(5, ExternalController.IDX_BUTTON_R1.toInt())
        assertEquals(6, ExternalController.IDX_BUTTON_SELECT.toInt())
        assertEquals(7, ExternalController.IDX_BUTTON_START.toInt())
        assertEquals(8, ExternalController.IDX_BUTTON_L3.toInt())
        assertEquals(9, ExternalController.IDX_BUTTON_R3.toInt())
        assertEquals(10, ExternalController.IDX_BUTTON_L2.toInt())
        assertEquals(11, ExternalController.IDX_BUTTON_R2.toInt())
    }

    @Test
    fun `all 12 buttons can be set independently`() {
        val state = GamepadState()

        // Press all buttons
        for (i in 0..11) {
            state.setPressed(i, true)
        }
        for (i in 0..11) {
            assertTrue("Button $i should be pressed", state.isPressed(i))
        }

        // Release only button A
        state.setPressed(ExternalController.IDX_BUTTON_A.toInt(), false)
        assertFalse(state.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
        // All others still pressed
        for (i in 1..11) {
            assertTrue("Button $i should still be pressed", state.isPressed(i))
        }
    }
}
