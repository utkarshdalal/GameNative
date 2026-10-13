package app.gamenative.inputcontrols

import app.gamenative.enums.ControllerSupport
import com.winlator.inputcontrols.ControlsProfile
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds on-screen control profiles that emulate typical PC keyboard/mouse schemes
 * (WASD move, E interact, trackpad/mouse look) instead of a generic Xbox gamepad.
 */
object GameKeyboardLayoutFactory {
    enum class Style {
        /** WASD + action keys + right-side mouse trackpad (adventure / co-op / most PC games). */
        KEYBOARD_MOUSE,

        /** Built around SHOOTER_MODE (WASD move + touch look) plus fire/reload/sprint. */
        FPS,

        /** Arrow/select heavy layout for strategy/RTS. */
        RTS,

        /** Keep using a virtual Xbox-style gamepad. */
        GAMEPAD,
    }

    fun resolveStyle(
        tags: Collection<String>,
        controllerSupport: ControllerSupport? = null,
    ): Style {
        val joined = tags.joinToString(" ").lowercase(Locale.ROOT)
        if (FPS_MARKERS.any { joined.contains(it) }) return Style.FPS
        if (RTS_MARKERS.any { joined.contains(it) }) return Style.RTS
        // Full controller support often means the game expects a pad — keep Virtual Gamepad.
        if (controllerSupport == ControllerSupport.full &&
            !KEYBOARD_FIRST_MARKERS.any { joined.contains(it) }
        ) {
            return Style.GAMEPAD
        }
        return Style.KEYBOARD_MOUSE
    }

    fun buildWorkingProfileJson(
        profileId: Int,
        containerId: String,
        style: Style,
        gameName: String? = null,
        discovered: SteamInputKeyBindingExtractor.DiscoveredKeys? = null,
    ): JSONObject {
        require(style != Style.GAMEPAD) { "GAMEPAD style should use a built-in Virtual Gamepad profile" }
        val fromSteam = discovered?.hasKeyboardKeys == true
        val label = when (style) {
            Style.FPS -> if (fromSteam) "Auto FPS·Steam" else "Auto FPS"
            Style.RTS -> if (fromSteam) "Auto RTS·Steam" else "Auto RTS"
            Style.KEYBOARD_MOUSE -> if (fromSteam) "Auto KBM·Steam" else "Auto KBM"
            Style.GAMEPAD -> "Auto Gamepad"
        }
        val suffix = gameName?.take(24)?.let { " · $it" }.orEmpty()
        return JSONObject().apply {
            put("id", profileId)
            put("name", "$label$suffix")
            put("cursorSpeed", 1.0)
            put("elements", elementsFor(style, discovered))
            put("schemaVersion", ControlProfileService.SCHEMA_VERSION)
            put("listed", false)
            put("gameOwnerId", containerId)
            put(
                "includedSections",
                JSONArray().put(ControlProfileSection.ON_SCREEN.wireName),
            )
            put(ControlsProfile.KEY_AUTO_FIT_LAYOUT, true)
        }
    }

    private fun elementsFor(
        style: Style,
        discovered: SteamInputKeyBindingExtractor.DiscoveredKeys?,
    ): JSONArray = when (style) {
        Style.FPS -> fpsElements(discovered)
        Style.RTS -> rtsElements()
        Style.KEYBOARD_MOUSE -> keyboardMouseElements(discovered)
        Style.GAMEPAD -> JSONArray()
    }

