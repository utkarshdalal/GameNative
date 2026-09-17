package app.gamenative.steamcontroller

import com.winlator.inputcontrols.ExternalController
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Editor-facing, JSON-serializable representation of a Steam Controller mapping, plus its converter to the
 * runtime [ScProfile]. This is the data layer behind the in-app SC binding editor (newprompt "Next focus" #1b).
 *
 * Why a separate DTO instead of serializing [ScProfile] directly: [ScProfile] is built from sealed runtime
 * types ([ScOutput]/[PadMode]/[StickMode]/…) that would each need polymorphic serialization wiring. The editor
 * only authors **digital button bindings** for now (source → output + activator); the analog sources
 * (sticks/pads/triggers/gyro) are inherited from a base profile ([ScProfile.default] by default). Keeping the
 * DTO flat and string-keyed makes it trivially serializable and stable across versions.
 */

/** A bindable digital source the editor exposes, with its [TritonProtocol] bit and a human label. */
enum class ScSource(val bit: Int, val label: String, val group: String) {
    A(TritonProtocol.BTN_A, "A", "Face"),
    B(TritonProtocol.BTN_B, "B", "Face"),
    X(TritonProtocol.BTN_X, "X", "Face"),
    Y(TritonProtocol.BTN_Y, "Y", "Face"),
    LEFT_BUMPER(TritonProtocol.BTN_LBUMPER, "Left Bumper (L1)", "Bumpers"),
    RIGHT_BUMPER(TritonProtocol.BTN_RBUMPER, "Right Bumper (R1)", "Bumpers"),
    // Trigger full-pull digital clicks are folded into the Left/Right Trigger behavior editors (not shown as
    // standalone button rows), mirroring how stick/pad clicks fold into their analog surface. Group unused.
    LEFT_TRIGGER_CLICK(TritonProtocol.BTN_LTRIG_CLICK, "Left Trigger (full pull)", "Bumpers"),
    RIGHT_TRIGGER_CLICK(TritonProtocol.BTN_RTRIG_CLICK, "Right Trigger (full pull)", "Bumpers"),
    DPAD_UP(TritonProtocol.BTN_DPAD_UP, "D-Pad Up", "D-Pad"),
    DPAD_DOWN(TritonProtocol.BTN_DPAD_DOWN, "D-Pad Down", "D-Pad"),
    DPAD_LEFT(TritonProtocol.BTN_DPAD_LEFT, "D-Pad Left", "D-Pad"),
    DPAD_RIGHT(TritonProtocol.BTN_DPAD_RIGHT, "D-Pad Right", "D-Pad"),
    LEFT_STICK_CLICK(TritonProtocol.BTN_L3, "Left Stick Click (L3)", "Sticks/Pads"),
    RIGHT_STICK_CLICK(TritonProtocol.BTN_R3, "Right Stick Click (R3)", "Sticks/Pads"),
    LEFT_PAD_CLICK(TritonProtocol.BTN_LPAD_CLICK, "Left Pad Click", "Sticks/Pads"),
    RIGHT_PAD_CLICK(TritonProtocol.BTN_RPAD_CLICK, "Right Pad Click", "Sticks/Pads"),
    START(TritonProtocol.BTN_VIEW, "Start", "Menu"),
    BACK(TritonProtocol.BTN_MENU, "Back / Select", "Menu"),
    REAR_RIGHT_TOP(TritonProtocol.BTN_R4, "Rear Paddle R4", "Rear Paddles"),
    REAR_RIGHT_BOTTOM(TritonProtocol.BTN_R5, "Rear Paddle R5", "Rear Paddles"),
    REAR_LEFT_TOP(TritonProtocol.BTN_L4, "Rear Paddle L4", "Rear Paddles"),
    REAR_LEFT_BOTTOM(TritonProtocol.BTN_L5, "Rear Paddle L5", "Rear Paddles"),
    LEFT_GRIP(TritonProtocol.BTN_LGRIP, "Left Grip", "Grips"),
    RIGHT_GRIP(TritonProtocol.BTN_RGRIP, "Right Grip", "Grips"),
}

/** What kind of output a binding emits (selects which DTO fields are meaningful).
 *  [INHERIT] = "keep whatever the base profile binds for this source" — used to preserve outputs the editor can't
 *  yet author (layer ops / mode-shift / mouse-nudge / show-keyboard / open-QuickMenu) so editing a config doesn't
 *  flatten them to NONE (an explicit unbind). Distinct from [NONE], which is a user-chosen unbind. */
enum class OutputKind {
    NONE, INHERIT, KEY, GAMEPAD_BUTTON, GAMEPAD_DPAD, MOUSE_BUTTON, SWITCH_ACTION_SET, MOUSE_NUDGE,
    LAYER_OP, SHOW_KEYBOARD, OPEN_QUICK_MENU,
}

/** Editor-facing activator choices (map 1:1 to [Activator]). [DOUBLE_PRESS]/[LONG_PRESS]/[TURBO] carry a timing
 *  via [EditBinding.activatorMs]; [RELEASE] = Steam's release activator (fire on let-go). */
enum class EditActivator { REGULAR, DOUBLE_PRESS, LONG_PRESS, TURBO, RELEASE }

/** One source's binding in editor form. Only the field(s) relevant to [kind] are used. */
@Serializable
data class EditBinding(
    val kind: OutputKind = OutputKind.NONE,
    /** XKeycode names (a combo for KEY; one element for a single key). */
    val keys: List<String> = emptyList(),
    /** ExternalController.IDX_BUTTON_* index for GAMEPAD_BUTTON. */
    val gamepadIdx: Int = -1,
    /** 0=up,1=right,2=down,3=left for GAMEPAD_DPAD. */
    val dpadIndex: Int = -1,
    /** Pointer.Button name for MOUSE_BUTTON. */
    val mouseButton: String = "",
    /** Target action-set id for SWITCH_ACTION_SET (an [ScEditableSet.id]). */
    val targetSetId: String = "",
    /** One-shot relative mouse nudge (MOUSE_NUDGE) — e.g. a radial's no-op `mouse_delta 0 0` center. */
    val nudgeDx: Int = 0,
    val nudgeDy: Int = 0,
    /** LAYER_OP: target layer set id + the op (ADD/HOLD/REMOVE, a [LayerOpType] name). */
    val layerId: String = "",
    val layerOp: String = "HOLD",
    val activator: EditActivator = EditActivator.REGULAR,
    /** Activator timing in ms: double-press window / long-press hold / turbo interval. 0 = engine default. */
    val activatorMs: Int = 0,
    /** Display-only summary of an [OutputKind.INHERIT] binding (what advanced output is being preserved). */
    val inheritDesc: String = "",
) {
    fun toOutput(): ScOutput = when (kind) {
        OutputKind.NONE -> ScOutput.None
        // INHERIT carries no output of its own — callers ([ScEditableProfile.toScProfile]) skip it so the base's
        // binding survives untouched. Returning None here is purely defensive (it should never be reached).
        OutputKind.INHERIT -> ScOutput.None
        OutputKind.KEY -> ScOutput.Key(keys.mapNotNull { runCatching { XKeycode.valueOf(it) }.getOrNull() })
            .let { if (it.keys.isEmpty()) ScOutput.None else it }
        OutputKind.GAMEPAD_BUTTON -> if (gamepadIdx >= 0) ScOutput.GamepadButton(gamepadIdx) else ScOutput.None
        OutputKind.GAMEPAD_DPAD -> if (dpadIndex in 0..3) ScOutput.GamepadDpad(dpadIndex) else ScOutput.None
        OutputKind.MOUSE_BUTTON ->
            runCatching { ScOutput.MouseButton(Pointer.Button.valueOf(mouseButton)) }.getOrDefault(ScOutput.None)
        // A switch fires on press by default; the "On release" activator makes it fire on release instead — the
        // second half of a momentary hold-to-shift (set A: press → set B; set B: release → back to set A).
        OutputKind.SWITCH_ACTION_SET ->
            if (targetSetId.isNotBlank()) ScOutput.SwitchActionSet(targetSetId, onRelease = activator == EditActivator.RELEASE)
            else ScOutput.None
        OutputKind.MOUSE_NUDGE -> ScOutput.MouseNudge(nudgeDx, nudgeDy)
        OutputKind.SHOW_KEYBOARD -> ScOutput.ShowKeyboard
        OutputKind.OPEN_QUICK_MENU -> ScOutput.OpenQuickMenu
        OutputKind.LAYER_OP ->
            if (layerId.isNotBlank()) ScOutput.LayerOp(layerId, runCatching { LayerOpType.valueOf(layerOp) }.getOrDefault(LayerOpType.HOLD))
            else ScOutput.None
    }

    fun toActivator(): Activator = when (activator) {
        EditActivator.REGULAR -> Activator.Regular
        EditActivator.DOUBLE_PRESS -> Activator.DoublePress(if (activatorMs > 0) activatorMs.toLong() else 300)
        EditActivator.LONG_PRESS -> Activator.LongPress(if (activatorMs > 0) activatorMs.toLong() else 500)
        EditActivator.TURBO -> Activator.Turbo(if (activatorMs > 0) activatorMs.toLong() else 80)
        EditActivator.RELEASE -> Activator.OnRelease
    }

    companion object {
        /** Build an [EditBinding] from a bare runtime output (no activator) — used to seed D-Pad / staged-trigger
         *  outputs. A bare [Binding] carries the default [Activator.Regular], so this is exactly [Binding.toEdit]. */
        fun fromOutput(out: ScOutput): EditBinding = Binding(out).toEdit()
    }
}

/** A short human description of an advanced [ScOutput] the editor can't author yet (null = editor-authorable). Used
 *  to label [OutputKind.INHERIT] bindings so a preserved layer/mode-shift binding doesn't read as "Unbound". */
fun ScOutput.advancedDesc(): String? = when (this) {
    is ScOutput.ModeShift -> "Mode-shift $source"
    is ScOutput.MousePosition -> "Mouse position"
    is ScOutput.Macro -> "Macro (${commands.size} cmd${if (commands.size == 1) "" else "s"})"
    else -> null
}

/** Convert a runtime [Binding] to its editor form (output + activator). Advanced outputs the editor can't author
 *  become [OutputKind.INHERIT] (preserved, not flattened). Shared by button + menu-slot seeding. */
fun Binding.toEdit(): EditBinding {
    val (act, ms) = when (val a = activator) {
        is Activator.DoublePress -> EditActivator.DOUBLE_PRESS to a.windowMs.toInt()
        is Activator.LongPress -> EditActivator.LONG_PRESS to a.holdMs.toInt()
        is Activator.Turbo -> EditActivator.TURBO to a.intervalMs.toInt()
        is Activator.OnRelease -> EditActivator.RELEASE to 0
        else -> EditActivator.REGULAR to 0
    }
    return when (val o = output) {
        is ScOutput.Key -> EditBinding(OutputKind.KEY, keys = o.keys.map { it.name }, activator = act, activatorMs = ms)
        is ScOutput.GamepadButton -> EditBinding(OutputKind.GAMEPAD_BUTTON, gamepadIdx = o.idx, activator = act, activatorMs = ms)
        is ScOutput.GamepadDpad -> EditBinding(OutputKind.GAMEPAD_DPAD, dpadIndex = o.index, activator = act, activatorMs = ms)
        is ScOutput.MouseButton -> EditBinding(OutputKind.MOUSE_BUTTON, mouseButton = o.button.name, activator = act, activatorMs = ms)
        // A switch's press/release timing lives in the output's onRelease flag, not the runtime activator.
        is ScOutput.SwitchActionSet -> EditBinding(
            OutputKind.SWITCH_ACTION_SET, targetSetId = o.targetSetId,
            activator = if (o.onRelease) EditActivator.RELEASE else EditActivator.REGULAR,
        )
        is ScOutput.MouseNudge -> EditBinding(OutputKind.MOUSE_NUDGE, nudgeDx = o.dx, nudgeDy = o.dy)
        is ScOutput.ShowKeyboard -> EditBinding(OutputKind.SHOW_KEYBOARD)
        is ScOutput.OpenQuickMenu -> EditBinding(OutputKind.OPEN_QUICK_MENU)
        is ScOutput.LayerOp -> EditBinding(OutputKind.LAYER_OP, layerId = o.layerId, layerOp = o.op.name)
        // Advanced outputs (layer/mode-shift/mouse-nudge/show-keyboard/open-QuickMenu) -> INHERIT so a
        // round-trip through the editor preserves them rather than unbinding them.
        else -> o.advancedDesc()?.let { EditBinding(OutputKind.INHERIT, inheritDesc = it) }
            ?: EditBinding(OutputKind.NONE, activator = act, activatorMs = ms)
    }
}

/** One slot of an authored radial/touch/button-pad menu: a [label] (HUD) + the [binding] it fires. */
@Serializable
data class EditMenuSlot(val label: String = "", val binding: EditBinding = EditBinding()) {
    fun toMenuSlot(): MenuSlot = MenuSlot(Binding(binding.toOutput(), binding.toActivator()), label)

    companion object {
        /** Seed from a runtime [MenuSlot], or null if its output is advanced/unrepresentable (caller then inherits). */
        fun fromOrNull(s: MenuSlot): EditMenuSlot? =
            s.binding.toEdit().takeIf { it.kind != OutputKind.INHERIT }?.let { EditMenuSlot(s.label, it) }

        /** Seed from a bare [ScOutput] (button-pad cells carry no activator), or null if unrepresentable. */
        fun fromOutputOrNull(out: ScOutput): EditMenuSlot? =
            Binding(out).toEdit().takeIf { it.kind != OutputKind.INHERIT }?.let { EditMenuSlot("", it) }
    }
}

/** Editor-facing response-curve choice (maps to the runtime [ResponseCurve]). */
@Serializable
enum class EditCurve { LINEAR, AGGRESSIVE, RELAXED, WIDE;
    fun toRuntime(): ResponseCurve = when (this) {
        LINEAR -> ResponseCurve.LINEAR
        AGGRESSIVE -> ResponseCurve.AGGRESSIVE
        RELAXED -> ResponseCurve.RELAXED
        WIDE -> ResponseCurve.WIDE
    }
    companion object {
        fun from(c: ResponseCurve): EditCurve = when (c) {
            ResponseCurve.AGGRESSIVE -> AGGRESSIVE
            ResponseCurve.RELAXED -> RELAXED
            ResponseCurve.WIDE, ResponseCurve.EXTRA_WIDE -> WIDE
            else -> LINEAR
        }
    }
}

/** Parametric analog-surface behaviors the editor can author (Steam's per-surface "Behavior" dropdown, for the
 *  modes with simple settings). Menu/Button-Pad behaviors carry slot lists and are authored elsewhere
 *  (`ScMenuLabels` / `.vdf` import) — a surface left on one of those keeps its base mode (the editor shows
 *  `null` = inherit for it). */
@Serializable
enum class AnalogMode { NONE, MOUSE, JOYSTICK, FLICK_STICK, SCROLL_WHEEL, DPAD, RADIAL, TOUCH_MENU, BUTTON_PAD }

/**
 * Editor representation of one analog surface (trackpad or stick): a chosen [mode] + its settings. Convert to the
 * runtime [PadMode] / [StickMode] with [toPadMode] / [toStickMode] (each returns null when [mode] isn't valid for
 * that surface kind, so the caller keeps the base mode). Only the fields relevant to [mode] are used.
 */
@Serializable
data class EditAnalog(
    val mode: AnalogMode = AnalogMode.NONE,
    /** Mouse / flick sensitivity as a percent of the engine default (100 = default). */
    val sensitivityPct: Int = 100,
    /** Deadzone as a percent of full deflection (stick modes / stick-mouse). */
    val deadzonePct: Int = 12,
    /** Pad MOUSE touch-feel (per-pad): motion smoothing (0–100) + rest jitter floor (raw pad units). */
    val smoothingPct: Int = ScTuningStore.DEFAULT_SMOOTHING,
    val jitterFloor: Int = 24,
    val invertY: Boolean = false,
    val curve: EditCurve = EditCurve.LINEAR,
    /** JOYSTICK: which virtual XInput stick to drive ("LEFT"/"RIGHT"). */
    val outputStick: String = "RIGHT",
    /** SCROLL_WHEEL: pad-units of travel per wheel click. */
    val scrollStep: Int = 6000,
    /** MOUSE (relative pad): Rotate Output (deg) + per-axis H/V output scale (1.0 = 100%). */
    val mouseRotation: Float = 0f,
    val mouseHorizScale: Float = 1f,
    val mouseVertScale: Float = 1f,
    /** DPAD: the four directional outputs. */
    val up: EditBinding = EditBinding(),
    val down: EditBinding = EditBinding(),
    val left: EditBinding = EditBinding(),
    val right: EditBinding = EditBinding(),
    /** DPAD: layout mode name (EIGHT_WAY / FOUR_WAY / ANALOG_EMU / CROSS_GATE) + normalized cross-gate band. */
    val dpadLayout: String = "EIGHT_WAY",
    val dpadOverlap: Float = 4000f / 32768f,
    // ── Menu modes (RADIAL / TOUCH_MENU / BUTTON_PAD) ──
    /** The menu's slots (ring order for RADIAL; row-major for TOUCH_MENU/BUTTON_PAD). */
    val slots: List<EditMenuSlot> = emptyList(),
    /** Grid dimensions for TOUCH_MENU / BUTTON_PAD (ignored by RADIAL). */
    val menuCols: Int = 0,
    val menuRows: Int = 0,
    /** Commit on pad CLICK (else on touch/release). Pad menus only. */
    val menuOnClick: Boolean = false,
    /** true = HOLD activation (hold the slot while pointed), false = COMMIT (pulse on commit). */
    val menuHold: Boolean = false,
    /** RADIAL center button (`touch_menu_button_0`); null = none. */
    val menuCenter: EditMenuSlot? = null,
    /** RADIAL movement menu (8 ring slots labelled by arrows). */
    val menuDirectional: Boolean = false,
) {
    private fun menuActivation() = if (menuHold) MenuActivation.HOLD else MenuActivation.COMMIT
    private fun menuSlots() = slots.map { it.toMenuSlot() }

    fun toPadMode(): PadMode? = when (mode) {
        AnalogMode.NONE -> PadMode.None
        AnalogMode.MOUSE -> PadMode.Mouse(sensitivity = DEFAULT_PAD_MOUSE_SENS * sensitivityPct / 100f, invertY = invertY,
            jitterFloor = jitterFloor, smoothing = smoothingPct,
            rotation = mouseRotation, horizScale = mouseHorizScale, vertScale = mouseVertScale)
        AnalogMode.SCROLL_WHEEL -> PadMode.ScrollWheel(step = scrollStep, invertY = invertY)
        AnalogMode.DPAD -> PadMode.DPad(up.toOutput(), down.toOutput(), left.toOutput(), right.toOutput(), deadzone = deadzonePct / 100f,
            layout = runCatching { DpadLayout.valueOf(dpadLayout) }.getOrDefault(DpadLayout.EIGHT_WAY), overlap = dpadOverlap)
        AnalogMode.RADIAL -> PadMode.RadialMenu(
            slots = menuSlots(), onClick = menuOnClick, activation = menuActivation(),
            center = menuCenter?.toMenuSlot(), directional = menuDirectional,
        )
        AnalogMode.TOUCH_MENU -> PadMode.TouchMenu(
            slots = menuSlots(), cols = menuCols.coerceAtLeast(1), rows = menuRows.coerceAtLeast(1),
            onClick = menuOnClick, activation = menuActivation(),
        )
        AnalogMode.BUTTON_PAD -> PadMode.ButtonPadGrid(
            cols = menuCols.coerceAtLeast(1), rows = menuRows.coerceAtLeast(1),
            cells = slots.map { it.binding.toOutput() }, onClick = menuOnClick,
        )
        else -> null // JOYSTICK / FLICK_STICK are stick-only
    }

    fun toStickMode(): StickMode? = when (mode) {
        AnalogMode.NONE -> StickMode.None
        AnalogMode.JOYSTICK -> StickMode.JoystickMove(
            stick = if (outputStick == "LEFT") Stick.LEFT else Stick.RIGHT,
            invertY = invertY, deadzone = deadzonePct / 100f, curve = curve.toRuntime(),
        )
        AnalogMode.MOUSE -> StickMode.Mouse(sensitivity = DEFAULT_STICK_MOUSE_SENS * sensitivityPct / 100f, deadzone = deadzonePct / 100f, invertY = invertY, curve = curve.toRuntime())
        AnalogMode.FLICK_STICK -> StickMode.FlickStick(sensitivity = DEFAULT_FLICK_SENS * sensitivityPct / 100f, deadzone = deadzonePct / 100f)
        AnalogMode.RADIAL -> StickMode.RadialMenu(
            slots = menuSlots(), activation = menuActivation(), deadzone = deadzonePct / 100f,
            center = menuCenter?.toMenuSlot(), directional = menuDirectional,
        )
        AnalogMode.TOUCH_MENU -> StickMode.TouchMenu(
            slots = menuSlots(), cols = menuCols.coerceAtLeast(1), rows = menuRows.coerceAtLeast(1),
            activation = menuActivation(), deadzone = deadzonePct / 100f,
        )
        else -> null // SCROLL_WHEEL / DPAD / BUTTON_PAD are pad-only
    }

    companion object {
        const val DEFAULT_PAD_MOUSE_SENS = 1f / 70f
        const val DEFAULT_STICK_MOUSE_SENS = 12f
        const val DEFAULT_FLICK_SENS = 20f

        /** Seed an [EditAnalog] from a runtime pad mode, or null if it's a mode the editor doesn't author yet
         *  (menus / button-pad) — null means "inherit / leave as-is", so saving won't clobber it. */
        fun fromPad(m: PadMode): EditAnalog? = when (m) {
            is PadMode.None -> EditAnalog(AnalogMode.NONE)
            is PadMode.Mouse -> EditAnalog(AnalogMode.MOUSE, sensitivityPct = (m.sensitivity / DEFAULT_PAD_MOUSE_SENS * 100f).roundToInt(), invertY = m.invertY,
                smoothingPct = m.smoothing, jitterFloor = m.jitterFloor,
                mouseRotation = m.rotation, mouseHorizScale = m.horizScale, mouseVertScale = m.vertScale)
            // Absolute/region mouse, single-button, directional-swipe aren't authored in the editor yet → null =
            // inherit (preserved losslessly on edit; importer handles them).
            is PadMode.AbsoluteMouse -> null
            is PadMode.SingleButton -> null
            is PadMode.DirectionalSwipe -> null
            is PadMode.Joystick -> null
            is PadMode.MouseJoystick -> null
            is PadMode.ScrollWheel -> EditAnalog(AnalogMode.SCROLL_WHEEL, scrollStep = m.step, invertY = m.invertY)
            is PadMode.DPad -> EditAnalog(
                AnalogMode.DPAD, deadzonePct = (m.deadzone * 100f).roundToInt(),
                up = EditBinding.fromOutput(m.up), down = EditBinding.fromOutput(m.down),
                left = EditBinding.fromOutput(m.left), right = EditBinding.fromOutput(m.right),
                dpadLayout = m.layout.name, dpadOverlap = m.overlap,
            )
            // Menus are representable only if every slot's output is editor-authorable; otherwise null = inherit the
            // base surface untouched (keeps the overlay lossless for exotic menu bindings).
            is PadMode.RadialMenu -> EditAnalog(
                AnalogMode.RADIAL, slots = m.slots.map { EditMenuSlot.fromOrNull(it) ?: return null },
                menuOnClick = m.onClick, menuHold = m.activation == MenuActivation.HOLD,
                menuCenter = m.center?.let { EditMenuSlot.fromOrNull(it) ?: return null }, menuDirectional = m.directional,
            )
            is PadMode.TouchMenu -> EditAnalog(
                AnalogMode.TOUCH_MENU, slots = m.slots.map { EditMenuSlot.fromOrNull(it) ?: return null },
                menuCols = m.cols, menuRows = m.rows, menuOnClick = m.onClick, menuHold = m.activation == MenuActivation.HOLD,
            )
            is PadMode.ButtonPadGrid -> EditAnalog(
                AnalogMode.BUTTON_PAD, slots = m.cells.map { EditMenuSlot.fromOutputOrNull(it) ?: return null },
                menuCols = m.cols, menuRows = m.rows, menuOnClick = m.onClick,
            )
        }

        fun fromStick(m: StickMode): EditAnalog? = when (m) {
            is StickMode.None -> EditAnalog(AnalogMode.NONE)
            is StickMode.JoystickMove -> EditAnalog(AnalogMode.JOYSTICK, deadzonePct = (m.deadzone * 100f).roundToInt(), invertY = m.invertY, curve = EditCurve.from(m.curve), outputStick = m.stick.name)
            is StickMode.Mouse -> EditAnalog(AnalogMode.MOUSE, sensitivityPct = (m.sensitivity / DEFAULT_STICK_MOUSE_SENS * 100f).roundToInt(), deadzonePct = (m.deadzone * 100f).roundToInt(), invertY = m.invertY, curve = EditCurve.from(m.curve))
            is StickMode.FlickStick -> EditAnalog(AnalogMode.FLICK_STICK, sensitivityPct = (m.sensitivity / DEFAULT_FLICK_SENS * 100f).roundToInt(), deadzonePct = (m.deadzone * 100f).roundToInt())
            is StickMode.RadialMenu -> EditAnalog(
                AnalogMode.RADIAL, slots = m.slots.map { EditMenuSlot.fromOrNull(it) ?: return null },
                deadzonePct = (m.deadzone * 100f).roundToInt(), menuHold = m.activation == MenuActivation.HOLD,
                menuCenter = m.center?.let { EditMenuSlot.fromOrNull(it) ?: return null }, menuDirectional = m.directional,
            )
            is StickMode.TouchMenu -> EditAnalog(
                AnalogMode.TOUCH_MENU, slots = m.slots.map { EditMenuSlot.fromOrNull(it) ?: return null },
                menuCols = m.cols, menuRows = m.rows, deadzonePct = (m.deadzone * 100f).roundToInt(),
                menuHold = m.activation == MenuActivation.HOLD,
            )
            // Stick d-pad isn't a stick option in the editor (DPAD is pad-only) -> null = inherit (preserved losslessly).
            is StickMode.DPad -> null
        }
    }
}

/** Editor representation of a physical trigger (Steam's trigger "Behavior"). AXIS = analog → an XInput trigger
 *  axis; STAGED = soft/full-pull staging (each stage fires a command at a pull threshold), optionally also an axis. */
@Serializable
enum class TriggerEditMode { AXIS, STAGED }

@Serializable
data class EditTrigger(
    val mode: TriggerEditMode = TriggerEditMode.AXIS,
    /** TriggerAxis name (NONE / GAMEPAD_L2 / GAMEPAD_R2). */
    val axis: String = "GAMEPAD_R2",
    val soft: EditBinding = EditBinding(),
    val full: EditBinding = EditBinding(),
    val softThresholdPct: Int = 40,
    val fullThresholdPct: Int = 90,
) {
    private fun axisOr(default: TriggerAxis) = runCatching { TriggerAxis.valueOf(axis) }.getOrDefault(default)

    fun toRuntime(defaultAxis: TriggerAxis): TriggerMode = when (mode) {
        TriggerEditMode.AXIS -> TriggerMode.Axis(axisOr(defaultAxis))
        TriggerEditMode.STAGED -> TriggerMode.Staged(
            soft = soft.toOutput(), full = full.toOutput(),
            softThreshold = softThresholdPct / 100f, fullThreshold = fullThresholdPct / 100f,
            axis = axisOr(TriggerAxis.NONE),
        )
    }

    companion object {
        fun from(m: TriggerMode): EditTrigger = when (m) {
            is TriggerMode.Axis -> EditTrigger(TriggerEditMode.AXIS, axis = m.axis.name)
            is TriggerMode.Staged -> EditTrigger(
                TriggerEditMode.STAGED, axis = m.axis.name,
                soft = EditBinding.fromOutput(m.soft), full = EditBinding.fromOutput(m.full),
                softThresholdPct = (m.softThreshold * 100f).roundToInt(), fullThresholdPct = (m.fullThreshold * 100f).roundToInt(),
            )
        }
    }
}

/** Editor representation of the gyro (Steam's gyro "Behavior"). OFF = disabled; MOUSE = gyro rate → mouse aim,
 *  gated by [gate] (when the gyro is active — e.g. only while a grip is held). */
@Serializable
enum class GyroEditMode { OFF, MOUSE, JOYSTICK }

@Serializable
data class EditGyro(
    val mode: GyroEditMode = GyroEditMode.MOUSE,
    val sensitivityPct: Int = 100,
    /** GyroGate name (ALWAYS / LEFT_GRIP / RIGHT_GRIP / EITHER_GRIP / LEFT_PAD_TOUCH / RIGHT_PAD_TOUCH). */
    val gate: String = "EITHER_GRIP",
    /** For JOYSTICK: which output stick (LEFT/RIGHT). */
    val outputStick: String = "RIGHT",
    /** For JOYSTICK: deflection style (angle→held position) vs the default camera style (rate→velocity). */
    val deflection: Boolean = false,
    /** GyroActivation name (ENABLE / SUPPRESS / TOGGLE) — what the gate button does. */
    val activation: String = "ENABLE",
    /** MOUSE feel set: speed deadzone + precision speed (raw gyro units), acceleration curve, H/V mixer (−100..100%). */
    val speedDeadzone: Int = 0,
    val precisionSpeed: Int = 0,
    val accel: String = "OFF",
    val hvMixerPct: Int = 0,
    /** JOYSTICK shaping: power curve (×100 → 0.1..4), output range (%), lock at edges. */
    val powerCurvePct: Int = 100,
    val outputMinPct: Int = 0,
    val outputMaxPct: Int = 100,
    val lockAtEdges: Boolean = false,
) {
    private fun gateEnum() = runCatching { GyroGate.valueOf(gate) }.getOrDefault(GyroGate.EITHER_GRIP)
    private fun activationEnum() = runCatching { GyroActivation.valueOf(activation) }.getOrDefault(GyroActivation.ENABLE)
    private fun accelEnum() = runCatching { GyroAccel.valueOf(accel) }.getOrDefault(GyroAccel.OFF)
    fun toRuntime(): GyroMode = when (mode) {
        GyroEditMode.OFF -> GyroMode.None
        GyroEditMode.MOUSE -> GyroMode.Mouse(
            sensitivity = DEFAULT_GYRO_SENS * sensitivityPct / 100f, gate = gateEnum(), activation = activationEnum(),
            speedDeadzone = speedDeadzone.toFloat(), precisionSpeed = precisionSpeed.toFloat(),
            accel = accelEnum(), hvMixer = hvMixerPct / 100f,
        )
        GyroEditMode.JOYSTICK -> GyroMode.Joystick(
            stick = runCatching { Stick.valueOf(outputStick) }.getOrDefault(Stick.RIGHT),
            sensitivity = (if (deflection) DEFAULT_GYRO_DEFLECT_SENS else DEFAULT_GYRO_JOY_SENS) * sensitivityPct / 100f,
            gate = gateEnum(), deflection = deflection, activation = activationEnum(),
            powerCurve = (powerCurvePct / 100f).coerceIn(0.1f, 4f), outputMin = outputMinPct / 100f,
            outputMax = outputMaxPct / 100f, lockAtEdges = lockAtEdges,
        )
    }

    companion object {
        const val DEFAULT_GYRO_SENS = 1f / 900f
        const val DEFAULT_GYRO_JOY_SENS = 1f / 6000f // camera: gyro rate → stick deflection
        const val DEFAULT_GYRO_DEFLECT_SENS = 1f / 60000f // deflection: integrated angle → held position
        fun from(m: GyroMode): EditGyro = when (m) {
            is GyroMode.Joystick -> EditGyro(GyroEditMode.JOYSTICK, sensitivityPct = (m.sensitivity / (if (m.deflection) DEFAULT_GYRO_DEFLECT_SENS else DEFAULT_GYRO_JOY_SENS) * 100f).roundToInt(), gate = m.gate.name, outputStick = m.stick.name, deflection = m.deflection, activation = m.activation.name,
                powerCurvePct = (m.powerCurve * 100f).roundToInt(), outputMinPct = (m.outputMin * 100f).roundToInt(), outputMaxPct = (m.outputMax * 100f).roundToInt(), lockAtEdges = m.lockAtEdges)
            is GyroMode.None -> EditGyro(GyroEditMode.OFF)
            is GyroMode.Mouse -> EditGyro(GyroEditMode.MOUSE, sensitivityPct = (m.sensitivity / DEFAULT_GYRO_SENS * 100f).roundToInt(), gate = m.gate.name, activation = m.activation.name,
                speedDeadzone = m.speedDeadzone.roundToInt(), precisionSpeed = m.precisionSpeed.roundToInt(), accel = m.accel.name, hvMixerPct = (m.hvMixer * 100f).roundToInt())
        }
    }
}

/** Editor representation of haptics: master + per-pad enable and the slide-detent spacing. (Gain dB values keep
 *  their tuned defaults — not exposed yet; see newprompt backlog "Haptics control UI".) */
@Serializable
data class EditHaptics(
    val enabled: Boolean = true,
    val leftPadEnabled: Boolean = true,
    val rightPadEnabled: Boolean = true,
    val detentStep: Int = 7600,
) {
    fun toRuntime(base: HapticSettings): HapticSettings =
        base.copy(enabled = enabled, leftPadEnabled = leftPadEnabled, rightPadEnabled = rightPadEnabled, detentStep = detentStep)

    companion object {
        fun from(h: HapticSettings) = EditHaptics(h.enabled, h.leftPadEnabled, h.rightPadEnabled, h.detentStep)
    }
}

/**
 * A whole editable profile: a name plus per-[ScSource] bindings (keyed by [ScSource.name] so the JSON is
 * stable). Sources absent from [buttons] inherit from the base profile at conversion time. The analog surfaces
 * ([leftPad]/[rightPad]/[leftStick]/[rightStick]) plus [leftTrigger]/[rightTrigger]/[gyro]/[haptics] are
 * null = inherit the base profile; set = override.
 */
@Serializable
data class ScEditableProfile(
    val name: String = "Custom",
    val buttons: Map<String, EditBinding> = emptyMap(),
    val leftPad: EditAnalog? = null,
    val rightPad: EditAnalog? = null,
    val leftStick: EditAnalog? = null,
    val rightStick: EditAnalog? = null,
    val leftTrigger: EditTrigger? = null,
    val rightTrigger: EditTrigger? = null,
    val gyro: EditGyro? = null,
    val haptics: EditHaptics? = null,
) {
    /**
     * Build a runtime [ScProfile]: start from [base] (so analog sources keep sensible defaults), then override
     * exactly the digital sources this editable profile binds. An [EditBinding] of kind NONE explicitly unbinds
     * its source (removes it from the button map).
     */
    fun toScProfile(base: ScProfile = ScProfile.default()): ScProfile {
        val overrides = HashMap(base.buttons)
        for ((srcName, eb) in buttons) {
            val src = runCatching { ScSource.valueOf(srcName) }.getOrNull() ?: continue
            // INHERIT keeps the base's binding for this source (preserves layer/mode-shift/etc the editor can't author).
            if (eb.kind == OutputKind.INHERIT) continue
            val out = eb.toOutput()
            if (out is ScOutput.None) overrides.remove(src.bit)
            else overrides[src.bit] = Binding(out, eb.toActivator())
        }
        return ScProfile(
            name = name,
            buttons = overrides,
            // Analog surfaces: a set EditAnalog overrides the base mode; null (or a mode invalid for that surface
            // kind, e.g. a pad set to JOYSTICK) falls through to the base profile's mode.
            leftStick = leftStick?.toStickMode() ?: base.leftStick,
            rightStick = rightStick?.toStickMode() ?: base.rightStick,
            leftPad = leftPad?.toPadMode() ?: base.leftPad,
            rightPad = rightPad?.toPadMode() ?: base.rightPad,
            leftTrigger = leftTrigger?.toRuntime(TriggerAxis.GAMEPAD_L2) ?: base.leftTrigger,
            rightTrigger = rightTrigger?.toRuntime(TriggerAxis.GAMEPAD_R2) ?: base.rightTrigger,
            gyro = gyro?.toRuntime() ?: base.gyro,
            haptics = haptics?.toRuntime(base.haptics) ?: base.haptics,
        )
    }

    /** Wrap as a single-action-set [ScConfig] (what the live path consumes). */
    fun toScConfig(base: ScProfile = ScProfile.default()): ScConfig =
        ScConfig(sets = mapOf("0" to toScProfile(base)), defaultSetId = "0")

    /** The `group_source_bindings` source names this profile defines (a non-null analog surface) — used to derive a
     *  layer's [ScConfig.setSources] so it overrides exactly those surfaces ([mergeProfiles]). Names must match
     *  [mergeProfiles]'s checks. */
    fun definedSources(): Set<String> = buildSet {
        if (leftStick != null) add("joystick")
        if (rightStick != null) add("right_joystick")
        if (leftPad != null) add("left_trackpad")
        if (rightPad != null) add("right_trackpad")
        if (leftTrigger != null) add("left_trigger")
        if (rightTrigger != null) add("right_trigger")
        if (gyro != null) add("gyro")
    }

    companion object {
        /** Seed an editable profile from a runtime [ScProfile] (default = the shipped default) so the editor
         *  opens showing the current bindings rather than a blank slate. */
        fun from(profile: ScProfile = ScProfile.default()): ScEditableProfile {
            val byBit = profile.buttons
            val map = LinkedHashMap<String, EditBinding>()
            for (src in ScSource.entries) {
                val b = byBit[src.bit] ?: continue
                map[src.name] = b.toEdit()
            }
            return ScEditableProfile(
                name = profile.name,
                buttons = map,
                leftPad = EditAnalog.fromPad(profile.leftPad),
                rightPad = EditAnalog.fromPad(profile.rightPad),
                leftStick = EditAnalog.fromStick(profile.leftStick),
                rightStick = EditAnalog.fromStick(profile.rightStick),
                leftTrigger = EditTrigger.from(profile.leftTrigger),
                rightTrigger = EditTrigger.from(profile.rightTrigger),
                gyro = EditGyro.from(profile.gyro),
                haptics = EditHaptics.from(profile.haptics),
            )
        }

        /** Common XInput pad buttons offered in the editor (index → label). */
        val GAMEPAD_BUTTONS: List<Pair<Int, String>> = listOf(
            ExternalController.IDX_BUTTON_A.toInt() to "Pad A",
            ExternalController.IDX_BUTTON_B.toInt() to "Pad B",
            ExternalController.IDX_BUTTON_X.toInt() to "Pad X",
            ExternalController.IDX_BUTTON_Y.toInt() to "Pad Y",
            ExternalController.IDX_BUTTON_L1.toInt() to "Pad LB",
            ExternalController.IDX_BUTTON_R1.toInt() to "Pad RB",
            ExternalController.IDX_BUTTON_L2.toInt() to "Pad LT",
            ExternalController.IDX_BUTTON_R2.toInt() to "Pad RT",
            ExternalController.IDX_BUTTON_L3.toInt() to "Pad L3",
            ExternalController.IDX_BUTTON_R3.toInt() to "Pad R3",
            ExternalController.IDX_BUTTON_START.toInt() to "Pad Start",
            ExternalController.IDX_BUTTON_SELECT.toInt() to "Pad Back",
        )

        val DPAD_DIRECTIONS: List<Pair<Int, String>> =
            listOf(0 to "Pad D-Pad Up", 1 to "Pad D-Pad Right", 2 to "Pad D-Pad Down", 3 to "Pad D-Pad Left")

        val MOUSE_BUTTONS: List<String> =
            listOf(Pointer.Button.BUTTON_LEFT.name, Pointer.Button.BUTTON_RIGHT.name, Pointer.Button.BUTTON_MIDDLE.name)
    }
}

/** One authored action set: a stable [id] (used as the [ScConfig] set key + [ScOutput.SwitchActionSet] target), a
 *  user-facing [name], and its [profile]. */
@Serializable
data class ScEditableSet(
    val id: String = "0",
    val name: String = "Set",
    val profile: ScEditableProfile = ScEditableProfile(),
    /** True = this set is an action **layer** (a partial overlay pushed by [ScOutput.LayerOp]) rather than a base
     *  action set switched to by [ScOutput.SwitchActionSet]. Drives [ScConfig.setSources] derivation. */
    val isLayer: Boolean = false,
)

/**
 * Editor model for a whole multi-action-set config (Phase 5d). Holds an ordered list of [sets] plus which one is
 * active at launch ([defaultSetId]). Converts to the runtime [ScConfig] the live path consumes. A single-set config
 * is just a list of one — and [fromSingle] migrates the legacy single-[ScEditableProfile] storage transparently.
 */
@Serializable
data class ScEditableConfig(
    val sets: List<ScEditableSet> = listOf(ScEditableSet(id = "0", name = "Default")),
    val defaultSetId: String = "0",
) {
    fun toScConfig(base: ScProfile = ScProfile.default()): ScConfig {
        val map = LinkedHashMap<String, ScProfile>()
        val sources = HashMap<String, Set<String>>()
        for (s in sets) {
            map[s.id] = s.profile.toScProfile(base)
            // A layer overrides exactly the analog surfaces it defines (buttons always merge per-bit); derive that
            // source set so [mergeProfiles] applies only those. Non-layer sets are switched-to (full replace), so
            // they need no source list.
            if (s.isLayer) sources[s.id] = s.profile.definedSources()
        }
        val def = if (sets.any { it.id == defaultSetId }) defaultSetId else (sets.firstOrNull()?.id ?: "0")
        return ScConfig(sets = map, defaultSetId = def, setSources = sources)
    }

    /** Next free numeric set id (ids are just stable strings; we mint "0","1","2",… ). */
    fun nextSetId(): String = (generateSequence(0) { it + 1 }.first { n -> sets.none { it.id == n.toString() } }).toString()

    companion object {
        /** Wrap a legacy single editable profile as a one-set config (back-compat with the old `<key>.json`). */
        fun fromSingle(p: ScEditableProfile): ScEditableConfig =
            ScEditableConfig(sets = listOf(ScEditableSet(id = "0", name = p.name.ifBlank { "Default" }, profile = p)))

        /**
         * Seed an editable config from a resolved runtime [ScConfig] (e.g. a parsed `.vdf`) so the editor opens
         * showing that config's action sets + bindings. Advanced button outputs the editor can't author yet
         * (layer ops / mode-shift / etc) seed as [OutputKind.INHERIT] (preserved, not flattened); menu/radial/
         * button-pad analog surfaces seed as null=inherit; and config-level layer-source / mode-shift-overlay
         * structures are NOT carried on the editable model. For a `.vdf`-active config the lossless path is
         * base-vdf + overlay ([ScConfigStore.saveEditableConfig]/`resolve`), which resolves this against the parsed
         * vdf as base so every inherited surface (menus, layers, mode-shift) survives editing exactly.
         */
        fun fromScConfig(cfg: ScConfig): ScEditableConfig {
            val sets = cfg.sets.entries.map { (id, profile) ->
                ScEditableSet(id = id, name = profile.name.ifBlank { "Set $id" }, profile = ScEditableProfile.from(profile))
            }
            return ScEditableConfig(
                sets = sets.ifEmpty { listOf(ScEditableSet(id = "0", name = "Default")) },
                defaultSetId = cfg.defaultSetId,
            )
        }
    }
}
