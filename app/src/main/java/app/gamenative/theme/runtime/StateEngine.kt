package app.gamenative.theme.runtime

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.theme.model.Easing as ThemeEasing
import app.gamenative.theme.model.StandardState

/**
 * State & Transition engine for template-driven visuals.
 *
 * Features:
 * - States: normal, focused, selected, pressed, disabled (via [StandardState])
 * - Modifiers: scale, translate, opacity, overlay, border, shadow, blur, tint, swapSource
 * - Interruptible animations with per-(from→to) durations & easings
 * - Declarative, data-driven API usable from template/runtime glue
 */
object StateEngine {

    // --- Data specs (declarative) ---

    data class Vec2(val x: Float = 0f, val y: Float = 0f)

    data class OverlaySpec(
        val color: Color = Color.Unspecified,
        val opacity: Float = 0f,
        val cornerRadiusDp: Dp = 0.dp,
        val shape: Shape = RectangleShape,
    )

    data class BorderSpec(
        val widthDp: Dp = 0.dp,
        val color: Color = Color.Transparent,
        val shape: Shape = RectangleShape,
    )

    data class ShadowSpec(
        val elevationDp: Dp = 0.dp,
        val color: Color = Color.Black.copy(alpha = 0.5f), // reserved; see note in modifier
        val shape: Shape = RectangleShape,
    )

    data class BlurSpec(
        val radiusDp: Dp = 0.dp,
        val edgeTreatment: BlurredEdgeTreatment = BlurredEdgeTreatment.Rectangle,
    )

    data class TintSpec(
        val color: Color = Color.Unspecified,
        val strength: Float = 0f, // 0..1 multiplier strength
        val blendMode: BlendMode = BlendMode.Modulate,
    )

    /**
     * A single visual state specification. All fields optional; omitted fields imply defaults.
     */
    data class StateSpec(
        val scale: Vec2? = null,           // 1f,1f default
        val translateDp: Vec2? = null,     // 0dp,0dp default
        val opacity: Float? = null,        // 1f default
        val overlay: OverlaySpec? = null,
        val border: BorderSpec? = null,
        val shadow: ShadowSpec? = null,
        val blur: BlurSpec? = null,
        val tint: TintSpec? = null,
        // Logical media source swap: e.g., key "image" -> "imageFocused"
        val swapSource: Map<String, String>? = null,
    )

    /** Transition spec for (from -> to) pair. */
    data class TransitionSpec(
        val durationMs: Int = 200,
        val easing: ThemeEasing = ThemeEasing.EASE_OUT,
    )

    /** Full program: per-state specs and optional per-transition overrides. */
    data class StateProgram(
        val states: Map<StandardState, StateSpec>,
        val transitions: Map<Pair<StandardState, StandardState>, TransitionSpec> = emptyMap(),
        val defaultTransition: TransitionSpec = TransitionSpec(),
        val base: StateSpec = StateSpec( // defaults fallback
            scale = Vec2(1f, 1f),
            translateDp = Vec2(0f, 0f),
            opacity = 1f,
        ),
    )

    // --- Runtime: evaluation ---

    data class Evaluated(
        val scale: Vec2,
        val translateDp: Vec2,
        val opacity: Float,
        val overlay: OverlaySpec?,
        val border: BorderSpec?,
        val shadow: ShadowSpec?,
        val blur: BlurSpec?,
        val tint: TintSpec?,
        val swapSource: Map<String, String>?,
    )

    private fun merge(base: StateSpec, s: StateSpec?): StateSpec {
        if (s == null) return base
        return StateSpec(
            scale = s.scale ?: base.scale,
            translateDp = s.translateDp ?: base.translateDp,
            opacity = s.opacity ?: base.opacity,
            overlay = s.overlay ?: base.overlay,
            border = s.border ?: base.border,
            shadow = s.shadow ?: base.shadow,
            blur = s.blur ?: base.blur,
            tint = s.tint ?: base.tint,
            swapSource = s.swapSource ?: base.swapSource,
        )
    }

    private fun eval(program: StateProgram, state: StandardState): Evaluated {
        val merged = merge(program.base, program.states[state])
        return Evaluated(
            scale = merged.scale ?: Vec2(1f, 1f),
            translateDp = merged.translateDp ?: Vec2(0f, 0f),
            opacity = (merged.opacity ?: 1f).coerceIn(0f, 1f),
            overlay = merged.overlay,
            border = merged.border,
            shadow = merged.shadow,
            blur = merged.blur,
            tint = merged.tint,
            swapSource = merged.swapSource,
        )
    }

