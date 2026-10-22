package app.gamenative.steamcontroller

import com.winlator.inputcontrols.ExternalController
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

/**
 * The Steam Controller mapping profile model + its interpreter's data types. A [ScProfile] describes how
 * each physical SC source (buttons, sticks, trackpads, triggers, gyro) maps to GameNative outputs
 * (virtual XInput pad, mouse, keys). [ProfileInterpreter] consumes a decoded [TritonState] + a profile and
 * drives GameNative's injection seams.
 *
 * This replaces the hardcoded map that lived in TritonMapper. The shipped default ([ScProfile.default]) is
 * expressed *in this model*, so the interpreter is exercised end-to-end and behaviour is identical to the
 * old hardcoded path. The sealed mode/output types intentionally carry one case each for now; later build
 * steps add cases (pad grid, d-pad, scroll, activators, action sets) without changing the interpreter's
 * shape. See docs/STEAM-INPUT-FEATURES.md (build order) and docs/SC-MAPPING-ENGINE.md.
 */

/** What a single digital (button-like) SC bit emits. Gamepad outputs are level-based; key/mouse are edge-based. */
sealed class ScOutput {
    /** No output (explicitly unbound). */
    object None : ScOutput()
    /** A virtual XInput pad button, by ExternalController.IDX_BUTTON_* index. Driven by current pressed state. */
    data class GamepadButton(val idx: Int) : ScOutput()
    /** A virtual XInput d-pad direction: 0=up, 1=right, 2=down, 3=left (GamepadState.dpad order). */
    data class GamepadDpad(val index: Int) : ScOutput()
    /** A mouse button, pressed/released on the bit's edges. */
    data class MouseButton(val button: Pointer.Button) : ScOutput()
    /**
     * A keyboard output: one key, or a combo (modifiers + key) held together. On press all keys go down in
     * order; on release they come up in reverse order. A single key is just a one-element list.
     */
    data class Key(val keys: List<XKeycode>) : ScOutput() {
        constructor(key: XKeycode) : this(listOf(key))
    }
    /**
     * Switch the active **action set** (Steam `CHANGE_PRESET`). Fired on the binding's edge: [onRelease] = false
     * presses-edge (e.g. `Start_Press` → enter a menu set), true = release-edge (e.g. the menu set's same button
     * → return). The "hold for menus" feel comes from each set re-binding the button (enter on press in set A,
     * leave on release in set B), so no momentary state is needed. Handled by [ProfileInterpreter] only when an
     * [ScConfig] is installed; a no-op otherwise. [targetSetId] is the Steam preset id.
     */
    data class SwitchActionSet(val targetSetId: String, val onRelease: Boolean = false) : ScOutput()
    /**
     * Push/pop an action **layer** (Steam `add_layer`/`hold_layer`/`remove_layer`). A layer is a partial overlay
     * (another preset) merged over the active set — see [mergeProfiles]. [op] picks the behaviour: ADD = push
     * until removed, REMOVE = pop, HOLD = push while the button is held (popped on release). [layerId] = Steam
     * preset id. Handled by [ProfileInterpreter] only when an [ScConfig] is installed.
     */
    data class LayerOp(val layerId: String, val op: LayerOpType) : ScOutput()
    /**
     * Mode-shift (Steam `mode_shift <source> <groupId>`): while this button is held, a single [source] (e.g.
     * "left_trackpad") momentarily uses an alternate group's mode/bindings. [groupId] is the raw group id; its
     * decoded single-source overlay lives in [ScConfig.shiftOverlays] and is merged over the active profile only
     * while held ([mergeProfiles] with `layerSources={source}`). Handled by [ProfileInterpreter] with a config.
     */
    data class ModeShift(val source: String, val groupId: String) : ScOutput()
    /** Toggle the on-screen split-trackpad keyboard (Steam `controller_action SHOW_KEYBOARD`). Handled by
     *  [ProfileInterpreter]: on the press edge it flips keyboard mode (pads drive the keyboard, see [ScKeyboard]). */
    object ShowKeyboard : ScOutput()
    /** Open GameNative's in-game QuickMenu (and let the controller navigate it). On the press edge the
     *  [ProfileInterpreter] calls [ScUiBridge.openQuickMenu]; while any menu/editor is up the interpreter routes
     *  controller input to Android focus-nav keys instead of the game. Default-bound to the Steam button. */
    object OpenQuickMenu : ScOutput()
    /** A one-shot relative mouse nudge (Steam `controller_action mouse_delta dx dy`): emits a single
     *  [ScOutputSink.mouseMove] on the press edge. */
    data class MouseNudge(val dx: Int, val dy: Int) : ScOutput()
    /** Warp the cursor to an absolute screen position (Steam `controller_action MOUSE_POSITION x y return`):
     *  on the press edge emits [ScOutputSink.mouseMoveAbs] to ([nx],[ny]) (screen fraction 0..1). ([returnAfter]
     *  auto-return-after is not yet honored — needs the sink to read the current position first.) */
    data class MousePosition(val nx: Float, val ny: Float, val returnAfter: Boolean = false) : ScOutput()
    /**
     * A macro (Steam's repeated same-type activators): a sequence of [commands] played **once on press**
     * (one-shot; holding doesn't repeat, releasing doesn't interrupt). Commands run in order, each framed by its
     * own `delay_start`/`delay_end`; a command's [MacroCommand.outputs] are pressed **together** (a chord) for the
     * step. Sub-outputs may be keys/mouse/gamepad. Played by [ProfileInterpreter] via its timed scheduler.
     */
    data class Macro(val commands: List<MacroCommand>) : ScOutput()
}

