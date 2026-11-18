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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

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
    // outline already shows that. The ring sits just outside the whole field (label space included), so it
    // never covers the outline or strikes through the floating label in the outline's notch.
    val ringed = modifier
        .outset(RingOutset)
        .focusRing(interactionSource, shape, width = 2.dp, keyboardOnly = true)
        .padding(RingOutset)

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

private val RingOutset = 3.dp

/**
 * Grows the node that follows by [outset] on every side without changing this element's size or position; paired
 * with a padding of [outset] after it. focusRing draws inside its node's bounds, so this is what puts it outside.
 */
private fun Modifier.outset(outset: Dp): Modifier = layout { measurable, constraints ->
    val px = outset.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * px, 2 * px))
    layout(placeable.width - 2 * px, placeable.height - 2 * px) { placeable.place(-px, -px) }
}