    /** Classic PC layout: WASD pad, mouse look trackpad, E/F/Space/Shift/Ctrl/R + mouse buttons. */
    private fun keyboardMouseElements(
        discovered: SteamInputKeyBindingExtractor.DiscoveredKeys?,
    ): JSONArray = JSONArray().apply {
        val move = discovered?.move?.takeIf { it.size == 4 }
            ?: listOf("KEY_W", "KEY_D", "KEY_S", "KEY_A")
        put(
            element(
                type = "D_PAD",
                bindings = move,
                x = 0.12,
                y = 0.72,
                scale = 1.15,
                text = moveLabel(move),
            ),
        )
        put(
            element(
                type = "TRACKPAD",
                bindings = listOf(
                    "MOUSE_MOVE_UP",
                    "MOUSE_MOVE_RIGHT",
                    "MOUSE_MOVE_DOWN",
                    "MOUSE_MOVE_LEFT",
                ),
                x = 0.84,
                y = 0.48,
                scale = 1.7,
                text = "LOOK",
            ),
        )
        // Large primary click — right-thumb zone under look pad.
        put(button("LMB", "MOUSE_LEFT_BUTTON", 0.78, 0.72, scale = 1.55))
        put(button("RMB", "MOUSE_RIGHT_BUTTON", 0.90, 0.62, scale = 1.2))
        putActionButtons(
            defaults = listOf(
                Slot("E", "KEY_E", 0.68, 0.86, scale = 1.15),
                Slot("F", "KEY_F", 0.88, 0.84),
                Slot("SPC", "KEY_SPACE", 0.58, 0.88),
                Slot("SHF", "KEY_SHIFT_L", 0.05, 0.55, toggle = true),
                Slot("CTL", "KEY_CTRL_L", 0.05, 0.68, toggle = true),
                Slot("R", "KEY_R", 0.92, 0.88),
                Slot("Q", "KEY_Q", 0.58, 0.72),
                Slot("TAB", "KEY_TAB", 0.05, 0.22),
                Slot("ESC", "KEY_ESC", 0.95, 0.08),
                Slot("I", "KEY_I", 0.15, 0.22),
                Slot("M", "KEY_M", 0.25, 0.22),
            ),
            discovered = discovered,
            moveBindings = move.toSet() + setOf("MOUSE_LEFT_BUTTON", "MOUSE_RIGHT_BUTTON"),
        )
    }

    private fun fpsElements(
        discovered: SteamInputKeyBindingExtractor.DiscoveredKeys?,
    ): JSONArray = JSONArray().apply {
        val moveType = when (discovered?.move) {
            listOf("KEY_UP", "KEY_RIGHT", "KEY_DOWN", "KEY_LEFT") -> "arrow_keys"
            else -> "wasd"
        }
        put(
            element(
                type = "SHOOTER_MODE",
                bindings = listOf("NONE", "NONE", "NONE", "NONE"),
                x = 0.97,
                y = 0.93,
                scale = 1.0,
                extra = mapOf(
                    "shooterMovementType" to moveType,
                    "shooterLookType" to "mouse",
                    "shooterLookSensitivity" to 1.35,
                    "shooterJoystickSize" to 1.0,
                ),
            ),
        )
        // Large fire button for thumb; RMB stays smaller for aim/alt-fire.
        put(button("LMB", "MOUSE_LEFT_BUTTON", 0.88, 0.70, scale = 1.55))
        put(button("RMB", "MOUSE_RIGHT_BUTTON", 0.96, 0.58, scale = 1.15))
        putActionButtons(
            defaults = listOf(
                Slot("E", "KEY_E", 0.76, 0.80, scale = 1.1),
                Slot("F", "KEY_F", 0.84, 0.88),
                Slot("SPC", "KEY_SPACE", 0.94, 0.78),
                Slot("R", "KEY_R", 0.90, 0.88),
                Slot("SHF", "KEY_SHIFT_L", 0.04, 0.65, toggle = true),
                Slot("CTL", "KEY_CTRL_L", 0.04, 0.78, toggle = true),
                Slot("Q", "KEY_Q", 0.12, 0.55),
                Slot("ESC", "KEY_ESC", 0.97, 0.07),
                Slot("TAB", "KEY_TAB", 0.05, 0.20),
            ),
            discovered = discovered,
            moveBindings = discovered?.move?.toSet().orEmpty() +
                setOf("MOUSE_LEFT_BUTTON", "MOUSE_RIGHT_BUTTON"),
        )
        put(
            element(
                type = "RANGE_BUTTON",
                bindings = listOf("NONE", "NONE", "NONE", "NONE"),
                x = 0.97,
                y = 0.33,
                scale = 1.0,
                extra = mapOf(
                    "range" to "FROM_0_TO_9",
                    "orientation" to 1,
                    "scrollLocked" to true,
                ),
            ),
        )
    }

