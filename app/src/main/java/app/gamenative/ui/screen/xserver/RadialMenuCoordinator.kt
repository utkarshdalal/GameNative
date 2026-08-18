package app.gamenative.ui.screen.xserver

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.findViewTreeCompositionContext
import app.gamenative.PluviaApp
import app.gamenative.ui.component.dialog.RadialMenuSettingsContent
import app.gamenative.ui.theme.PluviaTheme
import com.winlator.container.Container
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.inputcontrols.RadialMenu
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.xserver.XServer
import timber.log.Timber
import kotlin.math.atan2
import kotlin.math.sqrt

class RadialMenuCoordinator(
    private val context: Context,
    private val host: ViewGroup,
    private val anchor: View,
    private val container: Container,
    private val xServer: XServer,
    private val gameNameProvider: () -> String,
    private val showKeyboard: (View, String) -> Unit,
    private val openQuickMenu: () -> Unit,
    private val onSettingsVisibilityChanged: (Boolean) -> Unit,
) : InputControlsView.RadialMenuListener {
    companion object {
        fun install(
            context: Context,
            host: ViewGroup,
            anchor: View,
            container: Container,
            xServer: XServer,
            gameNameProvider: () -> String,
            showKeyboard: (View, String) -> Unit,
            openQuickMenu: () -> Unit,
            onSettingsVisibilityChanged: (Boolean) -> Unit,
        ): RadialMenuCoordinator {
            PluviaApp.radialMenuCoordinator?.detach()
            return RadialMenuCoordinator(
                context = context,
                host = host,
                anchor = anchor,
                container = container,
                xServer = xServer,
                gameNameProvider = gameNameProvider,
                showKeyboard = showKeyboard,
                openQuickMenu = openQuickMenu,
                onSettingsVisibilityChanged = onSettingsVisibilityChanged,
            ).also { coordinator ->
                coordinator.bindTouchpadView(PluviaApp.touchpadView)
                PluviaApp.radialMenuCoordinator = coordinator
            }
        }
    }

    private val overlayView = RadialMenuOverlayView(context).apply {
        listener = object : RadialMenuOverlayView.Listener {
            override fun onPointerPosition(x: Float, y: Float) {
                updateSelection(PointF(x, y))
            }

            override fun onPointerRelease(commit: Boolean) {
                close(commit)
            }
        }
    }

    private var activeControlsProfile: ControlsProfile? = null
    private var inputControlsView: InputControlsView? = null
    private var touchpadView: TouchpadView? = null
    private var physicalControllerHandler: PhysicalControllerHandler? = null
    private var settingsDialog: Dialog? = null
    private var inputControlSelectionActive = false
    private var activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
    private val wheelCenter = PointF()

    init {
        host.addView(
            overlayView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun detach() {
        close(commit = false)
        inputControlsView?.setRadialMenuListener(null)
        touchpadView?.setOpenRadialMenuCallback(null)
        settingsDialog?.dismiss()
        settingsDialog = null
        if (overlayView.parent === host) {
            host.removeView(overlayView)
        }
    }

    fun bindInputControlsView(view: InputControlsView?) {
        inputControlsView?.setRadialMenuListener(null)
        inputControlsView = view
        view?.let { overlayView.setControlsStyle(it.primaryColor, it.secondaryColor) }
        view?.setRadialMenuListener(this)
    }

    fun bindTouchpadView(view: TouchpadView?) {
        touchpadView?.setOpenRadialMenuCallback(null)
        touchpadView = view
        view?.setOpenRadialMenuCallback { x, y ->
            view.post { openFromGesture(view, x, y) }
        }
    }

    fun bindPhysicalControllerHandler(handler: PhysicalControllerHandler?) {
        physicalControllerHandler = handler
    }

    fun setProfile(profile: ControlsProfile?) {
        activeControlsProfile = profile
    }

    fun showSettingsDialog(): Boolean {
        val profile = getOrCreateGameControlsProfile() ?: return false
        val existingDialog = settingsDialog
        if (existingDialog?.isShowing == true) {
            return true
        }

        onSettingsVisibilityChanged(true)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        if (!prepareComposeDialogHost(composeView)) {
            Timber.w("Unable to find Compose host owners for radial menu settings dialog")
            onSettingsVisibilityChanged(false)
            return false
        }
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(composeView)
            setOnDismissListener {
                if (settingsDialog === this) {
                    settingsDialog = null
                    onSettingsVisibilityChanged(false)
                }
            }
        }
        settingsDialog = dialog
        composeView.setContent {
            PluviaTheme {
                RadialMenuSettingsContent(
                    profile = profile,
                    onDismiss = { dialog.dismiss() },
                    onSave = {
                        applyProfile(profile)
                        dialog.dismiss()
                    },
                )
            }
        }
        try {
            dialog.show()
        } catch (e: RuntimeException) {
            Timber.e(e, "Failed to show radial menu settings dialog")
            if (settingsDialog === dialog) settingsDialog = null
            onSettingsVisibilityChanged(false)
            return false
        }
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        return true
    }

    private fun prepareComposeDialogHost(composeView: ComposeView): Boolean {
        val hasParentComposition = anchor.findViewTreeCompositionContext()?.let { compositionContext ->
            composeView.setParentCompositionContext(compositionContext)
            true
        } ?: false

        val hasLifecycleOwner = copyViewTreeOwner(
            className = "androidx.lifecycle.ViewTreeLifecycleOwner",
            target = composeView,
        )
        copyViewTreeOwner(
            className = "androidx.lifecycle.ViewTreeViewModelStoreOwner",
            target = composeView,
        )
        copyViewTreeOwner(
            className = "androidx.savedstate.ViewTreeSavedStateRegistryOwner",
            target = composeView,
        )

        return hasParentComposition || hasLifecycleOwner
    }

    private fun copyViewTreeOwner(className: String, target: View): Boolean {
        return try {
            val ownerClass = Class.forName(className)
            val owner = ownerClass.getMethod("get", View::class.java).invoke(null, anchor) ?: return false
            val setMethod = ownerClass.methods.firstOrNull { method ->
                method.name == "set" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == View::class.java &&
                    method.parameterTypes[1].isAssignableFrom(owner.javaClass)
            } ?: return false
            setMethod.invoke(null, target, owner)
            true
        } catch (e: ReflectiveOperationException) {
            Timber.d(e, "Unable to copy $className for radial menu settings dialog")
            false
        } catch (e: LinkageError) {
            Timber.d(e, "Unable to link $className for radial menu settings dialog")
            false
        }
    }

    override fun onRadialMenuTouchStart(pointerId: Int, x: Float, y: Float) {
        inputControlsView?.let { view ->
            inputControlSelectionActive = true
            activeTouchPointerId = pointerId
            openAt(pointFromView(view, x, y))
        }
    }

    override fun onRadialMenuTouchMove(pointerId: Int, x: Float, y: Float) {
        if (pointerId != activeTouchPointerId) return
        inputControlsView?.let { view ->
            updateSelection(pointFromView(view, x, y))
        }
    }

    override fun onRadialMenuTouchEnd(pointerId: Int, commit: Boolean) {
        if (pointerId != activeTouchPointerId) return
        inputControlSelectionActive = false
        activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
        close(commit)
    }

    override fun onRadialMenuButtonStateChanged(isDown: Boolean) {
        onRadialMenuButtonStateChanged(isDown, commit = true)
    }

    fun onRadialMenuButtonStateChanged(isDown: Boolean, commit: Boolean) {
        if (isDown) openCentered() else close(commit)
    }

    fun onRadialMenuVectorChanged(x: Float, y: Float) {
        if (!isVisible()) return
        val magnitude = sqrt(x * x + y * y)
        if (magnitude < 0.25f) {
            overlayView.setSelectedIndex(-1)
            return
        }
        updateSelection(
            PointF(
                wheelCenter.x + x * overlayView.radiusPx,
                wheelCenter.y + y * overlayView.radiusPx,
            ),
        )
    }

    fun onHostTouchEvent(event: MotionEvent): Boolean {
        if (!isVisible()) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                if (inputControlSelectionActive) {
                    false
                } else if (activeTouchPointerId == MotionEvent.INVALID_POINTER_ID ||
                    event.findPointerIndex(activeTouchPointerId) < 0
                ) {
                    claimHostPointer(event, event.actionIndex)
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activeTouchPointerId).takeIf { it >= 0 } ?: return false
                updateSelection(pointFromEventPointer(event, pointerIndex))
                !inputControlSelectionActive
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                if (event.getPointerId(event.actionIndex) != activeTouchPointerId) return false
                updateSelection(pointFromEventPointer(event, event.actionIndex))
                if (!inputControlSelectionActive) close(commit = true)
                false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!inputControlSelectionActive) close(commit = false)
                false
            }
            else -> false
        }
    }

    private fun getOrCreateGameControlsProfile(): ControlsProfile? {
        val manager = PluviaApp.inputControlsManager ?: InputControlsManager(context).also {
            PluviaApp.inputControlsManager = it
        }
        val profileId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
        var profile = if (profileId != 0) manager.getProfile(profileId) else null
        if (profile == null) {
            val allProfiles = manager.getProfiles(false)
            val sourceProfile = manager.getProfile(0)
                ?: allProfiles.firstOrNull { it.id == 2 }
                ?: allProfiles.firstOrNull()
            if (sourceProfile != null) {
                profile = try {
                    val duplicate = manager.duplicateProfile(sourceProfile)
                    duplicate.setName("${gameNameProvider()} - Controls")
                    duplicate.save()
                    container.putExtra("profileId", duplicate.id.toString())
                    container.saveData()
                    duplicate
                } catch (e: Exception) {
                    Timber.e(e, "Failed to auto-create controls profile for ${container.name}")
                    null
                }
            }
        }
        applyProfile(profile)
        return profile
    }

    private fun applyProfile(profile: ControlsProfile?) {
        activeControlsProfile = profile
        if (profile != null) {
            if (inputControlsView?.profile != null) {
                inputControlsView?.setProfile(profile)
            }
            physicalControllerHandler?.setProfile(profile)
        }
    }

    private fun activeProfile(): ControlsProfile? {
        inputControlsView?.profile?.let { return it }
        activeControlsProfile?.let { return it }

        val manager = PluviaApp.inputControlsManager ?: return null
        val profileId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
        val profile = if (profileId != 0) manager.getProfile(profileId) else null
        return profile ?: manager.getProfile(0) ?: manager.getProfiles(false).firstOrNull()
    }

    private fun activeMenu(): RadialMenu? {
        return activeProfile()?.defaultRadialMenu
    }

    private fun openCentered() {
        inputControlSelectionActive = false
        activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
        val width = host.width.takeIf { it > 0 } ?: anchor.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val height = host.height.takeIf { it > 0 } ?: anchor.height.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        openAt(PointF(width * 0.5f, height * 0.5f))
    }

    private fun openFromGesture(sourceView: View, x: Float, y: Float) {
        inputControlSelectionActive = false
        activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
        openAt(pointFromView(sourceView, x, y))
    }

    private fun openAt(point: PointF) {
        val menu = activeMenu() ?: return
        if (menu.enabledSlots.isEmpty()) return
        inputControlsView?.let { overlayView.setControlsStyle(it.primaryColor, it.secondaryColor) }
        val center = clampCenter(point)
        wheelCenter.set(center.x, center.y)
        overlayView.show(menu, center.x, center.y)
    }

    private fun close(commit: Boolean) {
        if (!isVisible()) {
            activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
            inputControlSelectionActive = false
            return
        }
        val slots = activeMenu()?.enabledSlots.orEmpty()
        val selectedIndex = overlayView.selectedIndex()
        val selectedBinding = if (commit && selectedIndex in slots.indices) {
            slots[selectedIndex].binding
        } else {
            Binding.NONE
        }
        overlayView.hide()
        activeTouchPointerId = MotionEvent.INVALID_POINTER_ID
        inputControlSelectionActive = false
        if (selectedBinding != Binding.NONE) dispatchBinding(selectedBinding)
    }

    private fun updateSelection(point: PointF) {
        if (!isVisible()) return
        overlayView.setSelectedIndex(slotIndexAt(point))
    }

    private fun isVisible(): Boolean = overlayView.visibility == View.VISIBLE

    private fun pointFromView(sourceView: View, x: Float, y: Float): PointF {
        val sourceLocation = IntArray(2)
        val hostLocation = IntArray(2)
        sourceView.getLocationOnScreen(sourceLocation)
        host.getLocationOnScreen(hostLocation)
        return PointF(
            sourceLocation[0] - hostLocation[0] + x,
            sourceLocation[1] - hostLocation[1] + y,
        )
    }

    private fun claimHostPointer(event: MotionEvent, pointerIndex: Int) {
        if (pointerIndex < 0 || pointerIndex >= event.pointerCount) return
        activeTouchPointerId = event.getPointerId(pointerIndex)
        updateSelection(pointFromEventPointer(event, pointerIndex))
    }

    private fun pointFromEventPointer(event: MotionEvent, pointerIndex: Int): PointF {
        val hostLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        val rawX = event.rawX + (event.getX(pointerIndex) - event.x)
        val rawY = event.rawY + (event.getY(pointerIndex) - event.y)
        return PointF(
            rawX - hostLocation[0],
            rawY - hostLocation[1],
        )
    }

    private fun clampCenter(point: PointF): PointF {
        val width = host.width.takeIf { it > 0 }?.toFloat()
            ?: context.resources.displayMetrics.widthPixels.toFloat()
        val height = host.height.takeIf { it > 0 }?.toFloat()
            ?: context.resources.displayMetrics.heightPixels.toFloat()
        val margin = overlayView.radiusPx + 8f * context.resources.displayMetrics.density
        fun clampAxis(value: Float, size: Float): Float {
            return if (size <= margin * 2f) size * 0.5f else value.coerceIn(margin, size - margin)
        }
        return PointF(clampAxis(point.x, width), clampAxis(point.y, height))
    }

    private fun slotIndexAt(point: PointF): Int {
        val slots = activeMenu()?.enabledSlots.orEmpty()
        if (slots.isEmpty()) return -1
        val dx = point.x - wheelCenter.x
        val dy = point.y - wheelCenter.y
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < overlayView.innerRadiusPx) return -1

        val sweep = 360f / slots.size
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val normalized = (angle + 90f + 360f) % 360f
        return (((normalized + sweep / 2f) / sweep).toInt() % slots.size)
    }

    private fun dispatchBinding(binding: Binding) {
        if (binding == Binding.NONE || binding == Binding.OPEN_RADIAL_MENU) return
        val offset = bindingOffset(binding)
        applyBinding(binding, true, offset)
        host.postDelayed({
            applyBinding(binding, false, 0f)
        }, 70L)
    }

    private fun bindingOffset(binding: Binding): Float {
        return when (binding) {
            Binding.GAMEPAD_LEFT_THUMB_UP,
            Binding.GAMEPAD_LEFT_THUMB_LEFT,
            Binding.GAMEPAD_RIGHT_THUMB_UP,
            Binding.GAMEPAD_RIGHT_THUMB_LEFT,
            -> -1f
            Binding.GAMEPAD_LEFT_THUMB_DOWN,
            Binding.GAMEPAD_LEFT_THUMB_RIGHT,
            Binding.GAMEPAD_RIGHT_THUMB_DOWN,
            Binding.GAMEPAD_RIGHT_THUMB_RIGHT,
            Binding.GAMEPAD_BUTTON_L2,
            Binding.GAMEPAD_BUTTON_R2,
            -> 1f
            else -> 0f
        }
    }

    private fun applyBinding(binding: Binding, isActionDown: Boolean, offset: Float = 0f) {
        if (binding == Binding.NONE || binding == Binding.OPEN_RADIAL_MENU) return
        if (binding == Binding.OPEN_NAVIGATION_MENU) {
            if (isActionDown) openQuickMenu()
            return
        }
        if (binding == Binding.SHOW_KEYBOARD) {
            if (isActionDown) showKeyboard(inputControlsView ?: host, "onscreen_keyboard_enabled_from_radial_menu")
            return
        }

        val view = inputControlsView
        if (view?.profile != null) {
            view.handleInputEvent(binding, isActionDown, offset)
            if (binding.isGamepad) {
                sendCurrentGamepadState(view.profile)
            }
            return
        }

        if (binding.isGamepad) {
            sendGamepadState(binding, isActionDown, offset)
            return
        }

        when (binding) {
            Binding.MOUSE_MOVE_LEFT,
            Binding.MOUSE_MOVE_RIGHT,
            Binding.MOUSE_MOVE_UP,
            Binding.MOUSE_MOVE_DOWN,
            -> {
                if (!isActionDown) return
                val dx = when (binding) {
                    Binding.MOUSE_MOVE_LEFT -> -24
                    Binding.MOUSE_MOVE_RIGHT -> 24
                    else -> 0
                }
                val dy = when (binding) {
                    Binding.MOUSE_MOVE_UP -> -24
                    Binding.MOUSE_MOVE_DOWN -> 24
                    else -> 0
                }
                xServer.injectPointerMoveDelta(dx, dy)
            }
            else -> {
                val pointerButton = binding.pointerButton
                if (isActionDown) {
                    if (pointerButton != null) xServer.injectPointerButtonPress(pointerButton)
                    else binding.inject(xServer, true)
                } else {
                    if (pointerButton != null) xServer.injectPointerButtonRelease(pointerButton)
                    else binding.inject(xServer, false)
                }
            }
        }
    }

    private fun sendGamepadState(binding: Binding, isActionDown: Boolean, offset: Float) {
        val profile = activeProfile() ?: return
        val state = profile.gamepadState
        val winHandler = xServer.winHandler ?: PluviaApp.xServerView?.getxServer()?.winHandler

        val buttonIdx = binding.ordinal - Binding.GAMEPAD_BUTTON_A.ordinal
        if (buttonIdx <= ExternalController.IDX_BUTTON_R2.toInt()) {
            when (buttonIdx) {
                ExternalController.IDX_BUTTON_L2.toInt() -> {
                    state.triggerL = if (isActionDown) offset else 0f
                    state.setPressed(ExternalController.IDX_BUTTON_L2.toInt(), isActionDown)
                }
                ExternalController.IDX_BUTTON_R2.toInt() -> {
                    state.triggerR = if (isActionDown) offset else 0f
                    state.setPressed(ExternalController.IDX_BUTTON_R2.toInt(), isActionDown)
                }
                else -> state.setPressed(buttonIdx, isActionDown)
            }
        } else {
            when (binding) {
                Binding.GAMEPAD_LEFT_THUMB_UP,
                Binding.GAMEPAD_LEFT_THUMB_DOWN,
                -> state.thumbLY = if (isActionDown) offset else 0f
                Binding.GAMEPAD_LEFT_THUMB_LEFT,
                Binding.GAMEPAD_LEFT_THUMB_RIGHT,
                -> state.thumbLX = if (isActionDown) offset else 0f
                Binding.GAMEPAD_RIGHT_THUMB_UP,
                Binding.GAMEPAD_RIGHT_THUMB_DOWN,
                -> state.thumbRY = if (isActionDown) offset else 0f
                Binding.GAMEPAD_RIGHT_THUMB_LEFT,
                Binding.GAMEPAD_RIGHT_THUMB_RIGHT,
                -> state.thumbRX = if (isActionDown) offset else 0f
                Binding.GAMEPAD_DPAD_UP,
                Binding.GAMEPAD_DPAD_RIGHT,
                Binding.GAMEPAD_DPAD_DOWN,
                Binding.GAMEPAD_DPAD_LEFT,
                -> state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                else -> Unit
            }
        }

        winHandler?.currentController?.state?.copy(state)
        winHandler?.sendGamepadState()
        winHandler?.sendVirtualGamepadState(state)
    }

    private fun sendCurrentGamepadState(profile: ControlsProfile?) {
        val state = profile?.gamepadState ?: return
        val winHandler = xServer.winHandler ?: PluviaApp.xServerView?.getxServer()?.winHandler
        winHandler?.currentController?.state?.copy(state)
        winHandler?.sendGamepadState()
        winHandler?.sendVirtualGamepadState(state)
    }
}
