package app.gamenative.ui.component

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * OutlinedTextField wrapper that sets IME_FLAG_NO_EXTRACT_UI on the EditorInfo.
 * Works around a GBoard bug where the composition overlay renders stale text
 * on mid-text deletion in Compose TextFields.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun NoExtractOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val focusManager = LocalFocusManager.current

    val resolvedOptions = if (keyboardOptions.imeAction == ImeAction.Default && singleLine) {
        keyboardOptions.copy(imeAction = ImeAction.Done)
    } else {
        keyboardOptions
    }

    val resolvedActions = keyboardActions
        ?: if (singleLine) KeyboardActions(onDone = { focusManager.clearFocus() })
        else KeyboardActions.Default

    // Ring only under controller / keyboard input: a tap also focuses a text field, and the field's own
    // outline already shows that. With a label the bounds start above the outline, so the ring moves down.
    val labelInset = if (label != null) with(LocalDensity.current) { OutlineTopInset.toPx() } else 0f
    val ringed = modifier.focusRing(
        interactionSource,
        if (labelInset > 0f) TopInsetShape(shape, labelInset) else shape,
        width = 2.dp,
        keyboardOnly = true,
    )

    // A focused Compose text field swallows D-pad up/down (for cursor movement),
    // which traps controller focus on the field. In a single-line field up/down are
    // never used for editing, so intercept them *before* the field and turn them into
    // focus moves, letting a gamepad navigate past the field. Left/right (cursor) and
    // typing are untouched.
    val gamepadModifier = if (singleLine) {
        ringed.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionUp -> {
                    focusManager.moveFocus(FocusDirection.Up)
                    true
                }
                Key.DirectionDown -> {
                    focusManager.moveFocus(FocusDirection.Down)
                    true
                }
                else -> false
            }
        }
    } else {
        ringed
    }

    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val modifiedRequest = PlatformTextInputMethodRequest { outAttributes ->
                request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
            }
            nextHandler.startInputMethod(modifiedRequest)
        },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = gamepadModifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            prefix = prefix,
            suffix = suffix,
            supportingText = supportingText,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = resolvedOptions,
            keyboardActions = resolvedActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            interactionSource = interactionSource,
            shape = shape,
            colors = colors,
        )
    }
}

// material3's OutlinedTextFieldTopPadding: half the bodySmall line height, reserved above the outline for the label.
private val OutlineTopInset = 8.sp

/** [shape] drawn [top] px lower, over the rest of the bounds. */
private class TopInsetShape(private val shape: Shape, private val top: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        when (val o = shape.createOutline(Size(size.width, (size.height - top).coerceAtLeast(0f)), layoutDirection, density)) {
            is Outline.Rectangle -> Outline.Rectangle(o.rect.translate(0f, top))
            is Outline.Rounded -> Outline.Rounded(o.roundRect.translate(Offset(0f, top)))
            is Outline.Generic -> Outline.Generic(Path().apply { addPath(o.path, Offset(0f, top)) })
        }
}
