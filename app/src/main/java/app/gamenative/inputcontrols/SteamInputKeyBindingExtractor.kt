package app.gamenative.inputcontrols

import app.gamenative.steamcontroller.GyroMode
import app.gamenative.steamcontroller.PadMode
import app.gamenative.steamcontroller.ScConfig
import app.gamenative.steamcontroller.ScOutput
import app.gamenative.steamcontroller.ScProfile
import app.gamenative.steamcontroller.StickMode
import app.gamenative.steamcontroller.TriggerMode
import app.gamenative.utils.SteamControllerProfileImporter
import com.winlator.inputcontrols.Binding
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

/**
 * Pulls keyboard/mouse bindings out of a Steam Input config so on-screen layouts can
 * mirror the game's resolved VDF (WASD + E + mouse look) instead of a generic pad.
 *
 * Returns null when the config is gamepad-only (no [ScOutput.Key] bindings).
 */
object SteamInputKeyBindingExtractor {
    data class LabeledBinding(
        val label: String,
        val binding: String,
    )

    data class DiscoveredKeys(
        /** Binding names in ControlElement order: Up, Right, Down, Left. */
        val move: List<String>? = null,
        val actions: List<LabeledBinding> = emptyList(),
        val hasMouseLook: Boolean = false,
    ) {
        val hasKeyboardKeys: Boolean
            get() = !move.isNullOrEmpty() || actions.any { it.binding.startsWith("KEY_") }
    }

    fun extractFromVdf(vdfText: String): DiscoveredKeys? {
        if (vdfText.isBlank()) return null
        return runCatching {
            extract(SteamControllerProfileImporter.importConfig(vdfText))
        }.getOrNull()
    }

    fun extract(config: ScConfig): DiscoveredKeys? {
        val profile = config.defaultProfile()
        return extract(profile)
    }

    fun extract(profile: ScProfile): DiscoveredKeys? {
        val move = extractMove(profile)
        val actions = LinkedHashMap<String, LabeledBinding>()
        collectButtonKeys(profile, actions)
        collectTriggerKeys(profile, actions)
        // Movement keys shouldn't also appear as face-button duplicates on the overlay.
        move?.forEach { actions.remove(it) }

        val discovered = DiscoveredKeys(
            move = move,
            actions = actions.values.toList(),
            hasMouseLook = hasMouseLook(profile),
        )
        return discovered.takeIf { it.hasKeyboardKeys }
    }

    private fun extractMove(profile: ScProfile): List<String>? {
        dpadBindings(profile.leftStick)?.let { return it }
        dpadBindings(profile.leftPad)?.let { return it }
        dpadBindings(profile.rightPad)?.let { return it }
        return null
    }

    private fun dpadBindings(mode: StickMode): List<String>? = when (mode) {
        is StickMode.DPad -> directionalKeys(mode.up, mode.right, mode.down, mode.left)
        else -> null
    }

    private fun dpadBindings(mode: PadMode): List<String>? = when (mode) {
        is PadMode.DPad -> directionalKeys(mode.up, mode.right, mode.down, mode.left)
        is PadMode.DirectionalSwipe -> directionalKeys(mode.up, mode.right, mode.down, mode.left)
        else -> null
    }

    private fun directionalKeys(up: ScOutput, right: ScOutput, down: ScOutput, left: ScOutput): List<String>? {
        val u = singleKeyBinding(up) ?: return null
        val r = singleKeyBinding(right) ?: return null
        val d = singleKeyBinding(down) ?: return null
        val l = singleKeyBinding(left) ?: return null
        return listOf(u, r, d, l)
    }

    private fun collectButtonKeys(profile: ScProfile, out: LinkedHashMap<String, LabeledBinding>) {
        profile.buttons.values.forEach { binding ->
            when (val output = binding.output) {
                is ScOutput.Key -> output.keys.firstOrNull()?.let { addKey(out, it) }
                is ScOutput.MouseButton -> mouseBinding(output.button)?.let { addBinding(out, it) }
                else -> Unit
            }
        }
    }