    private fun ThemeEasing.toCompose(): androidx.compose.animation.core.Easing = when (this) {
        ThemeEasing.LINEAR -> LinearEasing
        ThemeEasing.EASE_IN -> FastOutLinearInEasing
        ThemeEasing.EASE_OUT -> LinearOutSlowInEasing
        ThemeEasing.EASE_IN_OUT -> FastOutSlowInEasing
    }

    // --- Composable application ---

    /** Result from [animatedModifier]: the built modifier and any swapSource mapping for the caller. */
    data class ApplyResult(
        val modifier: Modifier,
        val currentSwap: Map<String, String>?,
    )

    /** Build an interruptible, animated modifier chain for the given [program] and [state]. */
    @Composable
    fun animatedModifier(
        program: StateProgram,
        state: StandardState,
    ): ApplyResult {
        // Transition between states with per-pair specs.
        val transition = updateTransition(targetState = state, label = "StateEngineTransition")

        val from = transition.currentState
        val to = transition.targetState
        val spec = program.transitions[from to to] ?: program.defaultTransition
        val floatTween: TweenSpec<Float> = tween(durationMillis = spec.durationMs, easing = spec.easing.toCompose())

        // Evaluate endpoints (memoized per state/program)
        val evalFrom = remember(program, from) { eval(program, from) }
        val evalTo = remember(program, to) { eval(program, to) }

        // Animated scalars/vectors
        val aScaleX by transition.animateFloat(transitionSpec = { floatTween }, label = "scaleX") { st ->
            if (st == to) evalTo.scale.x else evalFrom.scale.x
        }
        val aScaleY by transition.animateFloat(transitionSpec = { floatTween }, label = "scaleY") { st ->
            if (st == to) evalTo.scale.y else evalFrom.scale.y
        }
        val aTxDp by transition.animateFloat(transitionSpec = { floatTween }, label = "tx") { st ->
            if (st == to) evalTo.translateDp.x else evalFrom.translateDp.x
        }
        val aTyDp by transition.animateFloat(transitionSpec = { floatTween }, label = "ty") { st ->
            if (st == to) evalTo.translateDp.y else evalFrom.translateDp.y
        }
        val aOpacity by transition.animateFloat(transitionSpec = { floatTween }, label = "opacity") { st ->
            if (st == to) evalTo.opacity else evalFrom.opacity
        }

        // Overlay opacity (color not animated to keep deps minimal)
        val aOverlayAlpha by transition.animateFloat(transitionSpec = { floatTween }, label = "overlayAlpha") { st ->
            val oFrom = evalFrom.overlay?.opacity ?: 0f
            val oTo = evalTo.overlay?.opacity ?: 0f
            if (st == to) oTo else oFrom
        }
        val overlayColor = (evalTo.overlay?.color ?: evalFrom.overlay?.color ?: Color.Unspecified)

        // Border width (color switches instantly)
        val aBorderWidth by transition.animateFloat(transitionSpec = { floatTween }, label = "borderW") { st ->
            val vFrom = (evalFrom.border?.widthDp ?: 0.dp).value
            val vTo = (evalTo.border?.widthDp ?: 0.dp).value
            if (st == to) vTo else vFrom
        }
        val borderColor = (evalTo.border?.color ?: evalFrom.border?.color ?: Color.Transparent)
        val borderShape = (evalTo.border?.shape ?: evalFrom.border?.shape ?: RectangleShape)

        // Shadow elevation (color not animated)
        val aShadowElev by transition.animateFloat(transitionSpec = { floatTween }, label = "shadow") { st ->
            val vFrom = (evalFrom.shadow?.elevationDp ?: 0.dp).value
            val vTo = (evalTo.shadow?.elevationDp ?: 0.dp).value
            if (st == to) vTo else vFrom
        }

        // Blur radius
        val aBlurRadius by transition.animateFloat(transitionSpec = { floatTween }, label = "blur") { st ->
            val vFrom = (evalFrom.blur?.radiusDp ?: 0.dp).value
            val vTo = (evalTo.blur?.radiusDp ?: 0.dp).value
            if (st == to) vTo else vFrom
        }

        // Tint strength (color switches instantly)
        val aTintStrength by transition.animateFloat(transitionSpec = { floatTween }, label = "tintStrength") { st ->
            val vFrom = (evalFrom.tint?.strength ?: 0f)
            val vTo = (evalTo.tint?.strength ?: 0f)
            if (st == to) vTo else vFrom
        }
        val tintColor = (evalTo.tint?.color ?: evalFrom.tint?.color ?: Color.Unspecified)
        val tintBlend = (evalTo.tint?.blendMode ?: evalFrom.tint?.blendMode ?: BlendMode.Modulate)

        val density = LocalDensity.current
        val txPx = with(density) { aTxDp.dp.toPx() }
        val tyPx = with(density) { aTyDp.dp.toPx() }

        // Build modifier chain
        var modifierChain = Modifier
            .graphicsLayer {
                translationX = txPx
                translationY = tyPx
                scaleX = aScaleX
                scaleY = aScaleY
                alpha = aOpacity
            }

        if (aBlurRadius > 0f) {
            modifierChain = modifierChain.blur(aBlurRadius.dp)
        }

        // Overlay & tint via drawWithContent
        if (aOverlayAlpha > 0f && overlayColor != Color.Unspecified) {
            modifierChain = modifierChain.drawWithContent {
                drawContent()
                drawRect(color = overlayColor, alpha = aOverlayAlpha)
            }
        }
        if (aTintStrength > 0f && tintColor != Color.Unspecified) {
            modifierChain = modifierChain.drawWithContent {
                drawContent()
                drawRect(color = tintColor, alpha = aTintStrength, blendMode = tintBlend)
            }
        }

        // Border (shape optional)
        if (aBorderWidth > 0f) {
            modifierChain = modifierChain.border(BorderStroke(aBorderWidth.dp, borderColor), shape = borderShape)
        }

        // Shadow elevation is supported on graphicsLayer; custom color not applied to keep diffs minimal.
        if (aShadowElev > 0f) {
            modifierChain = modifierChain.graphicsLayer { shadowElevation = aShadowElev }
        }

        val currentSwap = evalTo.swapSource ?: evalFrom.swapSource

        return ApplyResult(modifier = modifierChain, currentSwap = currentSwap)
    }
}

