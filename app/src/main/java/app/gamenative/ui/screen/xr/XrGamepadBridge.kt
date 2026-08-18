package app.gamenative.ui.screen.xr

import com.winlator.inputcontrols.GamepadState
import com.winlator.winhandler.WinHandler

/** Feeds Meta Quest Touch controller input into Wine as a standard Xbox-layout gamepad. */
class XrGamepadBridge(
    private val winHandler: WinHandler,
    private val slot: Int = 0,
) {
    companion object {
        // Bit indices, matching the layout WinHandler.sendVirtualGamepadState already assumes.
        const val BUTTON_A = 0
        const val BUTTON_B = 1
        const val BUTTON_X = 2
        const val BUTTON_Y = 3
        const val BUTTON_LB = 4
        const val BUTTON_RB = 5
        const val BUTTON_BACK = 6
        const val BUTTON_START = 7
        const val BUTTON_L3 = 8
        const val BUTTON_R3 = 9
    }

    enum class Stick { LEFT, RIGHT }
    enum class Trigger { LEFT, RIGHT }

    private val state = GamepadState()

    fun updateButton(buttonIndex: Int, pressed: Boolean) {
        state.setPressed(buttonIndex, pressed)
    }

    fun updateThumbstick(stick: Stick, x: Float, y: Float) {
        val cx = x.coerceIn(-1f, 1f)
        val cy = y.coerceIn(-1f, 1f)
        when (stick) {
            Stick.LEFT -> {
                state.thumbLX = cx
                state.thumbLY = cy
            }
            Stick.RIGHT -> {
                state.thumbRX = cx
                state.thumbRY = cy
            }
        }
    }

    fun updateTrigger(trigger: Trigger, value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        when (trigger) {
            Trigger.LEFT -> state.triggerL = clamped
            Trigger.RIGHT -> state.triggerR = clamped
        }
    }

    /** Pushes the accumulated state to Wine. Call once per frame after the update* calls above. */
    fun commit() {
        winHandler.sendVirtualGamepadState(state, slot)
    }

    /**
     * Clears every button and axis and commits the neutral state, so nothing stays latched in the
     * game while input is being routed somewhere else (quick menu, XR pointer mode).
     */
    fun reset() {
        for (bit in BUTTON_A..BUTTON_R3) {
            state.setPressed(bit, false)
        }
        state.thumbLX = 0f
        state.thumbLY = 0f
        state.thumbRX = 0f
        state.thumbRY = 0f
        state.triggerL = 0f
        state.triggerR = 0f
        commit()
    }

    /**
     * Applies one raw snapshot as read from [XrNative.nativePollSnapshot] and commits it.
     * buttonBitmask bit layout matches [BUTTON_A]..[BUTTON_R3]; axes is
     * [leftX, leftY, rightX, rightY, triggerL, triggerR].
     */
    fun applySnapshot(buttonBitmask: Int, axes: FloatArray) {
        for (bit in BUTTON_A..BUTTON_R3) {
            updateButton(bit, (buttonBitmask and (1 shl bit)) != 0)
        }
        updateThumbstick(Stick.LEFT, axes[0], axes[1])
        updateThumbstick(Stick.RIGHT, axes[2], axes[3])
        updateTrigger(Trigger.LEFT, axes[4])
        updateTrigger(Trigger.RIGHT, axes[5])
        commit()
    }
}
