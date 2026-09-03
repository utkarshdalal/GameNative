package app.gamenative.ui.screen.xserver

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.PrefManager
import app.gamenative.ui.component.dialog.screenshotComboKeyId
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.GamepadState
import com.winlator.inputcontrols.JoyConSupport
import com.winlator.math.Mathf
import com.winlator.xserver.XServer
import java.util.Timer
import java.util.TimerTask

/**
 * Standalone handler for physical controller input that works independently of view visibility.
 * Applies profile bindings to convert physical controller input into virtual gamepad state.
 */
class PhysicalControllerHandler(
    private var profile: ControlsProfile?,
    private val xServer: XServer?,
    private val onOpenNavigationMenu: (() -> Unit)? = null,
    private val onShowKeyboard: (() -> Unit)? = null,
    private val onRadialMenuButtonStateChanged: ((Boolean, Boolean) -> Unit)? = null,
    private val onRadialMenuVectorChanged: ((Float, Float) -> Unit)? = null,
    private val onGyroModifierChanged: ((Any, Boolean) -> Unit)? = null,
    private val gyroStickMixer: ((Binding, Boolean, Float) -> Float)? = null,
    private val gamepadStateSender: (GamepadState?) -> Unit = { state ->
        xServer?.winHandler?.let { winHandler ->
            winHandler.sendGamepadState()
            winHandler.sendVirtualGamepadState(state)
        }
    },
    private val onTakeScreenshot: (() -> Unit)? = null,
) {
    private data class PhysicalInputSource(val deviceId: Int, val keyCode: Int)

    private data class MouseMoveSource(
        val deviceId: Int,
        val keyCode: Int,
        val binding: Binding,
    )

    companion object {
        private const val SCROLL_REPEAT_INTERVAL_MS = 90L
        private const val UNKNOWN_DEVICE_ID = -1
        private const val SEQUENCE_PRESS_MS = 80L
    }

    private val TAG = "gncontrol"
    private val mouseMoveOffset = PointF(0f, 0f)
    private val mouseMoveContributions = mutableMapOf<MouseMoveSource, Float>()
    private val sequenceHandler = Handler(Looper.getMainLooper())
    private var mouseMoveTimer: Timer? = null
    private var scrollRepeatTimer: Timer? = null
    private val scrollRepeatLock = Any()
    private val activeScrollBindings = mutableSetOf<Binding>()
    // track which axis keycodes are currently "pressed" so we only release on actual transitions.
    // accessed only from main thread (MotionEvent dispatch + Compose lifecycle), no sync needed.
    private val activeAxisBindings = mutableSetOf<PhysicalInputSource>()
    private val activeButtonBindings = mutableMapOf<PhysicalInputSource, BindingCombo>()
    private val activeTriggerBindings = mutableMapOf<PhysicalInputSource, BindingCombo>()
    private val activeSequenceTriggerBindings = mutableSetOf<PhysicalInputSource>()
    private val activeSequenceBindings = mutableMapOf<Binding, Int>()
    private val activeSequenceGyroSources = mutableSetOf<PhysicalInputSource>()
    private val activeGyroModifierSources = mutableSetOf<PhysicalInputSource>()

    // Tracks whether SHOW_KEYBOARD is currently held, so onShowKeyboard fires once per press (rising edge only)
    private var showKeyboardPressed = false
    private var radialMenuPressed = false
    private var radialMenuOpenedFromMotion = false
    private var radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID

    private fun releaseActiveAxes(
        exceptSource: PhysicalInputSource? = null,
        deviceId: Int? = null,
    ) {
        for (source in activeAxisBindings.toList()) {
            if (source == exceptSource || (deviceId != null && source.deviceId != deviceId)) continue
            activeAxisBindings.remove(source)
            val controller = profile?.getController(source.deviceId) ?: continue
            controller.getControllerBinding(source.keyCode)
                ?.takeIf { Binding.OPEN_RADIAL_MENU !in it.bindingCombo.bindings }
                ?.let {
                    handleInputEvent(
                        it.bindingCombo,
                        false,
                        0f,
                        fromMotion = true,
                        sourceKeyCode = source.keyCode,
                        sourceDeviceId = source.deviceId,
                        sourceController = controller,
                    )
                }
        }
    }

    private fun releaseActiveBindings(
        bindings: MutableMap<PhysicalInputSource, BindingCombo>,
        deviceId: Int? = null,
        fromMotion: Boolean = false,
    ) {
        for ((source, bindingCombo) in bindings.toMap()) {
            if (deviceId != null && source.deviceId != deviceId) continue
            bindings.remove(source)
            val controller = profile?.getController(source.deviceId)
            handleInputEvent(
                bindingCombo,
                false,
                0f,
                fromMotion = fromMotion,
                sourceKeyCode = source.keyCode,
                sourceDeviceId = source.deviceId,
                sourceController = controller,
            )
        }
    }

    // Currently-held physical keys per input device + screenshot combo state (settings-defined
    // multi-button shortcut). Scoped by deviceId so keys from two different controllers can't
    // be combined into a false combo trigger.
    private val pressedKeyCodesByDevice = mutableMapOf<Int, MutableSet<Int>>()
    private var screenshotComboFired = false
    // Cached so the key hot-path never does a blocking DataStore read. Refreshed at construction and
    // in setProfile() (i.e. whenever the game/profile (re)loads), which covers a binding changed in
    // Settings before returning to the game. Avoids a runBlocking read on the input dispatch thread.
    private var screenshotCombo: List<Int> = parseScreenshotCombo()

    private fun parseScreenshotCombo(): List<Int> =
        PrefManager.screenshotComboKeys.split(",").mapNotNull { it.trim().toIntOrNull() }

    fun setProfile(profile: ControlsProfile?) {
        releaseActiveBindings(activeButtonBindings)
        releaseActiveBindings(activeTriggerBindings, fromMotion = true)
        releaseGyroModifierSources()
        releaseActiveAxes()
        cancelActiveSequences()
        clearMouseMoveContributions()
        clearScrollRepeats()
        closeRadialMenuIfOpen(commit = false)
        activeSequenceTriggerBindings.clear()
        sendGamepadState()
        this.profile = profile
        screenshotCombo = parseScreenshotCombo()
        Log.d(TAG, "PhysicalControllerHandler: Profile set to ${profile?.name}")
    }

    /**
     * Clean up resources when handler is destroyed
     */
    fun cleanup() {
        releaseActiveBindings(activeButtonBindings)
        releaseActiveBindings(activeTriggerBindings, fromMotion = true)
        releaseGyroModifierSources()
        releaseActiveAxes()
        cancelActiveSequences()
        clearMouseMoveContributions()
        clearScrollRepeats()
        activeSequenceTriggerBindings.clear()
        showKeyboardPressed = false
        closeRadialMenuIfOpen(commit = false)
        sendGamepadState()
        pressedKeyCodesByDevice.clear()
        screenshotComboFired = false
    }

    /**
     * Clear held-key tracking. Call when key events stop being forwarded to this handler (e.g. an
     * overlay/menu/editor opens), so a held binding whose ACTION_UP is routed elsewhere can't linger:
     * a combo key would otherwise falsely satisfy the screenshot combo on a partial press, and a held
     * SHOW_KEYBOARD binding would leave [showKeyboardPressed] latched so the next press is ignored.
     */
    fun resetPressedKeys() {
        if (pressedKeyCodesByDevice.isEmpty() && !screenshotComboFired && !showKeyboardPressed) return
        pressedKeyCodesByDevice.clear()
        screenshotComboFired = false
        showKeyboardPressed = false
    }

    fun onInputDeviceRemoved(deviceId: Int) {
        cancelActiveSequences()
        releaseActiveBindings(activeButtonBindings, deviceId)
        releaseActiveBindings(activeTriggerBindings, deviceId, fromMotion = true)
        releaseActiveAxes(deviceId = deviceId)
        releaseGyroModifierSources(deviceId)
        mouseMoveContributions.keys.removeAll { it.deviceId == deviceId }
        recalculateMouseMoveOffset()
        sendGamepadState()
        if (radialMenuPressed && radialMenuOpenerDeviceId == deviceId) {
            closeRadialMenuIfOpen(commit = false)
        }
    }

    /**
     * Handle physical controller button events.
     * Extracted from InputControlsView.onKeyEvent()
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        // Track held keys and fire the settings-defined screenshot combo. Additive: does not consume
        // the event, so the buttons keep their normal game function.
        if (event.repeatCount == 0) {
            // Match the bind dialog's id: keyCode, or -scanCode for buttons with no keyCode (paddles).
            val keyId = screenshotComboKeyId(event.keyCode, event.scanCode)
            val deviceKeys = pressedKeyCodesByDevice.getOrPut(event.deviceId) { mutableSetOf() }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> deviceKeys.add(keyId)
                KeyEvent.ACTION_UP -> deviceKeys.remove(keyId)
            }
            checkScreenshotCombo()
        }

        if (profile != null && event.repeatCount == 0) {
            val keyCode = JoyConSupport.remapKeyCode(event.device, event)
            if (radialMenuPressed && !isRadialMenuOpenerDevice(event.deviceId)) return true
            val controller = profile?.getController(event.deviceId)
            if (controller != null) {
                val controllerBinding = controller.getControllerBinding(keyCode)
                if (radialMenuPressed && controllerBinding?.bindingCombo?.bindings?.contains(Binding.OPEN_RADIAL_MENU) == true) {
                    if (keyCode == radialMenuOpenerKeyCode ||
                        radialMenuOpenerKeyCode == KeyEvent.KEYCODE_UNKNOWN
                    ) {
                        handleInputEvent(
                            controllerBinding.bindingCombo,
                            event.action == KeyEvent.ACTION_DOWN,
                            sourceKeyCode = keyCode,
                            sourceDeviceId = event.deviceId,
                            sourceController = controller,
                        )
                        return true
                    }
                    handleRadialMenuNavigationKey(event, keyCode)
                    return true
                }
                if (radialMenuPressed && handleRadialMenuNavigationKey(event, keyCode)) {
                    return true
                }

                if (controllerBinding != null) {
                    // Some controllers emit BOTH a digital KeyEvent for L2/R2 and an analog axis value in MotionEvent.
                    // If this physical key is mapped to a virtual trigger AND the device exposes trigger axes,
                    // ignore the KeyEvent to avoid an initial "full press" spike. MotionEvent will provide the analog value.
                    if ((keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) &&
                        controllerBinding.bindingCombo.bindings.any { it == Binding.GAMEPAD_BUTTON_L2 || it == Binding.GAMEPAD_BUTTON_R2 } &&
                        deviceHasTriggerAxis(event.device, keyCode)
                    ) {
                        return true
                    }
                    val isActionDown = event.action == KeyEvent.ACTION_DOWN
                    val source = PhysicalInputSource(event.deviceId, keyCode)
                    val bindingCombo = if (!controllerBinding.bindingCombo.isSequence &&
                        Binding.OPEN_RADIAL_MENU !in controllerBinding.bindingCombo.bindings
                    ) {
                        if (isActionDown) {
                            activeButtonBindings[source] = controllerBinding.bindingCombo
                            controllerBinding.bindingCombo
                        } else {
                            activeButtonBindings.remove(source) ?: controllerBinding.bindingCombo
                        }
                    } else {
                        controllerBinding.bindingCombo
                    }
                    val offset = if (isActionDown &&
                        bindingCombo.bindings.any { it == Binding.GAMEPAD_BUTTON_L2 || it == Binding.GAMEPAD_BUTTON_R2 }
                    ) 1f else 0f
                    handleInputEvent(
                        bindingCombo,
                        isActionDown,
                        offset,
                        sourceKeyCode = keyCode,
                        sourceDeviceId = event.deviceId,
                        sourceController = controller,
                    )

                    sendGamepadState()
                    return true
                }
            }
        }
        return false
    }

    /**
     * Fires once when every combo key is held. Re-arms as soon as any key lifts, so a combo button the
     * game holds (e.g. L1) doesn't wedge the shortcut.
     */
    private fun checkScreenshotCombo() {
        val combo = screenshotCombo
        if (combo.isEmpty()) return
        // A single device must hold the entire combo; keys from different devices don't combine.
        val satisfied = pressedKeyCodesByDevice.values.any { it.containsAll(combo) }
        if (satisfied) {
            if (!screenshotComboFired) {
                screenshotComboFired = true
                onTakeScreenshot?.invoke()
            }
        } else {
            screenshotComboFired = false
        }
    }

    private fun deviceHasTriggerAxis(device: InputDevice?, keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 ->
                hasMotionRange(device, MotionEvent.AXIS_LTRIGGER) || hasMotionRange(device, MotionEvent.AXIS_BRAKE)
            KeyEvent.KEYCODE_BUTTON_R2 ->
                hasMotionRange(device, MotionEvent.AXIS_RTRIGGER) || hasMotionRange(device, MotionEvent.AXIS_GAS)
            else -> false
        }
    }

    private fun hasMotionRange(device: InputDevice?, axis: Int): Boolean {
        if (device == null) return false
        return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null ||
            device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null ||
            device.getMotionRange(axis) != null
    }

    /**
     * Handle physical controller analog stick and trigger events.
     * Extracted from InputControlsView.onGenericMotionEvent()
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (profile != null) {
            if (radialMenuPressed && !isRadialMenuOpenerDevice(event.deviceId)) return true
            val controller = profile?.getController(event.deviceId)
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                if (radialMenuPressed) {
                    updateRadialMenuVector(controller)
                    if (radialMenuOpenedFromMotion && !isRadialMenuMotionOpenerPressed(controller)) {
                        handleInputEvent(
                            Binding.OPEN_RADIAL_MENU,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = radialMenuOpenerKeyCode,
                            sourceDeviceId = event.deviceId,
                            sourceController = controller,
                        )
                    }
                    return true
                }

                // Process trigger buttons (L2/R2)
                var controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2)
                if (controllerBinding != null) {
                    handleTriggerBinding(
                        KeyEvent.KEYCODE_BUTTON_L2,
                        controllerBinding.binding,
                        controllerBinding.bindingCombo,
                        controller.state.triggerL > 0f,
                        controller.state.triggerL,
                        fromMotion = true,
                        sourceKeyCode = KeyEvent.KEYCODE_BUTTON_L2,
                        sourceDeviceId = event.deviceId,
                        sourceController = controller,
                    )
                    if (radialMenuPressed) {
                        sendGamepadState()
                        return true
                    }
                }

                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2)
                if (controllerBinding != null) {
                    handleTriggerBinding(
                        KeyEvent.KEYCODE_BUTTON_R2,
                        controllerBinding.binding,
                        controllerBinding.bindingCombo,
                        controller.state.triggerR > 0f,
                        controller.state.triggerR,
                        fromMotion = true,
                        sourceKeyCode = KeyEvent.KEYCODE_BUTTON_R2,
                        sourceDeviceId = event.deviceId,
                        sourceController = controller,
                    )
                    if (radialMenuPressed) {
                        sendGamepadState()
                        return true
                    }
                }

                // Process analog stick input
                processJoystickInput(controller, event.deviceId)

                sendGamepadState()
                return true
            }
        }
        return false
    }

    private fun sendGamepadState() {
        gamepadStateSender(profile?.gamepadState)
    }

    /**
     * Create a timer for continuous mouse movement injection.
     * Runs at 60 FPS, injecting mouse deltas based on mouseMoveOffset.
     */
    private fun createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            mouseMoveTimer = Timer()
            mouseMoveTimer?.schedule(object : TimerTask() {
                override fun run() {
                    // Skip injection if movement is below 8% deadzone to save CPU cycles
                    val magnitude = Math.sqrt((mouseMoveOffset.x * mouseMoveOffset.x + mouseMoveOffset.y * mouseMoveOffset.y).toDouble())
                    if (magnitude < 0.08) return

                    // Look up cursor speed dynamically so it updates when profile changes
                    val cursorSpeed = profile?.cursorSpeed ?: 1f
                    val deltaX = (mouseMoveOffset.x * 10 * cursorSpeed).toInt()
                    val deltaY = (mouseMoveOffset.y * 10 * cursorSpeed).toInt()
                    xServer?.injectPointerMoveDelta(deltaX, deltaY)
                }
            }, 0, 1000 / 60)
        }
    }

    private fun updateMouseMoveContribution(
        binding: Binding,
        isActionDown: Boolean,
        offset: Float,
        sourceKeyCode: Int,
        sourceDeviceId: Int,
    ) {
        if (isActionDown) {
            val contribution = if (offset != 0f) {
                offset
            } else if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_UP) {
                -1f
            } else {
                1f
            }
            mouseMoveContributions[MouseMoveSource(sourceDeviceId, sourceKeyCode, binding)] = contribution
            createMouseMoveTimer()
        } else {
            mouseMoveContributions.keys.removeAll { source ->
                source.binding == binding &&
                    (sourceKeyCode == KeyEvent.KEYCODE_UNKNOWN || source.keyCode == sourceKeyCode) &&
                    (sourceDeviceId == UNKNOWN_DEVICE_ID || source.deviceId == sourceDeviceId)
            }
        }
        recalculateMouseMoveOffset()
    }

    private fun recalculateMouseMoveOffset() {
        mouseMoveOffset.set(0f, 0f)
        mouseMoveContributions.forEach { (source, contribution) ->
            if (source.binding == Binding.MOUSE_MOVE_LEFT || source.binding == Binding.MOUSE_MOVE_RIGHT) {
                mouseMoveOffset.x += contribution
            } else {
                mouseMoveOffset.y += contribution
            }
        }
        if (mouseMoveContributions.isEmpty()) {
            mouseMoveTimer?.cancel()
            mouseMoveTimer = null
        }
    }

    private fun clearMouseMoveContributions() {
        mouseMoveContributions.clear()
        mouseMoveOffset.set(0f, 0f)
        mouseMoveTimer?.cancel()
        mouseMoveTimer = null
    }

    private fun handleScrollBinding(binding: Binding, isActionDown: Boolean): Boolean {
        if (binding != Binding.MOUSE_SCROLL_UP && binding != Binding.MOUSE_SCROLL_DOWN) {
            return false
        }

        var pulseImmediately = false
        synchronized(scrollRepeatLock) {
            if (isActionDown) {
                pulseImmediately = activeScrollBindings.add(binding)
                createScrollRepeatTimerLocked()
            } else {
                activeScrollBindings.remove(binding)
                if (activeScrollBindings.isEmpty()) {
                    cancelScrollRepeatTimerLocked()
                }
            }
        }

        if (pulseImmediately) {
            sendScrollPulse(binding)
        }
        return true
    }

    private fun createScrollRepeatTimerLocked() {
        if (scrollRepeatTimer != null) return
        scrollRepeatTimer = Timer()
        scrollRepeatTimer?.schedule(object : TimerTask() {
            override fun run() {
                val bindings = synchronized(scrollRepeatLock) {
                    activeScrollBindings.toList()
                }
                bindings.forEach { sendScrollPulse(it) }
            }
        }, SCROLL_REPEAT_INTERVAL_MS, SCROLL_REPEAT_INTERVAL_MS)
    }

    private fun cancelScrollRepeatTimerLocked() {
        scrollRepeatTimer?.cancel()
        scrollRepeatTimer = null
    }

    private fun clearScrollRepeats() {
        synchronized(scrollRepeatLock) {
            activeScrollBindings.clear()
            cancelScrollRepeatTimerLocked()
        }
    }

    private fun sendScrollPulse(binding: Binding) {
        val pointerButton = binding.pointerButton ?: return
        xServer?.injectPointerButtonPress(pointerButton)
        xServer?.injectPointerButtonRelease(pointerButton)
    }

    /**
     * Process analog stick input and apply bindings.
     * Extracted from InputControlsView.processJoystickInput()
     */
    private fun processJoystickInput(controller: ExternalController, deviceId: Int) {
        val axes = intArrayOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y
        )
        val values = floatArrayOf(
            controller.state.thumbLX,
            controller.state.thumbLY,
            controller.state.thumbRX,
            controller.state.thumbRY,
            controller.state.dPadX.toFloat(),
            controller.state.dPadY.toFloat()
        )

        for (i in axes.indices) {
            val posKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], 1.toByte())
            val negKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], (-1).toByte())
            val positiveSource = PhysicalInputSource(deviceId, posKeyCode)
            val negativeSource = PhysicalInputSource(deviceId, negKeyCode)

            if (Math.abs(values[i]) > ControlElement.STICK_DEAD_ZONE) {
                val activeKey = ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i]))
                val oppositeKey = if (activeKey == posKeyCode) negKeyCode else posKeyCode
                val activeSource = if (activeKey == posKeyCode) positiveSource else negativeSource
                val oppositeSource = if (activeKey == posKeyCode) negativeSource else positiveSource

                val wasAlreadyActive = !activeAxisBindings.add(activeSource)
                controller.getControllerBinding(activeKey)?.let {
                    if (!it.bindingCombo.isSequence || !wasAlreadyActive) {
                        handleInputEvent(
                            it.bindingCombo,
                            true,
                            values[i],
                            fromMotion = true,
                            sourceKeyCode = activeKey,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                    if (radialMenuPressed) return
                }
                // release opposite direction (if it was active)
                if (activeAxisBindings.remove(oppositeSource)) {
                    controller.getControllerBinding(oppositeKey)?.let {
                        handleInputEvent(
                            it.bindingCombo,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = oppositeKey,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                }
            } else {
                // release both directions only if they were active
                if (activeAxisBindings.remove(positiveSource)) {
                    controller.getControllerBinding(posKeyCode)?.let {
                        handleInputEvent(
                            it.bindingCombo,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = posKeyCode,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                }
                if (activeAxisBindings.remove(negativeSource)) {
                    controller.getControllerBinding(negKeyCode)?.let {
                        handleInputEvent(
                            it.bindingCombo,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = negKeyCode,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                }
            }
        }
    }

    private fun handleTriggerBinding(
        keyCode: Int,
        legacyBinding: Binding,
        bindingCombo: BindingCombo,
        isPressed: Boolean,
        offset: Float,
        fromMotion: Boolean = false,
        sourceKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        sourceDeviceId: Int = UNKNOWN_DEVICE_ID,
        sourceController: ExternalController? = null,
    ) {
        val triggerSource = physicalInputSource(sourceDeviceId, keyCode, sourceController)
        if (bindingCombo.isSequence) {
            if (isPressed) {
                if (activeSequenceTriggerBindings.add(triggerSource)) {
                    handleInputEvent(
                        bindingCombo,
                        true,
                        offset,
                        fromMotion,
                        sourceKeyCode,
                        sourceDeviceId,
                        sourceController,
                    )
                }
            } else {
                activeSequenceTriggerBindings.remove(triggerSource)
            }
        } else {
            val resolvedBindingCombo = bindingCombo.takeIf { !it.isEmpty } ?: BindingCombo.of(legacyBinding)
            val dispatchedBindingCombo = if (isPressed) {
                activeTriggerBindings[triggerSource] = resolvedBindingCombo
                resolvedBindingCombo
            } else {
                activeTriggerBindings.remove(triggerSource) ?: resolvedBindingCombo
            }
            handleInputEvent(
                dispatchedBindingCombo,
                isPressed,
                offset,
                fromMotion,
                sourceKeyCode,
                sourceDeviceId,
                sourceController,
            )
        }
    }

    /**
     * Apply a binding to the virtual gamepad state and send to WinHandler.
     * Extracted from InputControlsView.handleInputEvent()
     */
    // offset: analog axis value for presses; must be 0f for releases (triggers use offset > 0f
    // to determine pressed state, sticks gate on isActionDown, everything else ignores offset)
    private fun handleInputEvent(
        bindingCombo: BindingCombo,
        isActionDown: Boolean,
        offset: Float = 0f,
        fromMotion: Boolean = false,
        sourceKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        sourceDeviceId: Int = UNKNOWN_DEVICE_ID,
        sourceController: ExternalController? = null,
    ) {
        if (bindingCombo.isEmpty) return
        if (Binding.OPEN_RADIAL_MENU in bindingCombo.bindings) {
            handleInputEvent(
                Binding.OPEN_RADIAL_MENU,
                isActionDown,
                offset,
                fromMotion,
                sourceKeyCode,
                sourceDeviceId,
                sourceController,
            )
            return
        }
        if (bindingCombo.isSequence) {
            if (isActionDown) {
                performBindingSequence(
                    bindingCombo,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
            }
            return
        }

        if (isActionDown) {
            bindingCombo.bindings.forEach { binding ->
                handleInputEvent(
                    binding,
                    true,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
            }
        } else {
            bindingCombo.bindings.asReversed().forEach { binding ->
                handleInputEvent(
                    binding,
                    false,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
            }
        }
    }

    private fun performBindingSequence(
        bindingCombo: BindingCombo,
        offset: Float,
        fromMotion: Boolean,
        sourceKeyCode: Int,
        sourceDeviceId: Int,
        sourceController: ExternalController?,
    ) {
        val pressDurationMs = minOf(
            SEQUENCE_PRESS_MS,
            (bindingCombo.sequenceDelayMs - 1).coerceAtLeast(1).toLong(),
        )
        bindingCombo.bindings.forEachIndexed { index, binding ->
            sequenceHandler.postDelayed({
                handleInputEvent(
                    binding,
                    true,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
                activeSequenceBindings[binding] = (activeSequenceBindings[binding] ?: 0) + 1
                val sequenceSource = physicalInputSource(sourceDeviceId, sourceKeyCode, sourceController)
                if (binding == Binding.GYRO_MODIFIER) activeSequenceGyroSources.add(sequenceSource)
                sendGamepadState()
                sequenceHandler.postDelayed({
                    val activeCount = activeSequenceBindings[binding] ?: return@postDelayed
                    if (binding == Binding.GYRO_MODIFIER) {
                        activeSequenceGyroSources.remove(sequenceSource)
                        handleInputEvent(
                            binding,
                            false,
                            0f,
                            fromMotion,
                            sourceKeyCode,
                            sourceDeviceId,
                            sourceController,
                        )
                    }
                    if (activeCount > 1) {
                        activeSequenceBindings[binding] = activeCount - 1
                    } else {
                        activeSequenceBindings.remove(binding)
                        if (binding != Binding.GYRO_MODIFIER) {
                            handleInputEvent(
                                binding,
                                false,
                                0f,
                                fromMotion,
                                sourceKeyCode,
                                sourceDeviceId,
                                sourceController,
                            )
                        }
                        sendGamepadState()
                    }
                }, pressDurationMs)
            }, index * bindingCombo.sequenceDelayMs.toLong())
        }
    }

    private fun cancelActiveSequences() {
        sequenceHandler.removeCallbacksAndMessages(null)
        activeSequenceGyroSources.toList().forEach { source ->
            handleInputEvent(
                Binding.GYRO_MODIFIER,
                false,
                0f,
                sourceKeyCode = source.keyCode,
                sourceDeviceId = source.deviceId,
            )
        }
        activeSequenceGyroSources.clear()
        if (activeSequenceBindings.isEmpty()) {
            activeSequenceTriggerBindings.clear()
            return
        }
        activeSequenceBindings.keys.toList().asReversed().forEach { binding ->
            if (binding != Binding.GYRO_MODIFIER) handleInputEvent(binding, false, 0f)
        }
        activeSequenceBindings.clear()
        activeSequenceTriggerBindings.clear()
        sendGamepadState()
    }

    private fun handleInputEvent(
        binding: Binding,
        isActionDown: Boolean,
        offset: Float = 0f,
        fromMotion: Boolean = false,
        sourceKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        sourceDeviceId: Int = UNKNOWN_DEVICE_ID,
        sourceController: ExternalController? = null,
    ) {
        if (binding == Binding.NONE) return

        if (binding == Binding.GYRO_MODIFIER) {
            setGyroModifierPressed(sourceDeviceId, sourceKeyCode, sourceController, isActionDown)
            return
        }

        if (binding == Binding.OPEN_RADIAL_MENU) {
            if (!isActionDown && radialMenuPressed && !isRadialMenuOpenerDevice(sourceDeviceId)) return
            if (radialMenuPressed != isActionDown) {
                if (isActionDown) {
                    neutralizeMotionInputs(sourceKeyCode, sourceDeviceId, sourceController)
                } else if (fromMotion && sourceKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    activeAxisBindings.remove(
                        physicalInputSource(sourceDeviceId, sourceKeyCode, sourceController),
                    )
                }
                radialMenuPressed = isActionDown
                radialMenuOpenedFromMotion = isActionDown && fromMotion
                radialMenuOpenerKeyCode = if (isActionDown) sourceKeyCode else KeyEvent.KEYCODE_UNKNOWN
                radialMenuOpenerDeviceId = if (isActionDown) sourceDeviceId else UNKNOWN_DEVICE_ID
                onRadialMenuButtonStateChanged?.invoke(isActionDown, true)
                if (!isActionDown) onRadialMenuVectorChanged?.invoke(0f, 0f)
            } else if (!isActionDown) {
                radialMenuOpenedFromMotion = false
                radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
                radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID
            }
            return
        }

        if (binding.isGamepad) {
            val winHandler = xServer?.winHandler
            val state = profile?.gamepadState

            if (state != null) {
                val buttonIdx = binding.ordinal - Binding.GAMEPAD_BUTTON_A.ordinal
                if (buttonIdx <= ExternalController.IDX_BUTTON_R2.toInt()) {
                    when (buttonIdx) {
                        ExternalController.IDX_BUTTON_L2.toInt() -> {
                            val triggerValue = if (offset > 0f) offset else if (isActionDown) 1f else 0f
                            state.triggerL = triggerValue
                            state.setPressed(ExternalController.IDX_BUTTON_L2.toInt(), triggerValue > 0f)
                        }
                        ExternalController.IDX_BUTTON_R2.toInt() -> {
                            val triggerValue = if (offset > 0f) offset else if (isActionDown) 1f else 0f
                            state.triggerR = triggerValue
                            state.setPressed(ExternalController.IDX_BUTTON_R2.toInt(), triggerValue > 0f)
                        }
                        else -> state.setPressed(buttonIdx, isActionDown)
                    }
                }
                else {
                    when (binding) {
                        Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_DOWN -> {
                            state.thumbLY = gyroStickMixer?.invoke(binding, isActionDown, offset)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_LEFT_THUMB_LEFT, Binding.GAMEPAD_LEFT_THUMB_RIGHT -> {
                            state.thumbLX = gyroStickMixer?.invoke(binding, isActionDown, offset)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_DOWN -> {
                            state.thumbRY = gyroStickMixer?.invoke(binding, isActionDown, offset)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_LEFT, Binding.GAMEPAD_RIGHT_THUMB_RIGHT -> {
                            state.thumbRX = gyroStickMixer?.invoke(binding, isActionDown, offset)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_DPAD_UP  -> {
                            state.dpad[0] = isActionDown
                            if(isActionDown) {
                                state.dpad[Binding.GAMEPAD_DPAD_DOWN.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                            }
                        }
                        Binding.GAMEPAD_DPAD_DOWN -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                                state.dpad[0] = false
                            }
                        }
                       Binding.GAMEPAD_DPAD_LEFT -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                              state.dpad[Binding.GAMEPAD_DPAD_RIGHT.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                          }
                        }
                        Binding.GAMEPAD_DPAD_RIGHT -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                                state.dpad[Binding.GAMEPAD_DPAD_LEFT.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                            }
                        }
                        else -> {}
                    }
                }

                if (winHandler != null) {
                    val controller = winHandler.getCurrentController()
                    if (controller != null) {
                        controller.state.copy(state)
                    }
                }
            }
        } else {
            // Handle special bindings
            if (binding == Binding.OPEN_NAVIGATION_MENU) {
                if (isActionDown) {
                    Log.d(TAG, "Opening navigation menu from controller binding")
                    onOpenNavigationMenu?.invoke()
                }
            } else if (binding == Binding.SHOW_KEYBOARD) {
                if (isActionDown) {
                    if (!showKeyboardPressed) {
                        showKeyboardPressed = true
                        Log.d(TAG, "Showing keyboard from controller binding")
                        onShowKeyboard?.invoke()
                    }
                } else {
                    showKeyboardPressed = false
                }
            } else if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                updateMouseMoveContribution(binding, isActionDown, offset, sourceKeyCode, sourceDeviceId)
            } else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                updateMouseMoveContribution(binding, isActionDown, offset, sourceKeyCode, sourceDeviceId)
            } else if (handleScrollBinding(binding, isActionDown)) {
                // Mouse wheel events are pulses, not held button state.
            } else {
                // For keyboard/mouse button bindings, inject into XServer
                val pointerButton = binding.pointerButton
                if (isActionDown) {
                    if (pointerButton != null) {
                        xServer?.injectPointerButtonPress(pointerButton)
                    } else {
                        xServer?.let { binding.inject(it, true) }
                    }
                } else {
                    if (pointerButton != null) {
                        xServer?.injectPointerButtonRelease(pointerButton)
                    } else {
                        xServer?.let { binding.inject(it, false) }
                    }
                }
            }
        }
    }

    private fun setGyroModifierPressed(
        sourceDeviceId: Int,
        sourceKeyCode: Int,
        sourceController: ExternalController?,
        pressed: Boolean,
    ) {
        val source = physicalInputSource(sourceDeviceId, sourceKeyCode, sourceController)
        val changed = if (pressed) {
            activeGyroModifierSources.add(source)
        } else {
            activeGyroModifierSources.remove(source)
        }
        if (changed) onGyroModifierChanged?.invoke(source, pressed)
    }

    private fun physicalInputSource(
        sourceDeviceId: Int,
        sourceKeyCode: Int,
        sourceController: ExternalController?,
    ): PhysicalInputSource {
        val resolvedDeviceId = if (sourceDeviceId != UNKNOWN_DEVICE_ID) {
            sourceDeviceId
        } else {
            sourceController?.deviceId ?: UNKNOWN_DEVICE_ID
        }
        return PhysicalInputSource(resolvedDeviceId, sourceKeyCode)
    }

    private fun releaseGyroModifierSources(deviceId: Int? = null) {
        activeGyroModifierSources.toList().forEach { source ->
            if (deviceId == null || source.deviceId == deviceId) {
                activeGyroModifierSources.remove(source)
                onGyroModifierChanged?.invoke(source, false)
            }
        }
    }

    private fun closeRadialMenuIfOpen(commit: Boolean) {
        if (!radialMenuPressed) return
        radialMenuPressed = false
        radialMenuOpenedFromMotion = false
        radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
        radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID
        onRadialMenuVectorChanged?.invoke(0f, 0f)
        onRadialMenuButtonStateChanged?.invoke(false, commit)
    }

    private fun neutralizeMotionInputs(
        exceptKeyCode: Int,
        sourceDeviceId: Int,
        controller: ExternalController?,
    ) {
        val exceptSource = if (exceptKeyCode == KeyEvent.KEYCODE_UNKNOWN) {
            null
        } else {
            physicalInputSource(sourceDeviceId, exceptKeyCode, controller)
        }
        releaseActiveAxes(exceptSource)
        neutralizeTrigger(controller, KeyEvent.KEYCODE_BUTTON_L2, exceptKeyCode)
        neutralizeTrigger(controller, KeyEvent.KEYCODE_BUTTON_R2, exceptKeyCode)
        clearMouseMoveContributions()
        clearScrollRepeats()
    }

    private fun neutralizeTrigger(controller: ExternalController?, keyCode: Int, exceptKeyCode: Int) {
        if (keyCode == exceptKeyCode) return
        val activeController = controller ?: profile?.getController("*") ?: return
        val triggerValue = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> activeController.state.triggerL
            KeyEvent.KEYCODE_BUTTON_R2 -> activeController.state.triggerR
            else -> 0f
        }
        if (triggerValue <= 0f) return
        activeController.getControllerBinding(keyCode)
            ?.takeIf { Binding.OPEN_RADIAL_MENU !in it.bindingCombo.bindings }
            ?.let {
                handleInputEvent(
                    it.bindingCombo,
                    false,
                    0f,
                    fromMotion = true,
                    sourceKeyCode = keyCode,
                    sourceController = activeController,
                )
            }
    }

    private fun isRadialMenuOpenerDevice(deviceId: Int): Boolean {
        return radialMenuOpenerDeviceId == UNKNOWN_DEVICE_ID || deviceId == radialMenuOpenerDeviceId
    }

    private fun handleRadialMenuNavigationKey(event: KeyEvent, keyCode: Int): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> true
                else -> false
            }
        }
        val vector = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0f to -1f
            KeyEvent.KEYCODE_DPAD_DOWN -> 0f to 1f
            KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
            else -> return false
        }
        onRadialMenuVectorChanged?.invoke(vector.first, vector.second)
        return true
    }

    private fun updateRadialMenuVector(controller: ExternalController) {
        val vector = radialSelectionVectorForAxes(
            controller.state.thumbLX,
            controller.state.thumbLY,
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
        ) ?: radialSelectionVectorForAxes(
            controller.state.thumbRX,
            controller.state.thumbRY,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
        ) ?: radialSelectionVectorForAxes(
            controller.state.dPadX.toFloat(),
            controller.state.dPadY.toFloat(),
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
        )

        if (vector == null) {
            onRadialMenuVectorChanged?.invoke(0f, 0f)
        } else {
            onRadialMenuVectorChanged?.invoke(vector.first, vector.second)
        }
    }

    private fun radialSelectionVectorForAxes(
        x: Float,
        y: Float,
        xAxis: Int,
        yAxis: Int,
    ): Pair<Float, Float>? {
        val filteredX = radialSelectionComponent(x, xAxis)
        val filteredY = radialSelectionComponent(y, yAxis)
        return if (Math.abs(filteredX) <= ControlElement.STICK_DEAD_ZONE &&
            Math.abs(filteredY) <= ControlElement.STICK_DEAD_ZONE
        ) {
            null
        } else {
            filteredX to filteredY
        }
    }

    private fun radialSelectionComponent(value: Float, axis: Int): Float {
        if (Math.abs(value) <= ControlElement.STICK_DEAD_ZONE) return 0f
        val keyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, Mathf.sign(value))
        return if (keyCode == radialMenuOpenerKeyCode) 0f else value
    }

    private fun isRadialMenuMotionOpenerPressed(controller: ExternalController): Boolean {
        val axes = intArrayOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
        )
        val values = floatArrayOf(
            controller.state.thumbLX,
            controller.state.thumbLY,
            controller.state.thumbRX,
            controller.state.thumbRY,
            controller.state.dPadX.toFloat(),
            controller.state.dPadY.toFloat(),
        )

        for (i in axes.indices) {
            if (Math.abs(values[i]) <= ControlElement.STICK_DEAD_ZONE) continue
            val keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i]))
            if (keyCode == radialMenuOpenerKeyCode) {
                return true
            }
        }

        return (radialMenuOpenerKeyCode == KeyEvent.KEYCODE_BUTTON_L2 && controller.state.triggerL > 0f) ||
            (radialMenuOpenerKeyCode == KeyEvent.KEYCODE_BUTTON_R2 && controller.state.triggerR > 0f)
    }
}