/** One step of an [ScOutput.Macro]: [outputs] are held together for the step, framed by the per-command delays (ms). */
data class MacroCommand(val outputs: List<ScOutput>, val delayStartMs: Long = 0, val delayEndMs: Long = 0)

enum class LayerOpType { ADD, HOLD, REMOVE }

/**
 * Merge a [layer] (partial overlay) over a [base] profile. The layer overrides exactly the sources it binds —
 * [layerSources] is the set of `group_source_bindings` sources the layer defines (e.g. "right_trackpad",
 * "joystick"); every other source falls through to [base]. Button bits the layer binds win per-bit (covers the
 * digital sources button_diamond/switch/dpad + clicks). This is Steam's action-layer semantics: a layer changes
 * only what it touches and leaves the rest of the active set intact.
 */
fun mergeProfiles(base: ScProfile, layer: ScProfile, layerSources: Set<String>): ScProfile {
    fun has(src: String) = src in layerSources
    return ScProfile(
        name = base.name,
        buttons = base.buttons + layer.buttons,
        leftStick = if (has("joystick")) layer.leftStick else base.leftStick,
        rightStick = if (has("right_joystick")) layer.rightStick else base.rightStick,
        leftPad = if (has("left_trackpad")) layer.leftPad else base.leftPad,
        rightPad = if (has("right_trackpad")) layer.rightPad else base.rightPad,
        leftTrigger = if (has("left_trigger")) layer.leftTrigger else base.leftTrigger,
        rightTrigger = if (has("right_trigger")) layer.rightTrigger else base.rightTrigger,
        gyro = if (has("gyro")) layer.gyro else base.gyro,
        haptics = base.haptics,
    )
}

/**
 * A whole controller config = several named action sets ([sets], keyed by Steam **preset id** since that's what
 * `CHANGE_PRESET`/[ScOutput.SwitchActionSet] targets) plus which one is active at launch ([defaultSetId]).
 * Produced by `SteamControllerProfileImporter.importConfig`; consumed by [ProfileInterpreter] to drive
 * config-defined action-set switching. (Action *layers* / mode-shift / chord are later build-step-3 additions.)
 */
class ScConfig(
    val sets: Map<String, ScProfile>,
    val defaultSetId: String,
    /** Per-preset-id set of `group_source_bindings` sources it defines — drives action-layer merging ([mergeProfiles]). */
    val setSources: Map<String, Set<String>> = emptyMap(),
    /** Decoded single-source overlays for [ScOutput.ModeShift], keyed by the target group id. */
    val shiftOverlays: Map<String, ScProfile> = emptyMap(),
) {
    /** The set to start in (falls back to any set, then an empty profile). */
    fun defaultProfile(): ScProfile = sets[defaultSetId] ?: sets.values.firstOrNull() ?: ScProfile()
}

/**
 * Per-binding press logic (Steam's "activators"). Only affects edge outputs (Key / MouseButton); gamepad
 * button/d-pad outputs are always level (Regular). "Pulse" = a quick press+release in one update.
 */