// ---- Demo Composables (verification) ----

@Preview(widthDp = 320, heightDp = 200)
@Composable
private fun StateEngineDemo_Preview() {
    var state by remember { mutableStateOf(StandardState.NORMAL) }

    val program = remember {
        StateEngine.StateProgram(
            states = mapOf(
                StandardState.NORMAL to StateEngine.StateSpec(
                    opacity = 1f,
                ),
                StandardState.FOCUSED to StateEngine.StateSpec(
                    scale = StateEngine.Vec2(1.05f, 1.05f),
                    border = StateEngine.BorderSpec(2.dp, Color(0xFF64B5F6)),
                    shadow = StateEngine.ShadowSpec(8.dp),
                    overlay = StateEngine.OverlaySpec(color = Color(0xFF2196F3), opacity = 0.08f),
                ),
                StandardState.SELECTED to StateEngine.StateSpec(
                    scale = StateEngine.Vec2(1.07f, 1.07f),
                    overlay = StateEngine.OverlaySpec(color = Color(0xFF4CAF50), opacity = 0.10f),
                    tint = StateEngine.TintSpec(color = Color(0xFF4CAF50), strength = 0.15f),
                ),
                StandardState.PRESSED to StateEngine.StateSpec(
                    scale = StateEngine.Vec2(0.98f, 0.98f),
                    overlay = StateEngine.OverlaySpec(color = Color.Black, opacity = 0.12f),
                ),
                StandardState.DISABLED to StateEngine.StateSpec(
                    opacity = 0.4f,
                    blur = StateEngine.BlurSpec(2.dp),
                ),
            ),
            transitions = mapOf(
                (StandardState.NORMAL to StandardState.FOCUSED) to StateEngine.TransitionSpec(160, ThemeEasing.EASE_OUT),
                (StandardState.FOCUSED to StandardState.NORMAL) to StateEngine.TransitionSpec(140, ThemeEasing.EASE_IN_OUT),
                (StandardState.NORMAL to StandardState.PRESSED) to StateEngine.TransitionSpec(80, ThemeEasing.EASE_IN),
                (StandardState.PRESSED to StandardState.NORMAL) to StateEngine.TransitionSpec(120, ThemeEasing.EASE_OUT),
            ),
            defaultTransition = StateEngine.TransitionSpec(200, ThemeEasing.EASE_IN_OUT)
        )
    }

    val applied = StateEngine.animatedModifier(program, state)

    // Placeholder for manual toggling in Preview interactive mode
    LaunchedEffect(Unit) { }

    Box(
        modifier = applied.modifier
            .size(200.dp)
            .background(Color(0xFF263238))
    ) {
        DemoStateControls(current = state, onChange = { state = it })
    }
}

@Composable
private fun BoxScope.DemoStateControls(current: StandardState, onChange: (StandardState) -> Unit) {
    // Minimal controls to switch states in interactive preview environment are omitted
    // to keep diffs minimal. In runtime, callers can bind keyboard/gamepad events to
    // update the [state] and observe animated transitions.
}