    private fun JSONArray.putActionButtons(
        defaults: List<Slot>,
        discovered: SteamInputKeyBindingExtractor.DiscoveredKeys?,
        moveBindings: Set<String>,
    ) {
        val byBinding = discovered?.actions
            ?.filter { it.binding !in moveBindings }
            ?.associateBy { it.binding }
            .orEmpty()
        val placed = mutableSetOf<String>()
        defaults.forEach { slot ->
            val override = byBinding[slot.binding]
            val label = override?.label ?: slot.text
            put(button(label, slot.binding, slot.x, slot.y, slot.scale, slot.toggle))
            placed += slot.binding
        }
        // Extra Steam keys that aren't in the default cluster (max 4 to avoid clutter).
        val extras = byBinding.values.filter { it.binding !in placed }.take(4)
        val extraSlots = listOf(
            0.60 to 0.22,
            0.70 to 0.22,
            0.35 to 0.22,
            0.45 to 0.22,
        )
        extras.forEachIndexed { index, action ->
            val (x, y) = extraSlots[index]
            put(button(action.label, action.binding, x, y))
        }
    }

    private data class Slot(
        val text: String,
        val binding: String,
        val x: Double,
        val y: Double,
        val scale: Double = 1.0,
        val toggle: Boolean = false,
    )

    private fun moveLabel(move: List<String>): String {
        if (move == listOf("KEY_W", "KEY_D", "KEY_S", "KEY_A")) return "WASD"
        if (move == listOf("KEY_UP", "KEY_RIGHT", "KEY_DOWN", "KEY_LEFT")) return "ARROWS"
        return "MOVE"
    }

    private fun rtsElements(): JSONArray = JSONArray().apply {
        put(button("↑", "KEY_UP", 0.13, 0.49))
        put(button("↓", "KEY_DOWN", 0.13, 0.76))
        put(button("←", "KEY_LEFT", 0.07, 0.62))
        put(button("→", "KEY_RIGHT", 0.19, 0.62))
        put(button("ESC", "KEY_ESC", 0.07, 0.36))
        put(button("DEL", "KEY_DEL", 0.13, 0.36))
        put(button("TAB", "KEY_TAB", 0.07, 0.49))
        put(button("SPC", "KEY_SPACE", 0.88, 0.78))
        put(button("E", "KEY_E", 0.78, 0.70))
        put(button("LMB", "MOUSE_LEFT_BUTTON", 0.84, 0.58, scale = 1.45))
        put(button("RMB", "MOUSE_RIGHT_BUTTON", 0.94, 0.68, scale = 1.15))
        put(
            element(
                type = "TRACKPAD",
                bindings = listOf(
                    "MOUSE_MOVE_UP",
                    "MOUSE_MOVE_RIGHT",
                    "MOUSE_MOVE_DOWN",
                    "MOUSE_MOVE_LEFT",
                ),
                x = 0.78,
                y = 0.35,
                scale = 1.5,
                text = "LOOK",
            ),
        )
    }

    private fun button(
        text: String,
        binding: String,
        x: Double,
        y: Double,
        scale: Double = 1.0,
        toggle: Boolean = false,
    ): JSONObject = element(
        type = "BUTTON",
        bindings = listOf(binding, "NONE", "NONE", "NONE"),
        x = x,
        y = y,
        scale = scale,
        text = text,
        toggle = toggle,
    )

    private fun element(
        type: String,
        bindings: List<String>,
        x: Double,
        y: Double,
        scale: Double,
        text: String = "",
        toggle: Boolean = false,
        extra: Map<String, Any> = emptyMap(),
    ): JSONObject = JSONObject().apply {
        put("type", type)
        put("shape", "CIRCLE")
        put("bindings", JSONArray(bindings))
        put("scale", scale)
        put("x", x)
        put("y", y)
        put("toggleSwitch", toggle)
        put("text", text)
        put("iconId", 0)
        extra.forEach { (key, value) -> put(key, value) }
    }

    private val FPS_MARKERS = listOf(
        "fps", "shooter", "first-person", "first person", "tactical shooter", "hero shooter",
    )
    private val RTS_MARKERS = listOf(
        "rts", "real-time strategy", "real time strategy", "city builder", "4x",
    )
    private val KEYBOARD_FIRST_MARKERS = listOf(
        "online co-op", "co-op", "coop", "adventure", "puzzle", "simulation",
        "fishing", "fish", "party", "platformer", "metroidvania", "point and click",
    )
}