sealed class Activator {
    /** Press on down, release on up (default). */
    object Regular : Activator()
    /** Pulse only if pressed twice within [windowMs]; a single press does nothing. */
    data class DoublePress(val windowMs: Long = 300) : Activator()
    /** Press once held for [holdMs] (stays held until physical release). */
    data class LongPress(val holdMs: Long = 500) : Activator()
    /** While held, pulse every [intervalMs] (rapid fire). */
    data class Turbo(val intervalMs: Long = 80) : Activator()
    /** Fire (a quick pulse) on the **release** edge — Steam's `release` activator ("do X when you let go"). */
    object OnRelease : Activator()
}

/**
 * A digital binding: an [output] plus the [activator] press-logic that drives it. Steam per-binding settings
 * (edge outputs only, for now): [delayStartMs]/[delayEndMs] = Fire Start/End Delay (ms; the output press/release
 * is deferred, and "fires anyway" even if the button is released during the start delay); [toggle] = the press
 * latches the output on, the next press latches it off.
 */
data class Binding(
    val output: ScOutput,
    val activator: Activator = Activator.Regular,
    val delayStartMs: Long = 0,
    val delayEndMs: Long = 0,
    val toggle: Boolean = false,
)

/** Which XInput trigger axis a physical SC trigger's analog value drives. */
enum class TriggerAxis { NONE, GAMEPAD_L2, GAMEPAD_R2 }

/** What a physical trigger does. */
sealed class TriggerMode {
    /** Analog trigger -> an XInput trigger axis (the default). */
    data class Axis(val axis: TriggerAxis) : TriggerMode()
    /**
     * Soft/full-pull staging: crossing [softThreshold] fires [soft], crossing [fullThreshold] fires [full]
     * (both can be held; e.g. soft = aim, full = shoot). Optionally also drive an analog [axis].
     */
    data class Staged(
        val soft: ScOutput,
        val full: ScOutput,
        val softThreshold: Float = 0.4f,
        val fullThreshold: Float = 0.9f,
        val axis: TriggerAxis = TriggerAxis.NONE,
    ) : TriggerMode()
}

/** Analog thumbstick source mode. */
sealed class StickMode {
    object None : StickMode()
    /** Drive a virtual XInput stick. [invertY] matches XInput's up-is-positive convention. [curve] shapes the
     *  magnitude response (linear/aggressive/relaxed). */
    data class JoystickMove(
        val stick: Stick,
        val invertY: Boolean = true,
        val deadzone: Float = 0.12f,
        val curve: ResponseCurve = ResponseCurve.LINEAR,
    ) : StickMode()
    /** Stick → relative mouse (Steam `joystick_mouse`/`mouse_joystick`): deflection drives pointer velocity.
     *  [sensitivity] = px per update at full deflection; [deadzone] ignores rest jitter; [curve] shapes response. */
    data class Mouse(
        val sensitivity: Float = 12f,
        val deadzone: Float = 0.10f,
        val invertY: Boolean = false,
        val curve: ResponseCurve = ResponseCurve.LINEAR,
    ) : StickMode()
    /** Flick stick (Steam `flickstick`): a simplified approximation — horizontal stick deflection → yaw mouse
     *  velocity. NOTE: not the true flick-and-rotate model; see docs/RISKS.md. [sensitivity] = px/update at full. */
    data class FlickStick(val sensitivity: Float = 20f, val deadzone: Float = 0.20f) : StickMode()
    /** Radial menu driven by the **stick** (e.g. ToME4 "Movement (Radial)"): deflection angle selects a slot;
     *  [activation] HOLD (default for sticks — hold the slot's output while pointed, like a movement radial) or
     *  COMMIT (pulse on return-to-center). The HUD shows while deflected past [deadzone], fades on center.
     *  [center] = the Steam radial's center button (`touch_menu_button_0`, render-only here); [directional] = a
     *  movement radial whose 8 ring slots are labelled by 8-way arrow (↑↗→↘↓↙←↖) instead of their bound key. */
    data class RadialMenu(
        val slots: List<MenuSlot>,
        val activation: MenuActivation = MenuActivation.HOLD,
        val deadzone: Float = 0.35f,
        val center: MenuSlot? = null,
        val directional: Boolean = false,
    ) : StickMode()
    /** Touch/grid menu driven by the stick: deflection picks a grid cell. [activation]/[deadzone] as [RadialMenu]. */
    data class TouchMenu(val slots: List<MenuSlot>, val cols: Int, val rows: Int, val activation: MenuActivation = MenuActivation.HOLD, val deadzone: Float = 0.35f) : StickMode()
    /** Stick as an 8-way d-pad (Steam stick `dpad`): deflection past [deadzone] presses the matching edge output(s)
     *  (diagonals press two); recenters to neutral. Same 8-way logic as [PadMode.DPad] but always live (no touch gate). */
    data class DPad(
        val up: ScOutput, val down: ScOutput, val left: ScOutput, val right: ScOutput,
        val deadzone: Float = 0.35f,
        val layout: DpadLayout = DpadLayout.EIGHT_WAY,
        /** CROSS_GATE dead-diagonal band width, normalized 0..1 (Steam `overlap_region`/32768; def 4000 ≈ 0.122). */
        val overlap: Float = 4000f / 32768f,
    ) : StickMode()
}

