package app.gamenative.steamcontroller

import com.winlator.inputcontrols.GamepadState
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Applies a [ScProfile] to each decoded [TritonState], driving an [ScOutputSink] (virtual XInput pad +
 * mouse/keys). This is the profile-driven replacement for the old hardcoded TritonMapper.applyState; with
 * [ScProfile.default] it behaves identically. The [profile] is swappable at runtime (future: action-set
 * switching). The sink seam makes the whole engine unit-testable headlessly (see ProfileInterpreterTest).
 */
class ProfileInterpreter(
    private val sink: ScOutputSink,
    @Volatile var profile: ScProfile,
    private val haptics: TritonHaptics?,
    /** Time source (ms) for activator timing; override in tests with a virtual clock. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Step-6 menu HUD sink; defaults to a no-op so the engine runs headless. */
    private val menuOverlay: ScMenuOverlay = NoOpScMenuOverlay,
    /** Split-trackpad keyboard HUD sink; defaults to a no-op. */
    keyboardOverlay: ScKeyboardOverlay = NoOpScKeyboardOverlay,
    /** Global "Touchpad deadzone" override (resting-finger freeze radius) for relative-mouse pads; null = use each
     *  [PadMode.Mouse]'s own jitterFloor. Set from [ScTuningStore] by the live driver. */
    padDeadzone: Int? = null,
    /** Global "Touchpad smoothing" (0–100) low-pass for pad-mouse motion + the keyboard cursor. 0 = off. */
    padSmoothing: Int = 0,
    /** App-UI seam: open the QuickMenu + navigate it (and the in-game editors) from the controller. No-op when
     *  headless / no overlay is attached, so unit tests and the engine-only path are unaffected. */
    private val uiBridge: ScUiBridge = NoOpScUiBridge,
) {
    // Mutable + @Volatile so the live-tune path ([setPadTuning], via TritonMapper.reload after an in-game edit) can
    // retune feel mid-game from another thread while the input loop reads these per report — no relaunch for dial-in.
    @Volatile private var padDeadzone: Int? = padDeadzone
    @Volatile private var padSmoothing: Int = padSmoothing

    /** Split on-screen keyboard; takes over the pads while open (toggled by a [ScOutput.ShowKeyboard] binding). */
    private val keyboard = ScKeyboard(sink, keyboardOverlay, haptics, clock, padSmoothing)

    /** Live dial-in of the two touchpad-feel knobs (via [TritonMapper.reload] after an in-game edit); also retunes
     *  the keyboard cursor low-pass so one knob still governs both surfaces. */
    fun setPadTuning(deadzone: Int?, smoothing: Int) {
        padDeadzone = deadzone
        padSmoothing = smoothing
        keyboard.setSmoothing(smoothing)
    }
    /** Push the active menu to the overlay HUD; never let a UI error break input. [directional] = a movement radial
     *  whose 8 ring slots show 8-way arrows instead of their bound key; [center] = the radial's center button. */
    private fun pushMenu(
        kind: ScMenuSpec.Kind, slots: List<MenuSlot>, cols: Int, rows: Int, highlighted: Int,
        center: MenuSlot? = null, directional: Boolean = false,
        cursorX: Float = Float.NaN, cursorY: Float = Float.NaN, menuId: String = "",
    ) {
        // Directional (movement) radials default to 8-way arrows, but a per-slot CUSTOM label always wins (so a
        // user can override the default arrow/key name). Non-directional menus use the config/key label.
        val labels = if (directional && slots.size == ARROW8.size) {
            slots.mapIndexed { i, s -> s.label.ifBlank { ARROW8[i] } }
        } else slots.map { slotLabel(it) }
        runCatching {
            menuOverlay.showMenu(ScMenuSpec(kind, labels, cols, rows, highlighted, center?.let { slotLabel(it) }, cursorX, cursorY, menuId))
        }
    }

    /** Overlay text for a menu slot: the config's label, or — when blank (e.g. ToME4's movement radial) — the
     *  bound key/output so the slot still shows what it does. */
    private fun slotLabel(slot: MenuSlot): String =
        slot.label.ifBlank { shortOutputName(slot.binding.output) }

    private fun shortOutputName(out: ScOutput?): String = when (out) {
        is ScOutput.Key -> out.keys.joinToString("+") { shortKeyName(it) }
        is ScOutput.MouseButton -> when (out.button) {
            Pointer.Button.BUTTON_LEFT -> "L-Click"
            Pointer.Button.BUTTON_RIGHT -> "R-Click"
            Pointer.Button.BUTTON_MIDDLE -> "M-Click"
            Pointer.Button.BUTTON_SCROLL_UP -> "Scroll↑"
            Pointer.Button.BUTTON_SCROLL_DOWN -> "Scroll↓"
            else -> "Mouse"
        }
        else -> ""
    }

    /** Compact key name for the overlay: arrows as glyphs, numpad as "Num N", punctuation as its symbol, else
     *  the bare key name. */
    private fun shortKeyName(k: XKeycode): String {
        val n = k.name.removePrefix("KEY_")
        PUNCT[n]?.let { return it }
        return when {
            n == "UP" -> "↑"
            n == "DOWN" -> "↓"
            n == "LEFT" -> "←"
            n == "RIGHT" -> "→"
            n.startsWith("KP_") -> "Num " + n.removePrefix("KP_")
            else -> n
        }
    }

    private companion object {
        /** Pad-mouse cursor tuning for menu/editor capture: raw-unit rest deadzone + pad-units→pixels gain. */
        const val NAV_CURSOR_FLOOR = 80f
        const val NAV_CURSOR_GAIN = 0.06f
        const val NAV_CURSOR_TICK_PX = 110f  // detent-tick spacing (px of cursor travel); higher = fewer ticks so the click stands out
        /** Raw gyro rotation-speed reference at which acceleration reaches full [GyroAccel.gain]. Tuned on-device. */
        const val GYRO_ACCEL_REF = 8000f
        /** Held-d-pad menu nav auto-repeat: initial delay, then per-step interval (ms). */
        const val NAV_REPEAT_DELAY_MS = 400L
        const val NAV_REPEAT_INTERVAL_MS = 130L
        // Trigger pull (raw 0..32767) past this counts as a held resize intent in the overlay editor (~quarter-pull).
        const val TRIGGER_NAV_THRESHOLD = 8000
        /** Macro step timing (ms): how long each command holds, and the gap after it, so the game samples distinct
         *  presses. Tunable by feel on device. [MIN_PULSE_MS] guards a delayed press+release so it still registers. */
        const val MACRO_STEP_MS = 40L
        const val MACRO_GAP_MS = 40L
        const val MIN_PULSE_MS = 20L
        /** 8-way arrow labels for a directional (movement) radial, clockwise from top — matches the ring order. */
        val ARROW8 = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
        /** Bare XKeycode name (minus "KEY_") → its glyph, for compact overlay slot labels. */
        val PUNCT = mapOf(
            "MINUS" to "-", "EQUAL" to "=", "COMMA" to ",", "PERIOD" to ".", "SLASH" to "/",
            "BACKSLASH" to "\\", "SEMICOLON" to ";", "APOSTROPHE" to "'", "GRAVE" to "`",
            "BRACKET_LEFT" to "[", "BRACKET_RIGHT" to "]", "SPACE" to "Space", "ENTER" to "⏎",
            "TAB" to "Tab", "ESC" to "Esc", "BKSP" to "⌫",
        )
    }
    private fun hideMenu() { runCatching { menuOverlay.hideMenu() } }
    private val gp = GamepadState()
    private var prevButtons = 0

    // Held stick position for GyroMode.Joystick(deflection=true): integrated gyro angle, reset when the gate closes.
    private var gyroDefX = 0f
    private var gyroDefY = 0f
    // GyroActivation.TOGGLE state: persistent on/off flipped on each gate press edge.
    private var gyroToggleOn = false
    private var gyroTogglePrevOpen = false

    // ---- timed scheduler (per-binding delays + macro playback) ----
    // A queue of press/release ops due at an absolute clock time. Drained each frame. Keys/mouse go straight to the
    // sink; gamepad outputs held by a running macro are collected in [macroBtns]/[macroDpad] and OR'd into gp.
    private class Scheduled(val dueMs: Long, val out: ScOutput, val press: Boolean)
    private val scheduled = ArrayList<Scheduled>()
    private var macroBtns = 0
    private val macroDpad = BooleanArray(4)

    private fun drainScheduled(now: Long) {
        if (scheduled.isNotEmpty()) {
            val fire = ArrayList<Scheduled>()
            val it = scheduled.iterator()
            while (it.hasNext()) { val s = it.next(); if (s.dueMs <= now) { fire.add(s); it.remove() } }
            fire.sortBy { it.dueMs }
            for (s in fire) when (val o = s.out) {
                is ScOutput.GamepadButton -> macroBtns = if (s.press) macroBtns or (1 shl o.idx) else macroBtns and (1 shl o.idx).inv()
                is ScOutput.GamepadDpad -> macroDpad[o.index] = s.press
                else -> if (s.press) pressOutput(o) else releaseOutput(o)
            }
        }
        // Overlay any macro-held gamepad bits onto this frame's virtual pad (after the level buttons were rebuilt).
        for (i in 0..15) if (macroBtns and (1 shl i) != 0) gp.setPressed(i, true)
        for (i in 0..3) if (macroDpad[i]) gp.dpad[i] = true
    }

    /** Press/release an edge output at an absolute time (immediate if already due). */
    private fun schedule(out: ScOutput, atMs: Long, press: Boolean, now: Long) {
        if (atMs <= now) { if (press) pressOutput(out) else releaseOutput(out) } else scheduled.add(Scheduled(atMs, out, press))
    }

    /** Enqueue a macro (one-shot): each command's outputs are pressed together, held [MACRO_STEP_MS], then released;
     *  commands run in order, framed by their delay_start/delay_end (+ a small gap so steps read as distinct presses). */
    private fun playMacro(m: ScOutput.Macro, now: Long) {
        var t = now
        for (cmd in m.commands) {
            t += cmd.delayStartMs
            for (o in cmd.outputs) scheduled.add(Scheduled(t, o, true))
            val rel = t + MACRO_STEP_MS
            for (o in cmd.outputs) scheduled.add(Scheduled(rel, o, false))
            t = rel + cmd.delayEndMs + MACRO_GAP_MS
        }
    }

    private fun clearScheduled() {
        scheduled.clear(); macroBtns = 0; for (i in 0..3) macroDpad[i] = false
    }

    /** Optional multi-set config. When installed, [ScOutput.SwitchActionSet] bindings swap [profile] live. */
    @Volatile var config: ScConfig? = null
        private set
    /** The Steam preset id of the currently active action set (only meaningful when [config] is installed). */
    var activeSetId: String? = null
        private set
    /** Active action-layer preset ids, base-first; the effective [profile] = active set merged with these. */
    private val layerStack = ArrayList<String>()
    /** (button bit, layerId) for `hold_layer` ops: popped when the button is released, tracked by raw bit. */
    private val heldLayers = ArrayList<Pair<Int, String>>()
    /** (button bit, source, overlay) for momentary `mode_shift` ops: a single-source overlay active while held. */
    private val heldShifts = ArrayList<Triple<Int, String, ScProfile>>()

    /** Install (or clear) a multi-action-set config and jump to its default set (no layers). */
    fun setConfig(cfg: ScConfig?) {
        config = cfg
        layerStack.clear(); heldLayers.clear(); heldShifts.clear()
        if (cfg != null) {
            activeSetId = cfg.defaultSetId
            recompute()
        }
    }

    /** Rebuild the effective [profile] = active set with each active layer merged over it, in stack order. */
    private fun recompute() {
        val cfg = config ?: return
        var p = cfg.sets[activeSetId] ?: cfg.defaultProfile()
        for (layerId in layerStack) {
            val layer = cfg.sets[layerId] ?: continue
            p = mergeProfiles(p, layer, cfg.setSources[layerId] ?: emptySet())
        }
        for ((_, source, overlay) in heldShifts) {   // momentary mode-shifts win on top
            p = mergeProfiles(p, overlay, setOf(source))
        }
        profile = p
    }

    /** Per-pad runtime state shared by the pad modes (mouse accumulator, active grid cell, scroll accumulator). */
    private class PadRuntime {
        var mouseActive = false
        var lastX = 0
        var lastY = 0
        // Sub-pixel remainder for pad-mouse so slow drags aren't lost to integer truncation.
        var accumX = 0f
        var accumY = 0f
        // EMA state for the optional motion low-pass (Touchpad smoothing).
        var smoothX = 0f
        var smoothY = 0f
        var gridCell = -1
        var scrollAccum = 0
        var dpadMask = 0
        // Single-button pad: currently-pressed output (so we can release it). null = not pressed.
        var singlePressed: ScOutput? = null
        // Directional-swipe: anchor the flick is measured from (re-set each fresh touch), + last-fired dir gate.
        var swipeAnchorX = 0
        var swipeAnchorY = 0
        var swipeTouched = false
        /** Radial/Touch menu state for a pad-driven menu. */
        val menu = MenuRuntime()
    }
    private val leftStickMenu = MenuRuntime()
    private val rightStickMenu = MenuRuntime()
    private val leftPad = PadRuntime()
    private val rightPad = PadRuntime()

    // Pad-mouse cursor state while a menu/editor is captured: the RIGHT trackpad drives an on-screen cursor over the
    // Compose dialog (right-pad click = tap). Trailing-anchor deadzone mirrors [applyPadMouse] to kill rest jitter.
    private var navCursorActive = false
    private var navCursorAnchorX = 0
    private var navCursorAnchorY = 0
    private var navCursorAccumX = 0f
    private var navCursorAccumY = 0f
    private val navClickGain = HapticSettings().clickGain
    private val navTickGain = HapticSettings().tickGain
    private var navCursorTickAccum = 0f
    private var prevCapturing = false

    fun apply(s: TritonState) {
        // GameNative's QuickMenu / in-game editors take over the controller while up: translate movement + buttons
        // into Android focus-nav keys and suppress all game output (the BLE Triton isn't an Android input device,
        // so this is the only way it can drive those Compose surfaces). Checked first so an open menu always wins.
        val capturing = uiBridge.isMenuCapturing()
        // On the falling edge (menu/editor closed), remove the pad-mouse nav cursor — nothing else drives it once
        // capture ends, so without this it freezes on-screen (bug: white dot stuck centre after closing the QuickMenu).
        if (prevCapturing && !capturing) uiBridge.hideCursor()
        prevCapturing = capturing
        if (capturing) {
            handleMenuNav(s)
            prevButtons = s.buttons
            return
        }

        // On-screen keyboard takes over the controller while open. The toggle binding ([ScOutput.ShowKeyboard])
        // is honored even in keyboard mode (so the same button closes it). While open, the pads drive the keyboard
        // and normal mapping/gamepad output is suppressed.
        handleKeyboardToggle(s)
        if (keyboard.active) {
            keyboard.update(s)
            prevButtons = s.buttons
            return
        }

        // Open the QuickMenu on the press edge of an [ScOutput.OpenQuickMenu] binding (default: Steam button). Once
        // open, the menu-capture branch above handles navigation; freeze the pad neutral and bail this frame.
        if (handleOpenQuickMenu(s)) {
            neutralizeGamepad()
            prevButtons = s.buttons
            return
        }

        handleSetSwitch(s)        // may swap the active set
        handleLayerOps(s)         // may push/pop action layers; both rebuild the effective `profile`
        val p = profile

        // ---- level outputs: virtual-pad buttons + d-pad (rebuilt every frame from current state) ----
        // Reset first so any macro-held gamepad bit (overlaid later in drainScheduled) clears once its macro ends,
        // rather than sticking (non-mapped bits are never otherwise cleared).
        gp.buttons = 0
        for (i in 0..3) gp.dpad[i] = false
        // Reset the virtual sticks too: every stick writer (JoystickMove / DPad / pad-as-joystick) ASSIGNS each frame,
        // and gyro-joystick ADDS on top — so without this, gyro-joystick with a None output stick accumulates across
        // frames instead of tracking rate (camera never returns to center). Deflection keeps its own held accumulator.
        gp.thumbLX = 0f; gp.thumbLY = 0f; gp.thumbRX = 0f; gp.thumbRY = 0f
        for ((bit, b) in p.buttons) {
            when (val out = b.output) {
                is ScOutput.GamepadButton -> gp.setPressed(out.idx, s.has(bit))
                is ScOutput.GamepadDpad -> gp.dpad[out.index] = s.has(bit)
                else -> {} // edge outputs handled below
            }
        }
        applyStick(p.leftStick, s.leftStickX, s.leftStickY, s.has(TritonProtocol.BTN_L3), s.has(TritonProtocol.BTN_LSTICK_TOUCH), leftStickMenu, ScMenuLocation.LEFT_STICK.name)
        applyStick(p.rightStick, s.rightStickX, s.rightStickY, s.has(TritonProtocol.BTN_R3), s.has(TritonProtocol.BTN_RSTICK_TOUCH), rightStickMenu, ScMenuLocation.RIGHT_STICK.name)
        applyTrigger(p.leftTrigger, s.triggerLeft, leftTrig)
        applyTrigger(p.rightTrigger, s.triggerRight, rightTrig)
        // A gyro-to-joystick mode adds to the stick, so it must land in this frame's gp (before the push below).
        applyGyro(p.gyro, s)

        // ---- edge outputs (mouse buttons + keys) through their activators ----
        val now = clock()
        for ((bit, b) in p.buttons) {
            when (b.output) {
                // Edge outputs route through activators. (Per-binding delay/toggle on a gamepad LEVEL button is a
                // separate code path, deferred to a later wave — see the buttons loop above.)
                is ScOutput.MouseButton, is ScOutput.Key, is ScOutput.MouseNudge, is ScOutput.MousePosition, is ScOutput.Macro -> applyActivator(bit, b, s, now)
                else -> {}
            }
        }

        // ---- trackpads (mode-driven: mouse / grid / d-pad / scroll / pad-as-joystick) ----
        applyPad(p.leftPad, leftPad, s, TritonProtocol.BTN_LPAD_TOUCH, TritonProtocol.BTN_LPAD_CLICK, s.leftPadX, s.leftPadY, ScMenuLocation.LEFT_PAD.name)
        applyPad(p.rightPad, rightPad, s, TritonProtocol.BTN_RPAD_TOUCH, TritonProtocol.BTN_RPAD_CLICK, s.rightPadX, s.rightPadY, ScMenuLocation.RIGHT_PAD.name)

        // Fire any due scheduled ops (per-binding delays + macro steps) and overlay macro-held gamepad bits onto gp.
        drainScheduled(now)

        // Push the virtual pad AFTER every gp contributor (buttons, sticks, triggers, gyro, pad-as-joystick, macros).
        sink.gamepad(gp)


        // ---- regenerate trackpad haptics (profile-driven feel) ----
        haptics?.update(s, prevButtons, p.haptics)

        prevButtons = s.buttons
    }

    private fun rising(s: TritonState, bit: Int) = (s.buttons and bit) != 0 && (prevButtons and bit) == 0
    private fun falling(s: TritonState, bit: Int) = (s.buttons and bit) == 0 && (prevButtons and bit) != 0

    /** Toggle the on-screen keyboard on the press edge of any [ScOutput.ShowKeyboard] binding (honored even while
     *  the keyboard is open, so the same button closes it). On open, the virtual pad is frozen at neutral. */
    private fun handleKeyboardToggle(s: TritonState) {
        var hasBinding = false
        for ((bit, b) in profile.buttons) {
            if (b.output is ScOutput.ShowKeyboard) {
                hasBinding = true
                if (rising(s, bit)) { toggleKeyboard(); return }
            }
        }
        // Global fallback: if the active profile binds the keyboard nowhere AND the "..." Quick-Access (3-dots)
        // button is otherwise unbound, let it toggle the keyboard — so per-game .vdf configs that omit
        // SHOW_KEYBOARD (e.g. ToME4) still get the on-screen keyboard.
        if (!hasBinding && !profile.buttons.containsKey(TritonProtocol.BTN_QAM) && rising(s, TritonProtocol.BTN_QAM)) {
            toggleKeyboard()
        }
    }

    private fun toggleKeyboard() {
        if (keyboard.active) {
            keyboard.deactivate()
        } else {
            keyboard.activate()
            neutralizeGamepad()
        }
    }

    /** Freeze the virtual pad at neutral (used when an overlay takes over the controller, so the game sees no
     *  stuck input while the keyboard / QuickMenu is up). */
    private fun neutralizeGamepad() {
        clearScheduled() // drop any in-flight macro / delayed press so nothing stays stuck while an overlay is up
        gp.thumbLX = 0f; gp.thumbLY = 0f; gp.thumbRX = 0f; gp.thumbRY = 0f
        gp.triggerL = 0f; gp.triggerR = 0f; gp.buttons = 0
        for (i in 0..3) gp.dpad[i] = false
        sink.gamepad(gp)
    }

    /** Open the QuickMenu on the press edge of an [ScOutput.OpenQuickMenu] binding (default: Steam button). As a
     *  global fallback, an otherwise-unbound Steam button opens it too (so .vdf configs still reach the menu).
     *  Returns true if the menu was opened this frame. */
    private fun handleOpenQuickMenu(s: TritonState): Boolean {
        var hasBinding = false
        for ((bit, b) in profile.buttons) {
            if (b.output is ScOutput.OpenQuickMenu) {
                hasBinding = true
                if (rising(s, bit)) { uiBridge.openQuickMenu(); return true }
            }
        }
        if (!hasBinding && !profile.buttons.containsKey(TritonProtocol.BTN_STEAM) && rising(s, TritonProtocol.BTN_STEAM)) {
            uiBridge.openQuickMenu(); return true
        }
        return false
    }

    /** While a GameNative menu/editor is captured, translate the controller into Android focus-nav keys: d-pad and
     *  left-stick (edge-triggered into a direction) move focus, A selects, B / Steam go back. Never touches the
     *  game output. */
    private fun handleMenuNav(s: TritonState) {
        val now = clock()
        // The currently-held nav direction comes from the d-pad bits, else the left stick deflection. Holding a
        // direction auto-repeats (initial [NAV_REPEAT_DELAY_MS], then every [NAV_REPEAT_INTERVAL_MS]) so the user can
        // hold to scroll a long list instead of tapping per item.
        val dir: ScNavKey? = when {
            (s.buttons and TritonProtocol.BTN_DPAD_UP) != 0 -> ScNavKey.UP
            (s.buttons and TritonProtocol.BTN_DPAD_DOWN) != 0 -> ScNavKey.DOWN
            (s.buttons and TritonProtocol.BTN_DPAD_LEFT) != 0 -> ScNavKey.LEFT
            (s.buttons and TritonProtocol.BTN_DPAD_RIGHT) != 0 -> ScNavKey.RIGHT
            else -> when (stickNavDir(s.leftStickX, s.leftStickY)) {
                1 -> ScNavKey.UP; 2 -> ScNavKey.DOWN; 3 -> ScNavKey.LEFT; 4 -> ScNavKey.RIGHT; else -> null
            }
        }
        when {
            dir == null -> navHeldDir = null
            dir != navHeldDir -> { navHeldDir = dir; uiBridge.nav(dir); navRepeatAt = now + NAV_REPEAT_DELAY_MS }
            now >= navRepeatAt -> { uiBridge.nav(dir); navRepeatAt = now + NAV_REPEAT_INTERVAL_MS }
        }

        // Triggers = resize intent in the overlay placement editor (RT bigger, LT smaller); ignored by other menus.
        // Own held-repeat so the user can resize while the stick moves the overlay. Threshold ~quarter-pull.
        val zoom: ScNavKey? = when {
            s.triggerRight > TRIGGER_NAV_THRESHOLD -> ScNavKey.ZOOM_IN
            s.triggerLeft > TRIGGER_NAV_THRESHOLD -> ScNavKey.ZOOM_OUT
            else -> null
        }
        when {
            zoom == null -> zoomHeld = null
            zoom != zoomHeld -> { zoomHeld = zoom; uiBridge.nav(zoom); zoomRepeatAt = now + NAV_REPEAT_DELAY_MS }
            now >= zoomRepeatAt -> { uiBridge.nav(zoom); zoomRepeatAt = now + NAV_REPEAT_INTERVAL_MS }
        }

        // Edge-triggered nav (A=Select, B/Steam=Back, LB/RB=tab-or-set, Y=Help) is defined ONCE in the fixed
        // [ScMenuNav] table so the interpreter and the editor tooltips can never drift apart.
        for (c in ScMenuNav.controls) if (rising(s, c.buttonBit)) uiBridge.nav(c.key)

        // Right trackpad = pad-mouse cursor over the dialog (the additive Steam-Controller nav option). Runs alongside
        // d-pad nav so either works; complex editor screens are usable by pointing + clicking instead of tab-focusing.
        handleMenuCursor(s)
    }

    private fun handleMenuCursor(s: TritonState) {
        val touched = (s.buttons and TritonProtocol.BTN_RPAD_TOUCH) != 0
        if (!touched) {
            navCursorActive = false
        } else if (!navCursorActive) {
            navCursorActive = true
            navCursorAnchorX = s.rightPadX; navCursorAnchorY = s.rightPadY
            navCursorAccumX = 0f; navCursorAccumY = 0f
        } else {
            val dxRaw = (s.rightPadX - navCursorAnchorX).toFloat()
            val dyRaw = (s.rightPadY - navCursorAnchorY).toFloat()
            val dist = hypot(dxRaw, dyRaw)
            if (dist >= NAV_CURSOR_FLOOR) {
                val ux = dxRaw / dist; val uy = dyRaw / dist
                navCursorAnchorX = (s.rightPadX - ux * NAV_CURSOR_FLOOR).roundToInt()
                navCursorAnchorY = (s.rightPadY - uy * NAV_CURSOR_FLOOR).roundToInt()
                val over = dist - NAV_CURSOR_FLOOR
                navCursorAccumX += ux * over * NAV_CURSOR_GAIN
                navCursorAccumY += -uy * over * NAV_CURSOR_GAIN // pad +Y is up; screen +Y is down
                val dx = navCursorAccumX.toInt(); val dy = navCursorAccumY.toInt()
                if (dx != 0 || dy != 0) {
                    uiBridge.moveCursor(dx, dy)
                    navCursorAccumX -= dx; navCursorAccumY -= dy
                    // Detent ticks as the cursor travels (reuse the keyboard's per-cell tick) so dragging feels like a
                    // textured surface, not a dead glide — one tick every [NAV_CURSOR_TICK_PX] pixels of travel.
                    navCursorTickAccum += hypot(dx.toFloat(), dy.toFloat())
                    if (navCursorTickAccum >= NAV_CURSOR_TICK_PX) {
                        navCursorTickAccum %= NAV_CURSOR_TICK_PX
                        haptics?.tick(1, navTickGain)
                    }
                }
            }
        }
        if (rising(s, TritonProtocol.BTN_RPAD_CLICK)) {
            uiBridge.cursorTap()
            haptics?.click(1, navClickGain) // right pad (side 1) — a real "click" feel so you can tell it registered
        }
    }

    /** Dominant left-stick direction past a deadzone: 0=none, 1=up, 2=down, 3=left, 4=right. Stick axes are s16
     *  (±32767); Y is up-positive on the SC, so up = +Y. */
    private fun stickNavDir(x: Int, y: Int): Int {
        val t = 16000
        if (abs(x) < t && abs(y) < t) return 0
        return if (abs(y) >= abs(x)) { if (y > 0) 1 else 2 } else { if (x < 0) 3 else 4 }
    }
    private var navHeldDir: ScNavKey? = null
    private var navRepeatAt = 0L
    private var zoomHeld: ScNavKey? = null
    private var zoomRepeatAt = 0L

    /**
     * Config-driven action-set switching: a [ScOutput.SwitchActionSet] binding swaps the active [profile] when
     * its button hits the matching edge (press, or release for the menu-set's "return" binding). The "hold for
     * menus" feel is encoded by the config itself (set A enters on press, set B leaves on release), so this stays
     * stateless. No-op unless an [ScConfig] is installed.
     */
    private fun handleSetSwitch(s: TritonState) {
        val cfg = config ?: return
        for ((bit, b) in profile.buttons) {
            val out = b.output
            if (out is ScOutput.SwitchActionSet) {
                val fire = if (out.onRelease) falling(s, bit) else rising(s, bit)
                if (fire && cfg.sets.containsKey(out.targetSetId)) {
                    activeSetId = out.targetSetId
                    layerStack.clear(); heldLayers.clear(); heldShifts.clear()  // new set: no layers/shifts
                    recompute()
                    runCatching { menuOverlay.toast(cfg.sets[activeSetId]?.name ?: "Set $activeSetId") }
                }
            }
        }
    }

    /**
     * Config-driven action **layers**: `add_layer`/`hold_layer`/`remove_layer` bindings ([ScOutput.LayerOp])
     * push/pop a partial overlay ([mergeProfiles]) over the active set. `hold_layer` is popped on the button's
     * release — tracked by raw bit so it survives the layer rebinding that button. No-op without an [ScConfig].
     */
    private fun handleLayerOps(s: TritonState) {
        val cfg = config ?: return
        var changed = false
        // Pop held layers / mode-shifts whose button was released.
        val held = heldLayers.iterator()
        while (held.hasNext()) {
            val (bit, layerId) = held.next()
            if (falling(s, bit)) {
                layerStack.remove(layerId); held.remove(); changed = true
                // Releasing a hold-layer returns to the base set — pop the OSD title like action-set switching.
                runCatching { menuOverlay.toast(cfg.sets[activeSetId]?.name ?: "Set $activeSetId") }
            }
        }
        val shifts = heldShifts.iterator()
        while (shifts.hasNext()) {
            if (falling(s, shifts.next().first)) { shifts.remove(); changed = true }
        }
        // Apply press-edge ops from the current effective profile.
        for ((bit, b) in profile.buttons) {
            when (val out = b.output) {
                is ScOutput.LayerOp -> if (rising(s, bit) && cfg.sets.containsKey(out.layerId)) {
                    // OSD pop-up mirrors action-set switching: show the layer's name on push, the base set on remove.
                    fun toastLayer() = runCatching { menuOverlay.toast(cfg.sets[out.layerId]?.name ?: "Layer ${out.layerId}") }
                    when (out.op) {
                        LayerOpType.ADD -> if (!layerStack.contains(out.layerId)) { layerStack.add(out.layerId); changed = true; toastLayer() }
                        LayerOpType.REMOVE -> if (layerStack.remove(out.layerId)) {
                            changed = true; runCatching { menuOverlay.toast(cfg.sets[activeSetId]?.name ?: "Set $activeSetId") }
                        }
                        LayerOpType.HOLD -> if (!layerStack.contains(out.layerId)) {
                            layerStack.add(out.layerId); heldLayers.add(bit to out.layerId); changed = true; toastLayer()
                        }
                    }
                }
                is ScOutput.ModeShift -> if (rising(s, bit) && heldShifts.none { it.first == bit }) {
                    cfg.shiftOverlays[out.groupId]?.let { heldShifts.add(Triple(bit, out.source, it)); changed = true }
                }
                else -> {}
            }
        }
        if (changed) recompute()
    }

    private class ActState {
        var lastRise = 0L        // DoublePress: time of the first press
        var awaitSecond = false  // DoublePress: a first press is pending a possible second
        var downTime = 0L        // LongPress: time the press began
        var fired = false        // LongPress: threshold reached and output held
        var lastFire = 0L        // Turbo: time of the last pulse
        var held = false         // Regular/LongPress: output currently held down
        var toggled = false      // Toggle: output currently latched on
        var pressDueMs = 0L      // Regular+delay: when the deferred press is/was due (so a delayed release stays after it)
    }
    private val actStates = HashMap<Int, ActState>()

    /** Drive an edge output (Key / MouseButton) through its activator's press logic. */
    private fun applyActivator(bit: Int, b: Binding, s: TritonState, now: Long) {
        val out = b.output
        val pressed = s.has(bit)
        val rose = rising(s, bit)
        val fell = falling(s, bit)
        val st = actStates.getOrPut(bit) { ActState() }
        // Macro: play the whole sequence once on the press edge (one-shot; hold/release don't affect it).
        if (out is ScOutput.Macro) { if (rose) playMacro(out, now); return }
        // Toggle: the press edge latches the output on, the next press latches it off (delays apply to each edge).
        if (b.toggle) {
            if (rose) { st.toggled = !st.toggled; schedule(out, now + (if (st.toggled) b.delayStartMs else b.delayEndMs), st.toggled, now) }
            return
        }
        when (val act = b.activator) {
            Activator.Regular -> {
                // Fire Start/End Delay: defer the press/release. "Fire anyway" — a press scheduled on the rise happens
                // even if released during the delay; the release is clamped to stay after the press so a tap registers.
                // With no delay set, both fire immediately (identical to the pre-delay path).
                val delayed = b.delayStartMs > 0 || b.delayEndMs > 0
                if (rose) { st.pressDueMs = now + b.delayStartMs; schedule(out, st.pressDueMs, true, now); st.held = true }
                if (fell) { schedule(out, if (delayed) maxOf(now + b.delayEndMs, st.pressDueMs + MIN_PULSE_MS) else now, false, now); st.held = false }
            }
            is Activator.DoublePress -> {
                if (rose) {
                    if (st.awaitSecond && now - st.lastRise <= act.windowMs) {
                        pulse(out); st.awaitSecond = false
                    } else {
                        st.lastRise = now; st.awaitSecond = true
                    }
                }
            }
            is Activator.LongPress -> {
                if (rose) { st.downTime = now; st.fired = false }
                if (pressed && !st.fired && now - st.downTime >= act.holdMs) {
                    pressOutput(out); st.held = true; st.fired = true
                }
                if (fell) { if (st.held) { releaseOutput(out); st.held = false }; st.fired = false }
            }
            is Activator.Turbo -> {
                if (rose) { pulse(out); st.lastFire = now }
                else if (pressed && now - st.lastFire >= act.intervalMs) { pulse(out); st.lastFire = now }
            }
            Activator.OnRelease -> {
                if (fell) pulse(out) // fire a quick pulse when the button is let go
            }
        }
    }

    private fun pulse(out: ScOutput) { pressOutput(out); releaseOutput(out) }

    private fun applyStick(mode: StickMode, rawX: Int, rawY: Int, clicked: Boolean, touched: Boolean, menuRt: MenuRuntime, menuId: String) {
        when (mode) {
            is StickMode.RadialMenu -> driveMenu(
                stickMag(rawX, rawY) >= mode.deadzone, touched, clicked, rawX, rawY,
                mode.slots, ScMenuSpec.Kind.RADIAL, 0, 0, mode.activation, commitOnClick = false, menuRt,
                center = mode.center, directional = mode.directional, menuId = menuId,
            )
            is StickMode.TouchMenu -> driveMenu(
                stickMag(rawX, rawY) >= mode.deadzone, touched, clicked, rawX, rawY,
                mode.slots, ScMenuSpec.Kind.GRID, mode.cols, mode.rows, mode.activation, commitOnClick = false, menuRt,
                menuId = menuId,
            )
            is StickMode.JoystickMove -> {
                val x = axisCurved(rawX, mode.deadzone, mode.curve)
                val y = axisCurved(if (mode.invertY) -rawY else rawY, mode.deadzone, mode.curve)
                if (mode.stick == Stick.LEFT) {
                    gp.thumbLX = x; gp.thumbLY = y
                } else {
                    gp.thumbRX = x; gp.thumbRY = y
                }
            }
            is StickMode.Mouse -> {
                val x = axisCurved(rawX, mode.deadzone, mode.curve)
                val y = axisCurved(if (mode.invertY) -rawY else rawY, mode.deadzone, mode.curve)
                val dx = (x * mode.sensitivity).toInt()
                val dy = (-y * mode.sensitivity).toInt() // screen Y grows downward
                if (dx != 0 || dy != 0) sink.mouseMove(dx, dy)
            }
            is StickMode.FlickStick -> {
                // Simplified: horizontal deflection -> yaw mouse velocity (NOT the true flick-and-rotate model).
                val x = axis(rawX, mode.deadzone)
                val dx = (x * mode.sensitivity).toInt()
                if (dx != 0) sink.mouseMove(dx, 0)
            }
            is StickMode.DPad -> applyStickDpad(mode, rawX, rawY, menuRt)
            StickMode.None -> {}
        }
    }

    private class TrigRuntime { var soft = false; var full = false }
    private val leftTrig = TrigRuntime()
    private val rightTrig = TrigRuntime()

    private fun applyTrigger(mode: TriggerMode, raw: Int, rt: TrigRuntime) {
        when (mode) {
            is TriggerMode.Axis -> setTriggerAxis(mode.axis, raw)
            is TriggerMode.Staged -> {
                setTriggerAxis(mode.axis, raw)
                val v = (raw / 32767f).coerceIn(0f, 1f)
                val soft = v >= mode.softThreshold
                val full = v >= mode.fullThreshold
                if (soft && !rt.soft) pressOutput(mode.soft)
                if (!soft && rt.soft) releaseOutput(mode.soft)
                rt.soft = soft
                if (full && !rt.full) pressOutput(mode.full)
                if (!full && rt.full) releaseOutput(mode.full)
                rt.full = full
            }
        }
    }

    private fun setTriggerAxis(axis: TriggerAxis, raw: Int) {
        val v = (raw / 32767f).coerceIn(0f, 1f)
        when (axis) {
            TriggerAxis.GAMEPAD_L2 -> gp.triggerL = v
            TriggerAxis.GAMEPAD_R2 -> gp.triggerR = v
            TriggerAxis.NONE -> {}
        }
    }

    private fun applyPad(mode: PadMode, rt: PadRuntime, s: TritonState, touchBit: Int, clickBit: Int, x: Int, y: Int, menuId: String) {
        when (mode) {
            PadMode.None -> {
                rt.mouseActive = false; releaseGridCell(mode, rt)
                if (rt.menu.active) hideMenu()
                rt.menu.slot = -1; rt.menu.active = false; rt.menu.heldSlot = -1
                if (rt.singlePressed != null) { releaseOutput(rt.singlePressed); rt.singlePressed = null }
                rt.swipeTouched = false
            }
            is PadMode.Mouse -> applyPadMouse(mode, rt, s.has(touchBit), x, y)
            is PadMode.AbsoluteMouse -> applyPadAbsolute(mode, s.has(touchBit), x, y)
            is PadMode.SingleButton -> applyPadSingle(mode, rt, s.has(if (mode.onClick) clickBit else touchBit))
            is PadMode.DirectionalSwipe -> applyPadSwipe(mode, rt, s.has(touchBit), x, y)
            is PadMode.ButtonPadGrid -> applyPadGrid(mode, rt, s.has(if (mode.onClick) clickBit else touchBit), x, y)
            is PadMode.DPad -> applyPadDpad(mode, rt, s.has(touchBit), x, y)
            is PadMode.ScrollWheel -> applyPadScroll(mode, rt, s.has(touchBit), y)
            is PadMode.Joystick -> applyPadJoystick(mode, s.has(touchBit), x, y)
            is PadMode.MouseJoystick -> applyPadMouseJoystick(mode, s.has(touchBit), x, y)
            is PadMode.RadialMenu -> driveMenu(s.has(touchBit), s.has(touchBit), s.has(clickBit), x, y, mode.slots, ScMenuSpec.Kind.RADIAL, 0, 0, mode.activation, mode.onClick, rt.menu, center = mode.center, directional = mode.directional, menuId = menuId)
            is PadMode.TouchMenu -> driveMenu(s.has(touchBit), s.has(touchBit), s.has(clickBit), x, y, mode.slots, ScMenuSpec.Kind.GRID, mode.cols, mode.rows, mode.activation, mode.onClick, rt.menu, menuId = menuId)
        }
    }

    // ---- shared Radial/Touch menu engine (pad- or stick-driven) ----
    private class MenuRuntime { var slot = -1; var active = false; var shown = false; var heldSlot = -1; var lastFire = 0L; var committed = false; var dpadMask = 0 }

    /**
     * Drive a Radial/Touch menu from any 2D source. [active] = the source is engaged (pad touched / stick deflected
     * past its deadzone). While active the highlighted slot tracks the position; firing depends on [activation]:
     * COMMIT = pulse once on click ([commitOnClick]) or on disengage (point-and-release); HOLD = the slot's output
     * is held while pointed and released when the highlight changes or the source disengages. Pushes the HUD while
     * active, hides it on disengage.
     */
    private fun driveMenu(
        active: Boolean, shown: Boolean, clicked: Boolean, x: Int, y: Int,
        slots: List<MenuSlot>, kind: ScMenuSpec.Kind, cols: Int, rows: Int,
        activation: MenuActivation, commitOnClick: Boolean, rt: MenuRuntime,
        center: MenuSlot? = null, directional: Boolean = false, menuId: String = "",
    ) {
        if (slots.isEmpty()) { rt.active = active; rt.shown = shown; return }
        val now = clock()
        // Cursor dot position for the radial HUD (normalized source position; pads/sticks both report ±32768).
        val curX = if (kind == ScMenuSpec.Kind.RADIAL) (x / 32768f).coerceIn(-1f, 1f) else Float.NaN
        val curY = if (kind == ScMenuSpec.Kind.RADIAL) (y / 32768f).coerceIn(-1f, 1f) else Float.NaN
        if (active) {
            if (!rt.active) rt.committed = false // fresh engagement
            rt.slot = when (kind) {
                ScMenuSpec.Kind.RADIAL -> radialSlot(x, y, slots.size, rt.slot)
                ScMenuSpec.Kind.GRID -> gridSlot(x, y, cols, rows, slots.size)
            }
            when (activation) {
                MenuActivation.HOLD -> {
                    // A movement-radial slot with hold_repeats (Activator.Turbo) should *repeat* the key while the
                    // stick stays pointed (re-pulse every intervalMs, matching Steam's repeat_rate) — some games
                    // (ToME4) only repeat on discrete presses, not a held key. Slots without it hold continuously.
                    val turbo = slots.getOrNull(rt.slot)?.binding?.activator as? Activator.Turbo
                    if (rt.heldSlot != rt.slot) {
                        releaseHeldIfContinuous(slots, rt.heldSlot) // turbo slots already pulsed (key is up)
                        rt.heldSlot = rt.slot
                        if (turbo != null) { pulseSlot(slots, rt.slot); rt.lastFire = now } else pressOutput(slots.getOrNull(rt.slot)?.binding?.output)
                    } else if (turbo != null && now - rt.lastFire >= turbo.intervalMs) {
                        pulseSlot(slots, rt.slot); rt.lastFire = now
                    }
                }
                MenuActivation.COMMIT -> {
                    // Per-slot Turbo (hold_repeats): re-fire while the slot stays pointed, instead of a single
                    // commit. Non-turbo slots commit immediately on a pad click — even in release-style menus
                    // (requires_click=0), clicking is always a valid commit (matches Steam) — else on
                    // point-and-release at disengage (below). [committed] gates the release so click+release
                    // doesn't double-fire.
                    val turbo = slots.getOrNull(rt.slot)?.binding?.activator as? Activator.Turbo
                    if (turbo != null) {
                        if (rt.heldSlot != rt.slot) { rt.heldSlot = rt.slot; pulseSlot(slots, rt.slot); rt.lastFire = now }
                        else if (now - rt.lastFire >= turbo.intervalMs) { pulseSlot(slots, rt.slot); rt.lastFire = now }
                    } else if (clicked && !rt.committed) {
                        pulseSlot(slots, rt.slot); rt.committed = true
                    }
                }
            }
            pushMenu(kind, slots, cols, rows, rt.slot, center, directional, curX, curY, menuId)
        } else {
            // Not engaged (centered / finger lifted). If we WERE engaged, finalize the just-ended deflection.
            if (rt.active) {
                when (activation) {
                    MenuActivation.HOLD -> { releaseHeldIfContinuous(slots, rt.heldSlot); rt.heldSlot = -1 }
                    MenuActivation.COMMIT -> {
                        // point-and-release commit — for release-style menus only, and not if a click already
                        // committed this engagement or it's a Turbo slot (which fired repeatedly while held).
                        val wasTurbo = slots.getOrNull(rt.slot)?.binding?.activator is Activator.Turbo
                        if (!commitOnClick && !wasTurbo && !rt.committed) pulseSlot(slots, rt.slot)
                    }
                }
                rt.slot = -1; rt.heldSlot = -1; rt.committed = false
            }
            // HUD: keep it visible while the source is still *touched* (thumb resting on the stick / finger on the
            // pad) even before deflecting — show the ring with nothing highlighted; hide once the source is released.
            if (shown) { rt.slot = -1; pushMenu(kind, slots, cols, rows, -1, center, directional, curX, curY, menuId) }
            else if (rt.shown || rt.active) hideMenu()
        }
        rt.active = active
        rt.shown = shown
    }

    /** Radial angle -> slot (0 at top, clockwise); keeps [prev] while inside the center dead-zone (~0.25 radius). */
    private fun radialSlot(x: Int, y: Int, n: Int, prev: Int): Int {
        val nx = x / 32768f
        val ny = y / 32768f
        if (nx * nx + ny * ny < 0.0625f) return prev
        val ang = Math.toDegrees(kotlin.math.atan2(nx.toDouble(), ny.toDouble())) // 0=up, +clockwise
        val norm = ((ang % 360) + 360) % 360
        return Math.round(norm / (360.0 / n)).toInt() % n
    }

    /** Grid cell -> slot (row 0 = top), or -1 if past the slot count. */
    private fun gridSlot(x: Int, y: Int, cols: Int, rows: Int, n: Int): Int {
        val nx = (x.toFloat() / 65536f + 0.5f).coerceIn(0f, 0.999f)
        val ny = (y.toFloat() / 65536f + 0.5f).coerceIn(0f, 0.999f)
        val col = (nx * cols).toInt().coerceIn(0, cols - 1)
        val rowTop = (rows - 1) - (ny * rows).toInt().coerceIn(0, rows - 1)
        val cell = rowTop * cols + col
        return if (cell < n) cell else -1
    }

    private fun pulseSlot(slots: List<MenuSlot>, slot: Int) {
        val out = slots.getOrNull(slot)?.binding?.output ?: return
        pressOutput(out); releaseOutput(out)
    }

    /** Release a HOLD slot's key only if it was held continuously (non-Turbo). Turbo slots are pulsed, so their
     *  key is already up and a release would emit a spurious key-up. */
    private fun releaseHeldIfContinuous(slots: List<MenuSlot>, idx: Int) {
        val b = slots.getOrNull(idx)?.binding ?: return
        if (b.activator !is Activator.Turbo) releaseOutput(b.output)
    }

    /** d-pad direction mask for a normalized deflection, honoring the [layout] (bit0=up, bit1=down, bit2=left,
     *  bit3=right; +cy = up). See [DpadLayout]. */
    private fun dpadMask(cx: Float, cy: Float, deadzone: Float, layout: DpadLayout, overlap: Float): Int {
        val ax = abs(cx); val ay = abs(cy)
        if (ax < deadzone && ay < deadzone) return 0
        return when (layout) {
            DpadLayout.EIGHT_WAY, DpadLayout.ANALOG_EMU -> {
                var m = 0
                if (cy > deadzone) m = m or 0x1
                if (cy < -deadzone) m = m or 0x2
                if (cx < -deadzone) m = m or 0x4
                if (cx > deadzone) m = m or 0x8
                m
            }
            DpadLayout.FOUR_WAY, DpadLayout.CROSS_GATE -> {
                // Cross gate: near-diagonals (the two axes close in magnitude) press nothing.
                if (layout == DpadLayout.CROSS_GATE && abs(ay - ax) < overlap) return 0
                if (ay >= ax) { if (cy > 0) 0x1 else 0x2 } else { if (cx < 0) 0x4 else 0x8 }
            }
        }
    }

    /** d-pad: edge-press [outs] as the mask changes; returns the new mask (store it back). [cx]/[cy] = normalized
     *  deflection (+Y up); [active]=false forces neutral (e.g. pad lifted). */
    private fun driveDpad(cx: Float, cy: Float, active: Boolean, deadzone: Float, layout: DpadLayout, overlap: Float, outs: Array<ScOutput>, prevMask: Int): Int {
        val mask = if (!active) 0 else dpadMask(cx, cy, deadzone, layout, overlap)
        if (mask == prevMask) return mask
        for (i in 0..3) {
            val bit = 1 shl i
            if (mask and bit != 0 && prevMask and bit == 0) pressOutput(outs[i])
            if (mask and bit == 0 && prevMask and bit != 0) releaseOutput(outs[i])
        }
        return mask
    }

    private fun applyPadDpad(mode: PadMode.DPad, rt: PadRuntime, touched: Boolean, x: Int, y: Int) {
        rt.dpadMask = driveDpad(x / 32768f, y / 32768f, touched, mode.deadzone, mode.layout, mode.overlap,
            arrayOf(mode.up, mode.down, mode.left, mode.right), rt.dpadMask)
    }

    /** Stick as a d-pad: always live (deflection past the deadzone presses a direction; recenters to neutral). */
    private fun applyStickDpad(mode: StickMode.DPad, rawX: Int, rawY: Int, rt: MenuRuntime) {
        rt.dpadMask = driveDpad(rawX / 32768f, rawY / 32768f, active = true, mode.deadzone, mode.layout, mode.overlap,
            arrayOf(mode.up, mode.down, mode.left, mode.right), rt.dpadMask)
    }

    /** Scroll wheel: accumulate vertical travel; each [step] units emits one wheel click. */
    private fun applyPadScroll(mode: PadMode.ScrollWheel, rt: PadRuntime, touched: Boolean, y: Int) {
        if (!touched) { rt.mouseActive = false; return }
        if (!rt.mouseActive) { rt.mouseActive = true; rt.lastY = y; rt.scrollAccum = 0; return }
        rt.scrollAccum += (y - rt.lastY)
        rt.lastY = y
        val up = if (mode.invertY) Pointer.Button.BUTTON_SCROLL_DOWN else Pointer.Button.BUTTON_SCROLL_UP
        val down = if (mode.invertY) Pointer.Button.BUTTON_SCROLL_UP else Pointer.Button.BUTTON_SCROLL_DOWN
        while (rt.scrollAccum >= mode.step) { clickWheel(up); rt.scrollAccum -= mode.step }   // finger up = scroll up
        while (rt.scrollAccum <= -mode.step) { clickWheel(down); rt.scrollAccum += mode.step }
    }

    private fun clickWheel(button: Pointer.Button) {
        sink.mouseButton(button, true)
        sink.mouseButton(button, false)
    }

    /** Absolute / region mouse: warp the cursor to where the finger is (mapped 1:1 onto the mode's screen region)
     *  while the pad is touched. No deadzone/accumulation — it's a direct position, not a relative delta. */
    private fun applyPadAbsolute(mode: PadMode.AbsoluteMouse, touched: Boolean, x: Int, y: Int) {
        if (!touched) return
        val px = (x / 65536f + 0.5f).coerceIn(0f, 1f)        // 0 = left, 1 = right
        val pyUp = (y / 65536f + 0.5f).coerceIn(0f, 1f)      // 0 = bottom, 1 = top (pad reports +Y up)
        val fx = if (mode.invertX) 1f - px else px
        val fyTop = if (mode.invertY) pyUp else 1f - pyUp    // 0 = screen top
        // Region-relative offset from center (−0.5..0.5), optionally rotated (Rotate Output), then scaled onto the region.
        var ox = fx - 0.5f
        var oy = fyTop - 0.5f
        if (mode.rotation != 0f) {
            val r = Math.toRadians(mode.rotation.toDouble())
            val c = kotlin.math.cos(r).toFloat(); val sn = kotlin.math.sin(r).toFloat()
            val rx = ox * c - oy * sn; val ry = ox * sn + oy * c
            ox = rx; oy = ry
        }
        // Map the pad extent onto the region centered at (centerX,centerY) spanning sizeX/sizeY of the screen.
        val nx = mode.centerX + ox * mode.sizeX
        val ny = mode.centerY + oy * mode.sizeY
        sink.mouseMoveAbs(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    /** Pad-as-joystick: the finger's absolute position = stick deflection while touched; recenters (zero) on lift.
     *  Same curve/deadzone math as a physical stick ([applyStick]'s JoystickMove branch). */
    private fun applyPadJoystick(mode: PadMode.Joystick, touched: Boolean, x: Int, y: Int) {
        val vx = if (touched) axisCurved(x, mode.deadzone, mode.curve) else 0f
        val vy = if (touched) axisCurved(if (mode.invertY) -y else y, mode.deadzone, mode.curve) else 0f
        if (mode.stick == Stick.LEFT) { gp.thumbLX = vx; gp.thumbLY = vy } else { gp.thumbRX = vx; gp.thumbRY = vy }
    }

    /** Mouse Joystick: the finger's displacement from the pad CENTER drives a cursor velocity (self-centering, like
     *  a joystick that outputs mouse). Zero inside the deadzone; keeps moving while held off-centre; stops on lift. */
    private fun applyPadMouseJoystick(mode: PadMode.MouseJoystick, touched: Boolean, x: Int, y: Int) {
        if (!touched) return
        val nx = (x / 32768f).coerceIn(-1f, 1f)
        val ny = (y / 32768f).coerceIn(-1f, 1f) // pad +Y up
        if (hypot(nx, ny) < mode.deadzone) return
        val dx = (nx * mode.sensitivity).toInt()
        val dy = ((if (mode.invertY) ny else -ny) * mode.sensitivity).toInt() // screen Y grows downward
        if (dx != 0 || dy != 0) sink.mouseMove(dx, dy)
    }

    /** Single-button pad: whole surface = one button, pressed while touched/clicked, released on lift. */
    private fun applyPadSingle(mode: PadMode.SingleButton, rt: PadRuntime, engaged: Boolean) {
        if (engaged && rt.singlePressed == null) { rt.singlePressed = mode.output; pressOutput(mode.output) }
        else if (!engaged && rt.singlePressed != null) { releaseOutput(rt.singlePressed); rt.singlePressed = null }
    }

    /** Directional swipe: a flick past the threshold from the touch anchor pulses that cardinal direction's output,
     *  then slides the anchor so a continued swipe keeps firing (scroll-like). Re-anchors on each fresh touch. */
    private fun applyPadSwipe(mode: PadMode.DirectionalSwipe, rt: PadRuntime, touched: Boolean, x: Int, y: Int) {
        if (!touched) { rt.swipeTouched = false; return }
        if (!rt.swipeTouched) { rt.swipeTouched = true; rt.swipeAnchorX = x; rt.swipeAnchorY = y; return }
        val dx = x - rt.swipeAnchorX
        val dy = y - rt.swipeAnchorY
        val horiz = mode.scrollMode != SwipeAxes.VERTICAL
        val vert = mode.scrollMode != SwipeAxes.HORIZONTAL
        if (horiz && kotlin.math.abs(dx) >= mode.threshold && kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
            pulseOutput(if (dx > 0) mode.right else mode.left); rt.swipeAnchorX = x; rt.swipeAnchorY = y
        } else if (vert && kotlin.math.abs(dy) >= mode.threshold) {
            pulseOutput(if (dy > 0) mode.up else mode.down); rt.swipeAnchorX = x; rt.swipeAnchorY = y // pad +Y up
        }
    }

    /** Fire an edge output as a one-shot press+release pulse (for swipe / one-shot commands). */
    private fun pulseOutput(out: ScOutput?) { if (out != null && out != ScOutput.None) { pressOutput(out); releaseOutput(out) } }

    private fun applyPadMouse(mode: PadMode.Mouse, rt: PadRuntime, touched: Boolean, x: Int, y: Int) {
        if (!touched) { rt.mouseActive = false; return }
        if (!rt.mouseActive) {
            rt.mouseActive = true; rt.lastX = x; rt.lastY = y; rt.accumX = 0f; rt.accumY = 0f; rt.smoothX = 0f; rt.smoothY = 0f
            return
        }
        // Trailing-anchor deadzone (a "rubber band"): [lastX]/[lastY] is the anchor, not the previous report. While
        // the finger stays within [floor] raw units of the anchor it's treated as resting → no motion (this kills
        // resting jitter that a per-report gate leaks through, since a noise spike measured from a *fixed* anchor
        // stays small). Once the finger moves past the floor, emit only the OVERSHOOT (distance beyond the
        // deadzone) and slide the anchor to trail the finger by [floor] — so sustained movement tracks ~1:1 while a
        // brief spike leaks only a pixel or two. The global "Touchpad deadzone" ([padDeadzone]) sets the radius.
        val dxRaw = (x - rt.lastX).toFloat()
        val dyRaw = (y - rt.lastY).toFloat() // raw pad space (+Y up); screen-Y handled at emit
        val floor = mode.jitterFloor.toFloat()  // per-pad (was globally overridden by ScTuningStore deadzone)
        val dist = hypot(dxRaw, dyRaw)
        if (dist < floor) return
        val ux = dxRaw / dist
        val uy = dyRaw / dist
        rt.lastX = (x - ux * floor).roundToInt()        // anchor trails the finger by the deadzone radius
        rt.lastY = (y - uy * floor).roundToInt()
        val over = dist - floor
        var rawMoveX = ux * over * mode.sensitivity * mode.horizScale
        var rawMoveY = (if (mode.invertY) -uy else uy) * over * mode.sensitivity * mode.vertScale
        if (mode.rotation != 0f) { // Rotate Output: spin the pointer-delta vector.
            val r = Math.toRadians(mode.rotation.toDouble())
            val c = kotlin.math.cos(r).toFloat(); val sn = kotlin.math.sin(r).toFloat()
            val rx = rawMoveX * c - rawMoveY * sn; val ry = rawMoveX * sn + rawMoveY * c
            rawMoveX = rx; rawMoveY = ry
        }
        // Optional low-pass (per-pad smoothing): EMA the motion to damp frame-to-frame jitter (costs a little lag).
        val a = ScTuningStore.emaAlpha(mode.smoothing)
        rt.smoothX = rt.smoothX * (1f - a) + rawMoveX * a
        rt.smoothY = rt.smoothY * (1f - a) + rawMoveY * a
        // Accumulate scaled motion so sub-pixel movement isn't truncated away; emit the integer part, keep remainder.
        rt.accumX += rt.smoothX
        rt.accumY += rt.smoothY
        val dx = rt.accumX.toInt()
        val dy = rt.accumY.toInt()
        if (dx != 0 || dy != 0) {
            sink.mouseMove(dx, dy)
            rt.accumX -= dx; rt.accumY -= dy
        }
    }

    /** Button Pad / ToME grid: the cell under the finger is active while the activating bit is set. */
    private fun applyPadGrid(mode: PadMode.ButtonPadGrid, rt: PadRuntime, active: Boolean, x: Int, y: Int) {
        if (!active) {
            if (rt.gridCell >= 0) { releaseOutput(mode.cells.getOrNull(rt.gridCell)); rt.gridCell = -1 }
            return
        }
        // pad position -> normalized 0..1 (nx left->right, ny bottom->top, since pad +Y is up)
        val nx = (x.toFloat() / 65536f + 0.5f).coerceIn(0f, 0.999f)
        val ny = (y.toFloat() / 65536f + 0.5f).coerceIn(0f, 0.999f)
        val col = (nx * mode.cols).toInt().coerceIn(0, mode.cols - 1)
        val row = (ny * mode.rows).toInt().coerceIn(0, mode.rows - 1)
        val cell = row * mode.cols + col
        if (cell != rt.gridCell) {
            releaseOutput(mode.cells.getOrNull(rt.gridCell))
            rt.gridCell = cell
            pressOutput(mode.cells.getOrNull(cell))
        }
    }

    private fun releaseGridCell(mode: PadMode, rt: PadRuntime) {
        if (rt.gridCell >= 0 && mode is PadMode.ButtonPadGrid) releaseOutput(mode.cells.getOrNull(rt.gridCell))
        rt.gridCell = -1
    }

    /** Press an edge-style output (keys / mouse buttons). Gamepad/dpad/none cells are not edge-pressable. */
    private fun pressOutput(out: ScOutput?) {
        when (out) {
            is ScOutput.Key -> out.keys.forEach { sink.key(it, true) }
            is ScOutput.MouseButton -> sink.mouseButton(out.button, true)
            is ScOutput.MouseNudge -> if (out.dx != 0 || out.dy != 0) sink.mouseMove(out.dx, out.dy) // one-shot move
            is ScOutput.MousePosition -> sink.mouseMoveAbs(out.nx, out.ny) // one-shot absolute warp
            else -> {}
        }
    }

    private fun releaseOutput(out: ScOutput?) {
        when (out) {
            is ScOutput.Key -> out.keys.asReversed().forEach { sink.key(it, false) }
            is ScOutput.MouseButton -> sink.mouseButton(out.button, false)
            else -> {}
        }
    }

    private fun gyroGateOpen(gate: GyroGate, s: TritonState): Boolean = when (gate) {
        GyroGate.ALWAYS -> true
        GyroGate.LEFT_GRIP -> s.has(TritonProtocol.BTN_LGRIP)
        GyroGate.RIGHT_GRIP -> s.has(TritonProtocol.BTN_RGRIP)
        GyroGate.EITHER_GRIP -> s.has(TritonProtocol.BTN_LGRIP) || s.has(TritonProtocol.BTN_RGRIP)
        GyroGate.LEFT_PAD_TOUCH -> s.has(TritonProtocol.BTN_LPAD_TOUCH)
        GyroGate.RIGHT_PAD_TOUCH -> s.has(TritonProtocol.BTN_RPAD_TOUCH)
        GyroGate.LEFT_STICK_TOUCH -> s.has(TritonProtocol.BTN_LSTICK_TOUCH)
        GyroGate.RIGHT_STICK_TOUCH -> s.has(TritonProtocol.BTN_RSTICK_TOUCH)
        GyroGate.ANY_TOUCH -> s.has(TritonProtocol.BTN_LPAD_TOUCH) || s.has(TritonProtocol.BTN_RPAD_TOUCH) ||
            s.has(TritonProtocol.BTN_LSTICK_TOUCH) || s.has(TritonProtocol.BTN_RSTICK_TOUCH)
        GyroGate.ALL_TOUCH -> s.has(TritonProtocol.BTN_LPAD_TOUCH) && s.has(TritonProtocol.BTN_RPAD_TOUCH) &&
            s.has(TritonProtocol.BTN_LSTICK_TOUCH) && s.has(TritonProtocol.BTN_RSTICK_TOUCH)
        GyroGate.L4 -> s.has(TritonProtocol.BTN_L4)
        GyroGate.L5 -> s.has(TritonProtocol.BTN_L5)
        GyroGate.R4 -> s.has(TritonProtocol.BTN_R4)
        GyroGate.R5 -> s.has(TritonProtocol.BTN_R5)
        GyroGate.LEFT_BUMPER -> s.has(TritonProtocol.BTN_LBUMPER)
        GyroGate.RIGHT_BUMPER -> s.has(TritonProtocol.BTN_RBUMPER)
        GyroGate.A -> s.has(TritonProtocol.BTN_A)
        GyroGate.B -> s.has(TritonProtocol.BTN_B)
        GyroGate.X -> s.has(TritonProtocol.BTN_X)
        GyroGate.Y -> s.has(TritonProtocol.BTN_Y)
        GyroGate.L3 -> s.has(TritonProtocol.BTN_L3)
        GyroGate.R3 -> s.has(TritonProtocol.BTN_R3)
    }

    /** Whether the gyro is aiming this frame, combining the [gate] input with its [activation] behavior:
     *  ENABLE = while held, SUPPRESS = while NOT held, TOGGLE = flip on each press edge. */
    private fun gyroActive(gate: GyroGate, activation: GyroActivation, s: TritonState): Boolean {
        val open = gyroGateOpen(gate, s)
        return when (activation) {
            GyroActivation.ENABLE -> open
            // ALWAYS has no gate button, so "suppress" has nothing to suppress with → gyro just stays on.
            GyroActivation.SUPPRESS -> gate == GyroGate.ALWAYS || !open
            GyroActivation.TOGGLE -> {
                if (open && !gyroTogglePrevOpen) gyroToggleOn = !gyroToggleOn // flip on the press edge
                gyroTogglePrevOpen = open
                gyroToggleOn
            }
        }
    }

    /** Shape a raw joystick axis value in [-1,1]: power curve on the magnitude (0.1 aggressive … 1 linear … 4 relaxed),
     *  then rescale into [GyroMode.Joystick.outputMin]..[outputMax]. Sign preserved. */
    private fun shapeJoy(v: Float, mode: GyroMode.Joystick): Float {
        if (v == 0f) return 0f
        var t = abs(v).coerceIn(0f, 1f)
        if (mode.powerCurve != 1f) t = Math.pow(t.toDouble(), mode.powerCurve.toDouble()).toFloat()
        val out = mode.outputMin + t * (mode.outputMax - mode.outputMin)
        return if (v < 0) -out else out
    }

    private fun applyGyro(mode: GyroMode, s: TritonState) {
        when (mode) {
            is GyroMode.Mouse -> {
                if (gyroActive(mode.gate, mode.activation, s)) {
                    // Natural default: yaw-right → aim-right, pitch-up → aim-up (negate the raw sign, which felt
                    // backwards on device). invertX/invertY flip each axis back.
                    val sx = if (mode.invertX) 1f else -1f
                    val sy = if (mode.invertY) 1f else -1f
                    val gz = s.gyroZ.toFloat(); val gx = s.gyroX.toFloat()
                    val speed = hypot(gx, gz)                                   // rotation speed (raw units)
                    if (mode.speedDeadzone <= 0f || speed >= mode.speedDeadzone) { // below speed deadzone → no output
                        var sens = mode.sensitivity
                        // Precision: below precisionSpeed, scale sensitivity down proportionally (fine aim).
                        if (mode.precisionSpeed > 0f && speed < mode.precisionSpeed) sens *= speed / mode.precisionSpeed
                        // Acceleration: scale sensitivity UP with speed (fast flicks turn further).
                        if (mode.accel.gain > 0f) sens *= 1f + mode.accel.gain * (speed / GYRO_ACCEL_REF).coerceIn(0f, 1f)
                        // H/V mixer: >0 reduces horizontal, <0 reduces vertical (0 = 1:1).
                        val hScale = if (mode.hvMixer > 0f) 1f - mode.hvMixer else 1f
                        val vScale = if (mode.hvMixer < 0f) 1f + mode.hvMixer else 1f
                        val dx = (sx * gz * sens * hScale).toInt()
                        val dy = (sy * gx * sens * vScale).toInt()
                        if (dx != 0 || dy != 0) sink.mouseMove(dx, dy)
                    }
                }
            }
            is GyroMode.Joystick -> {
                // ADDED on top of any physical stick when gated open (so gyro layers onto stick-look rather than
                // fighting it); no-op when gated closed. Camera = rate→deflection (returns to center at rest);
                // deflection = integrated angle→held position (stays put until you rotate back).
                val open = gyroActive(mode.gate, mode.activation, s)
                val sx = if (mode.invertX) 1f else -1f
                val sy = if (mode.invertY) 1f else -1f
                val x: Float; val y: Float
                if (mode.deflection) {
                    // Integrate rate while gated; reset the accumulated angle to center when released (ratchet, so
                    // gyro drift can't accumulate). ponytail: raw-count integration, no dt — sensitivity is the
                    // per-game feel knob (matches camera's frame-rate model); switch to dt if frame rate drifts.
                    if (open) {
                        gyroDefX = (gyroDefX + sx * s.gyroZ * mode.sensitivity).coerceIn(-1f, 1f)
                        gyroDefY = (gyroDefY + sy * s.gyroX * mode.sensitivity).coerceIn(-1f, 1f)
                    } else { gyroDefX = 0f; gyroDefY = 0f }
                    x = gyroDefX; y = gyroDefY
                } else {
                    if (!open) return
                    x = (sx * s.gyroZ * mode.sensitivity).coerceIn(-1f, 1f)
                    y = (sy * s.gyroX * mode.sensitivity).coerceIn(-1f, 1f)
                }
                // Output shaping: power curve + output range per axis, then optional lock-at-edges magnitude clamp.
                var ox = shapeJoy(x, mode); var oy = shapeJoy(y, mode)
                if (mode.lockAtEdges) {
                    val mag = hypot(ox, oy)
                    if (mag > mode.outputMax && mag > 0f) { val k = mode.outputMax / mag; ox *= k; oy *= k }
                }
                if (mode.stick == Stick.LEFT) {
                    gp.thumbLX = (gp.thumbLX + ox).coerceIn(-1f, 1f); gp.thumbLY = (gp.thumbLY + oy).coerceIn(-1f, 1f)
                } else {
                    gp.thumbRX = (gp.thumbRX + ox).coerceIn(-1f, 1f); gp.thumbRY = (gp.thumbRY + oy).coerceIn(-1f, 1f)
                }
            }
            GyroMode.None -> {}
        }
    }

    private fun axis(raw: Int, deadzone: Float): Float {
        val v = (raw / 32767f).coerceIn(-1f, 1f)
        return if (abs(v) < deadzone) 0f else v
    }

    /** Stick deflection magnitude 0..~1 (for menu activation thresholds). */
    private fun stickMag(x: Int, y: Int): Float {
        val nx = x / 32768f; val ny = y / 32768f
        return kotlin.math.sqrt(nx * nx + ny * ny)
    }

    /**
     * Deadzoned axis with a [curve] applied to the magnitude beyond the deadzone (sign preserved). LINEAR is
     * intentionally identical to [axis] (no rescale) so the golden-trace regression stays valid; non-linear
     * curves rescale the post-deadzone range to 0..1 before shaping.
     */
    private fun axisCurved(raw: Int, deadzone: Float, curve: ResponseCurve): Float {
        val v = (raw / 32767f).coerceIn(-1f, 1f)
        val a = abs(v)
        if (a < deadzone) return 0f
        if (curve == ResponseCurve.LINEAR) return v
        val scaled = ((a - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        val shaped = curve.apply(scaled).coerceIn(0f, 1f)
        return if (v < 0) -shaped else shaped
    }
}