    private fun collectTriggerKeys(profile: ScProfile, out: LinkedHashMap<String, LabeledBinding>) {
        listOf(profile.leftTrigger, profile.rightTrigger).forEach { mode ->
            when (mode) {
                is TriggerMode.Staged -> {
                    addOutput(out, mode.soft)
                    addOutput(out, mode.full)
                }
                else -> Unit
            }
        }
    }

    private fun addOutput(out: LinkedHashMap<String, LabeledBinding>, output: ScOutput) {
        when (output) {
            is ScOutput.Key -> output.keys.firstOrNull()?.let { addKey(out, it) }
            is ScOutput.MouseButton -> mouseBinding(output.button)?.let { addBinding(out, it) }
            else -> Unit
        }
    }

    private fun hasMouseLook(profile: ScProfile): Boolean {
        if (profile.rightStick is StickMode.Mouse || profile.rightStick is StickMode.FlickStick) return true
        if (profile.leftStick is StickMode.Mouse || profile.leftStick is StickMode.FlickStick) return true
        if (profile.rightPad is PadMode.Mouse ||
            profile.rightPad is PadMode.MouseJoystick ||
            profile.rightPad is PadMode.AbsoluteMouse
        ) {
            return true
        }
        if (profile.leftPad is PadMode.Mouse ||
            profile.leftPad is PadMode.MouseJoystick ||
            profile.leftPad is PadMode.AbsoluteMouse
        ) {
            return true
        }
        if (profile.gyro is GyroMode.Mouse) return true
        return false
    }

    private fun singleKeyBinding(output: ScOutput): String? {
        val key = (output as? ScOutput.Key)?.keys?.singleOrNull() ?: return null
        return toBindingName(key)
    }

    private fun addKey(out: LinkedHashMap<String, LabeledBinding>, key: XKeycode) {
        val binding = toBindingName(key) ?: return
        addBinding(out, binding)
    }

    private fun addBinding(out: LinkedHashMap<String, LabeledBinding>, binding: String) {
        if (binding == Binding.NONE.name) return
        out.putIfAbsent(binding, LabeledBinding(labelFor(binding), binding))
    }

    private fun toBindingName(key: XKeycode): String? =
        runCatching { Binding.valueOf(key.name).name }.getOrNull()

    private fun mouseBinding(button: Pointer.Button): String? = when (button) {
        Pointer.Button.BUTTON_LEFT -> Binding.MOUSE_LEFT_BUTTON.name
        Pointer.Button.BUTTON_MIDDLE -> Binding.MOUSE_MIDDLE_BUTTON.name
        Pointer.Button.BUTTON_RIGHT -> Binding.MOUSE_RIGHT_BUTTON.name
        Pointer.Button.BUTTON_SCROLL_UP -> Binding.MOUSE_SCROLL_UP.name
        Pointer.Button.BUTTON_SCROLL_DOWN -> Binding.MOUSE_SCROLL_DOWN.name
        else -> null
    }

    private fun labelFor(binding: String): String = when (binding) {
        Binding.KEY_SPACE.name -> "SPC"
        Binding.KEY_SHIFT_L.name, Binding.KEY_SHIFT_R.name -> "SHF"
        Binding.KEY_CTRL_L.name, Binding.KEY_CTRL_R.name -> "CTL"
        Binding.KEY_ESC.name -> "ESC"
        Binding.KEY_TAB.name -> "TAB"
        Binding.MOUSE_LEFT_BUTTON.name -> "LMB"
        Binding.MOUSE_RIGHT_BUTTON.name -> "RMB"
        Binding.MOUSE_MIDDLE_BUTTON.name -> "MMB"
        Binding.MOUSE_SCROLL_UP.name -> "WH+"
        Binding.MOUSE_SCROLL_DOWN.name -> "WH-"
        else -> binding.removePrefix("KEY_").take(3)
    }
}