/**
 * How a Radial/Touch menu commits its highlighted slot.
 * - [COMMIT]: select while interacting, fire (pulse) once when interaction ends (release/center) or on click —
 *   hotbar/quick-select menus.
 * - [HOLD]: hold the highlighted slot's output while pointed; release when the highlight changes or interaction
 *   ends — movement radials ("hold a direction").
 */
enum class MenuActivation { COMMIT, HOLD }

enum class Stick { LEFT, RIGHT }

/** Steam d-pad `layout`: how deflection maps to the 4 directions.
 *  - [EIGHT_WAY] (0): each axis independent → diagonals press two directions (the default).
 *  - [FOUR_WAY] (1): only the dominant axis fires → no diagonals.
 *  - [ANALOG_EMU] (2): Steam emits an analog stick; our d-pad outputs are key/mouse edges, so there's nothing analog
 *    to emit → falls back to [EIGHT_WAY] (ponytail: no analog output path for key binds; revisit if a dpad→stick bind exists).
 *  - [CROSS_GATE] (3): cardinal only, with a dead diagonal band ([overlap] wide) so near-diagonals press nothing. */
enum class DpadLayout { EIGHT_WAY, FOUR_WAY, ANALOG_EMU, CROSS_GATE;
    companion object { fun fromVdf(v: Int) = when (v) { 1 -> FOUR_WAY; 2 -> ANALOG_EMU; 3 -> CROSS_GATE; else -> EIGHT_WAY } }
}

/** Response curve applied to an analog magnitude (0..1) before output. Approximates Steam's curve presets. */
enum class ResponseCurve { LINEAR, AGGRESSIVE, RELAXED, WIDE, EXTRA_WIDE;
    fun apply(m: Float): Float = when (this) {
        LINEAR -> m
        AGGRESSIVE -> m * m            // slow near center, fast at edge
        RELAXED -> kotlin.math.sqrt(m) // fast near center
        WIDE -> m * m * m
        EXTRA_WIDE -> m * 0.5f
    }
}

