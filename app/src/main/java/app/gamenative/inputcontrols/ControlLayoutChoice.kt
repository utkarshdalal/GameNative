package app.gamenative.inputcontrols

/**
 * Install-time / settings choice for on-screen touch controls.
 * [AUTO] picks KBM / FPS / RTS / Virtual Gamepad from store tags + Steam Input.
 */
enum class ControlLayoutChoice(val storageKey: String) {
    AUTO("auto"),
    KEYBOARD_MOUSE("kbm"),
    FPS("fps"),
    RTS("rts"),
    GAMEPAD("gamepad"),
    ;

    fun toStyleOrNull(): GameKeyboardLayoutFactory.Style? = when (this) {
        AUTO -> null
        KEYBOARD_MOUSE -> GameKeyboardLayoutFactory.Style.KEYBOARD_MOUSE
        FPS -> GameKeyboardLayoutFactory.Style.FPS
        RTS -> GameKeyboardLayoutFactory.Style.RTS
        GAMEPAD -> GameKeyboardLayoutFactory.Style.GAMEPAD
    }

    companion object {
        fun fromStorageKey(value: String?): ControlLayoutChoice? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) }
        }
    }
}