/** Trackpad touch-motion source mode. (Pad CLICK is a separate digital bit handled via [ScProfile.buttons].) */
sealed class PadMode {
    object None : PadMode()
    /**
     * Relative mouse: finger drag -> pointer delta. [sensitivity] = pad-units-to-pixels divisor reciprocal.
     * [jitterFloor] (raw pad units) gates out resting-finger noise so the cursor doesn't crawl when the finger is
     * still; sub-[jitterFloor] per-report deltas are ignored. Sub-pixel motion is accumulated (not truncated away)
     * so slow drags stay smooth. Raise [jitterFloor] if the cursor still wanders at rest; lower it if slow aiming
     * feels dead.
     */
    data class Mouse(
        val sensitivity: Float, val invertY: Boolean = true, val jitterFloor: Int = 24,
        /** Rotate Output (Steam `rotation`, −180..180°): rotate the pointer-delta vector. */
        val rotation: Float = 0f,
        /** Per-axis output scale (Steam `sensitivity_horiz_scale`/`_vert_scale` %/100; the H/V Output Mixer). */
        val horizScale: Float = 1f, val vertScale: Float = 1f,
        /** Per-pad motion low-pass (0–100); was the global "Touchpad smoothing". Higher = smoother/laggier. */
        val smoothing: Int = ScTuningStore.DEFAULT_SMOOTHING,
    ) : PadMode()
    /**
     * Button Pad / ToME grid: pad split into [cols]×[rows]; the cell under the finger fires its output.
     * [cells] is row-major, **row 0 = BOTTOM, col 0 = LEFT** (`cells[row*cols + col]`); pad with fewer cells
     * than `cols*rows` leaves the rest unbound. [onClick] = fire on pad CLICK (else on TOUCH). Cell outputs
     * currently support Key and MouseButton (the ToME 4×4 use case).
     */
    data class ButtonPadGrid(
        val cols: Int,
        val rows: Int,
        val cells: List<ScOutput>,
        val onClick: Boolean = false,
    ) : PadMode()
    /**
     * Pad as an 8-way d-pad: while touched, the finger direction past [deadzone] presses the matching
     * output(s) (diagonals press two). Outputs are edge-style (Key / MouseButton) — e.g. pad → WASD.
     */
    data class DPad(
        val up: ScOutput,
        val down: ScOutput,
        val left: ScOutput,
        val right: ScOutput,
        val deadzone: Float = 0.35f,
        val layout: DpadLayout = DpadLayout.EIGHT_WAY,
        /** CROSS_GATE dead-diagonal band width, normalized 0..1 (Steam `overlap_region`/32768; def 4000 ≈ 0.122). */
        val overlap: Float = 4000f / 32768f,
    ) : PadMode()
    /** Finger slide → scroll wheel: every [step] pad-units of vertical travel emits one wheel click. */
    data class ScrollWheel(val step: Int = 6000, val invertY: Boolean = false) : PadMode()
    /**
     * Trackpad as a virtual thumbstick (Steam pad `joystick_move`): the finger's absolute position on the pad =
     * stick deflection while touched, recentering (zero) on lift. Reuses the stick joystick math ([deadzone]/
     * [curve]/[invertY]); [stick] selects which XInput stick to drive.
     */
    data class Joystick(
        val stick: Stick,
        val invertY: Boolean = true,
        val deadzone: Float = 0.12f,
        val curve: ResponseCurve = ResponseCurve.LINEAR,
    ) : PadMode()
    /**
     * Radial Menu (Steam `radial_menu`): while the pad is touched, the finger **angle** highlights one of
     * [slots] arranged in a ring (slot 0 at top/12-o'clock, clockwise); committing fires that slot's binding as
     * a pulse. [onClick] = commit on pad click; else commit on touch-release ("point and release"). The visual
     * ring is the step-6 overlay; this is the source-independent selection logic (works "blind"). Slot labels are
     * kept for the overlay. [center] = Steam's `touch_menu_button_0` center button (render-only); [directional] =
     * a movement radial whose 8 ring slots are labelled by 8-way arrow instead of their bound key.
     */
    data class RadialMenu(
        val slots: List<MenuSlot>,
        val onClick: Boolean = false,
        val activation: MenuActivation = MenuActivation.COMMIT,
        val center: MenuSlot? = null,
        val directional: Boolean = false,
    ) : PadMode()
    /**
     * Touch Menu (Steam `touch_menu`): the pad is split into a [cols]×[rows] grid (row 0 = TOP), the cell under
     * the finger highlights a slot, committing fires it. [onClick]/release semantics as [RadialMenu]. Same grid
     * the overlay (step 6) will draw.
     */
    data class TouchMenu(val slots: List<MenuSlot>, val cols: Int, val rows: Int, val onClick: Boolean = false, val activation: MenuActivation = MenuActivation.COMMIT) : PadMode()
    /**
     * Absolute / region mouse (Steam `absolute_mouse` / `mouse_region`): while the pad is touched, the finger's
     * absolute position maps 1:1 onto a screen [region] rectangle (normalized 0..1; the full screen = the whole
     * desktop), warping the cursor there — unlike relative [Mouse], the cursor tracks WHERE the finger is, not how
     * far it slid. The interpreter emits a normalized target ([ScOutputSink.mouseMoveAbs]); the sink scales it to
     * the X screen geometry. [left]/[top]/[right]/[bottom] bound the region (default = full screen). [invertY]
     * flips vertical (default: finger-up → cursor-up).
     */
    data class AbsoluteMouse(
        /** Region CENTER as a screen fraction (Steam `position_x/y` %/100; 0.5 = centered). */
        val centerX: Float = 0.5f, val centerY: Float = 0.5f,
        /** Region SIZE as a screen fraction (Steam `scale` %/100 × `scale_x/y` %/100; full screen = 1.0). */
        val sizeX: Float = 1f, val sizeY: Float = 1f,
        val invertX: Boolean = false, val invertY: Boolean = false,
        /** Rotate Output (Steam `rotation`, −180..180°): rotates the region-relative position vector before mapping. */
        val rotation: Float = 0f,
    ) : PadMode()
    /**
     * Mouse Joystick (Steam pad `mouse_joystick`): the pad acts like a self-centering joystick that drives the
     * MOUSE — the finger's displacement from the pad CENTER sets a cursor velocity (further out = faster), zero at
     * center, stops on lift. Unlike relative [Mouse] (drag = delta) it keeps moving while the finger is held off-centre.
     */
    data class MouseJoystick(val sensitivity: Float = 20f, val deadzone: Float = 0.10f, val invertY: Boolean = false) : PadMode()
    /**
     * Single-button pad (Steam `single_button`): touching/clicking anywhere on the pad fires [output]. No cursor,
     * no menu — the whole surface is one button. [onClick] = fire on pad CLICK (else on TOUCH).
     */
    data class SingleButton(val output: ScOutput, val onClick: Boolean = false) : PadMode()
    /**
     * Directional swipe (Steam `2dscroll`): a quick flick in a cardinal direction pulses that direction's output.
     * Unlike [DPad] (held while the finger stays deflected), a swipe fires once per flick past [threshold] then
     * requires re-centering. Outputs are edge-style (Key/MouseButton/GamepadButton). [scrollMode] limits which axes
     * are active (BOTH/HORIZONTAL/VERTICAL).
     */
    data class DirectionalSwipe(
        val up: ScOutput, val down: ScOutput, val left: ScOutput, val right: ScOutput,
        val threshold: Int = 8000, val scrollMode: SwipeAxes = SwipeAxes.BOTH,
    ) : PadMode()
    // Future: Trackball is an AbsoluteMouse/Mouse toggle, not a separate mode (see docs/STEAM-VDF-ADVANCED-SCHEMA.md).
}

/** Which axes a [PadMode.DirectionalSwipe] responds to (Steam scroll-wheel-mode). */
enum class SwipeAxes { BOTH, HORIZONTAL, VERTICAL }

/** One entry of a Radial/Touch menu: the [binding] it fires on commit + a [label] (for the step-6 overlay). */
data class MenuSlot(val binding: Binding, val label: String = "")

/** Gyro source mode. [invertX]/[invertY] flip each axis from the natural default (yaw-right→aim-right,
 *  pitch-up→aim-up); the natural default was chosen after on-device feedback that the raw sign felt backwards. */
sealed class GyroMode {
    object None : GyroMode()
    /** Gyro rate -> mouse aim delta, gated by [gate] (e.g. only while a grip is held). [activation] = what the gate
     *  button DOES (hold-to-enable / hold-to-suppress / press-to-toggle). Feel set (all in RAW gyro-rate units, tuned
     *  on-device): [speedDeadzone] = rotation speed below which there's no output (kills hand shake); [precisionSpeed]
     *  = below this speed sensitivity scales down proportionally (fine aim); [accel] scales sensitivity UP with speed
     *  (fast flicks turn further); [hvMixer] −1..+1 rebalances horizontal vs vertical (>0 reduces horizontal, <0
     *  reduces vertical, 0 = 1:1). */
    data class Mouse(
        val sensitivity: Float, val gate: GyroGate = GyroGate.EITHER_GRIP,
        val invertX: Boolean = false, val invertY: Boolean = false,
        val activation: GyroActivation = GyroActivation.ENABLE,
        val speedDeadzone: Float = 0f, val precisionSpeed: Float = 0f,
        val accel: GyroAccel = GyroAccel.OFF, val hvMixer: Float = 0f,
    ) : GyroMode()
    /** Gyro -> virtual XInput stick. Two Steam styles, distinguished by [deflection]:
     *  - **camera** (`gyro_to_joystick_camera`, [deflection]=false): gyro *rate* → stick deflection (fast spin =
     *    far push, stops at rest — velocity-like aim for stick-look games). Yaw→X, pitch→Y.
     *  - **deflection** (`gyro_to_joystick_deflection`, [deflection]=true): gyro *angle* (integrated while gated) →
     *    *held* stick position — tilt to an angle and the stick stays there until you rotate back. The accumulated
     *    angle resets to center when the gate closes (ratchet), so integration drift can't build up.
     *  Both scaled by [sensitivity], gated by [gate], output to [stick]. [activation] = what the gate button DOES.
     *  Output shaping (Steam camera/deflection settings): [powerCurve] (0.1 aggressive … 1 linear … 4 relaxed) shapes
     *  the stick magnitude; [outputMin]/[outputMax] (0..1) rescale the output range; [lockAtEdges] clamps the combined
     *  magnitude to [outputMax] (else axes reach it independently → diagonal overshoot).
     *  (Lean Left/Right roll-axis binds are deferred — need a real bound export for the vdf input name + editor UI.) */
    data class Joystick(
        val stick: Stick = Stick.RIGHT, val sensitivity: Float,
        val gate: GyroGate = GyroGate.EITHER_GRIP, val invertX: Boolean = false, val invertY: Boolean = false,
        val deflection: Boolean = false, val activation: GyroActivation = GyroActivation.ENABLE,
        val powerCurve: Float = 1f, val outputMin: Float = 0f, val outputMax: Float = 1f, val lockAtEdges: Boolean = false,
    ) : GyroMode()
}

/** When a gyro mode is active. Grips are the rear paddles; the pad/stick-touch gates enable "touch-to-aim" (gyro
 *  only while the aim surface is touched — the most common gyro-mouse style). */
enum class GyroGate {
    ALWAYS, LEFT_GRIP, RIGHT_GRIP, EITHER_GRIP, LEFT_PAD_TOUCH, RIGHT_PAD_TOUCH, LEFT_STICK_TOUCH, RIGHT_STICK_TOUCH,
    /** Gyro active while ANY trackpad OR thumbstick is touched (Steam `gyro_ratchet_button_mask` covering the four
     *  touch surfaces, require-any). The common "touch anything to aim" gate. */
    ANY_TOUCH,
    /** Gyro active only while ALL four touch surfaces are touched at once (mask covering them, require-all). */
    ALL_TOUCH,
    // Single-button gates — any button can gate the gyro, not just grips (the rear paddles + bumpers are the common
    // picks; extend as needed). ponytail: single buttons only; arbitrary multi-button CHORDS need a mask-based gate
    // (a `gateMask`/multi-select follow-up) — this covers the common "hold/toggle on <button>" case.
    L4, L5, R4, R5, LEFT_BUMPER, RIGHT_BUMPER, A, B, X, Y, L3, R3;
}

/** What the gyro gate button(s) DO (Steam "Gyro Enable/Suppress/Toggle", vdf `gyro_button`):
 *  - [ENABLE]: gyro is active *while* the gate is held (the default; a plain hold-to-aim grip).
 *  - [SUPPRESS]: gyro is active *unless* the gate is held (on by default, hold to turn OFF for a precise moment).
 *  - [TOGGLE]: each gate *press* flips gyro on/off (hands-free aim + easy ratcheting — toggle off, recenter, toggle on).
 *  With gate = [GyroGate.ALWAYS] there is no button, so [activation] is moot (gyro is simply always on). */
enum class GyroActivation { ENABLE, SUPPRESS, TOGGLE }

/** Gyro output acceleration (Steam Acceleration: Off / Linear / Relaxed / Aggressive) — scales sensitivity UP with
 *  rotation speed so quick flicks turn further. [gain] is applied as `1 + gain * (speed / ACCEL_REF)` (clamped),
 *  ACCEL_REF being a raw-speed reference tuned on-device. */
enum class GyroAccel(val gain: Float) { OFF(0f), LINEAR(1f), RELAXED(0.5f), AGGRESSIVE(2f) }

/**
 * Trackpad/haptic feel parameters — profile-driven so the binding-editor UI can expose full haptics control
 * (docs/STEAM-INPUT-FEATURES.md §8). Defaults are the values tuned-by-feel from the USBPcap capture and
 * confirmed over USB on-phone 2026-06-17.
 */
data class HapticSettings(
    val enabled: Boolean = true,
    val leftPadEnabled: Boolean = true,
    val rightPadEnabled: Boolean = true,
    val clickGain: Int = 0xFE,    // press-down click loudness (int8 dB; less-negative = louder)
    val tickGain: Int = 0xF7,     // slide detent loudness
    val detentStep: Int = 7600,   // pad-units of travel between detent ticks
    val moveNoise: Int = 220,     // per-report delta below this is jitter (ignored)
)

/**
 * A full mapping profile. Digital bits map through [buttons]; the analog sources have dedicated mode fields.
 * One action set for now; action sets/layers/mode-shift (build step 3) will wrap this.
 */
class ScProfile(
    val name: String = "Default",
    /** TritonProtocol.BTN_* bit -> binding (output + activator). Bits absent from the map are unbound. */
    val buttons: Map<Int, Binding> = emptyMap(),
    val leftStick: StickMode = StickMode.None,
    val rightStick: StickMode = StickMode.None,
    val leftPad: PadMode = PadMode.None,
    val rightPad: PadMode = PadMode.None,
    /** What each physical trigger does (analog axis by default; or soft/full staging). */
    val leftTrigger: TriggerMode = TriggerMode.Axis(TriggerAxis.GAMEPAD_L2),
    val rightTrigger: TriggerMode = TriggerMode.Axis(TriggerAxis.GAMEPAD_R2),
    val gyro: GyroMode = GyroMode.None,
    val haptics: HapticSettings = HapticSettings(),
) {
    companion object {
        /**
         * The shipped default profile (any game) — a faithful re-expression of the old hardcoded TritonMapper
         * map. Sticks/ABXY/d-pad/bumpers/L3/R3/Start/Back/triggers -> virtual XInput pad; right pad drag ->
         * mouse; right-pad click -> left mouse, left-pad click -> right mouse; gyro while a grip is held ->
         * mouse aim; 4 rear paddles -> F1-F4 placeholders. Start/Back per SDL (VIEW 0x40=Start, MENU 0x4000=Back).
         */
        fun default(): ScProfile {
            val b = TritonProtocol
            val buttons = mapOf(
                b.BTN_A to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_A.toInt()),
                b.BTN_B to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_B.toInt()),
                b.BTN_X to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_X.toInt()),
                b.BTN_Y to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_Y.toInt()),
                b.BTN_LBUMPER to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_L1.toInt()),
                b.BTN_RBUMPER to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_R1.toInt()),
                b.BTN_L3 to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_L3.toInt()),
                b.BTN_R3 to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_R3.toInt()),
                // SDL: VIEW bit (0x40) = Start, MENU bit (0x4000) = Back/Select.
                b.BTN_VIEW to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_START.toInt()),
                b.BTN_MENU to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_SELECT.toInt()),
                b.BTN_DPAD_UP to ScOutput.GamepadDpad(0),
                b.BTN_DPAD_RIGHT to ScOutput.GamepadDpad(1),
                b.BTN_DPAD_DOWN to ScOutput.GamepadDpad(2),
                b.BTN_DPAD_LEFT to ScOutput.GamepadDpad(3),
                b.BTN_LTRIG_CLICK to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_L2.toInt()),
                b.BTN_RTRIG_CLICK to ScOutput.GamepadButton(ExternalController.IDX_BUTTON_R2.toInt()),
                b.BTN_RPAD_CLICK to ScOutput.MouseButton(Pointer.Button.BUTTON_LEFT),
                b.BTN_LPAD_CLICK to ScOutput.MouseButton(Pointer.Button.BUTTON_RIGHT),
                // Rear paddles -> placeholder keys (profile-configurable later).
                b.BTN_R4 to ScOutput.Key(XKeycode.KEY_F1),
                b.BTN_R5 to ScOutput.Key(XKeycode.KEY_F2),
                b.BTN_L4 to ScOutput.Key(XKeycode.KEY_F3),
                b.BTN_L5 to ScOutput.Key(XKeycode.KEY_F4),
                // Steam/Guide button opens the GameNative QuickMenu (and lets the controller navigate it).
                b.BTN_STEAM to ScOutput.OpenQuickMenu,
                // The "..." Quick-Access (3-dots) button between the trackpads toggles the on-screen keyboard.
                b.BTN_QAM to ScOutput.ShowKeyboard,
            ).mapValues { (_, out) -> Binding(out) }  // default: all Regular activators
            return ScProfile(
                name = "Default",
                buttons = buttons,
                leftStick = StickMode.JoystickMove(Stick.LEFT, invertY = true, deadzone = 0.12f),
                rightStick = StickMode.JoystickMove(Stick.RIGHT, invertY = true, deadzone = 0.12f),
                leftPad = PadMode.None,
                rightPad = PadMode.Mouse(sensitivity = 1.0f / 70f, invertY = true),
                leftTrigger = TriggerMode.Axis(TriggerAxis.GAMEPAD_L2),
                rightTrigger = TriggerMode.Axis(TriggerAxis.GAMEPAD_R2),
                gyro = GyroMode.Mouse(sensitivity = 1.0f / 900f, gate = GyroGate.EITHER_GRIP),
                haptics = HapticSettings(),
            )
        }
    }
}
