package app.gamenative.ui.component

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest

/**
 * OutlinedTextField wrapper that sets IME_FLAG_NO_EXTRACT_UI on the EditorInfo.
 * Works around a GBoard bug where the composition overlay renders stale text
 * on mid-text deletion in Compose TextFields.
 *
 * Under controller / keyboard input, focus alone does not open the soft keyboard: A (or Enter) or a tap opens it,
 * and B closes it while focus stays on the field. Typing on a physical keyboard works either way. Touch is
 * unchanged. [showKeyboardOnFocus] opens it on any focus, for fields that are focused on purpose (a code entry
 * screen, a field reached only through its own tile's click); B and A still close and reopen it there.
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
    showKeyboardOnFocus: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current

    val resolvedOptions = if (keyboardOptions.imeAction == ImeAction.Default && singleLine) {
        keyboardOptions.copy(imeAction = ImeAction.Done)
    } else {
        keyboardOptions
    }

    val resolvedActions = keyboardActions
        ?: if (singleLine) KeyboardActions(onDone = { focusManager.clearFocus() })
        else KeyboardActions.Default

    // whether this focus may hold an input method connection (and so the soft keyboard). false until A or a tap
    // when the field was reached by controller / keyboard navigation.
    val imeAllowed = remember { mutableStateOf(showKeyboardOnFocus) }
    val hasFocus = remember { mutableStateOf(false) }
    val editable = enabled && !readOnly
    // a tap on the already focused field
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            if (it is PressInteraction.Press && hasFocus.value) imeAllowed.value = true
        }
    }

    // Ring only under controller / keyboard input: a tap also focuses a text field, and the field's own
    // outline already shows that. The ring sits just outside the whole field (label space included), so it
    // never covers the outline or strikes through the floating label in the outline's notch.
    val ringed = modifier
        .outset(RingOutset)
        .focusRing(interactionSource, shape, width = 2.dp, keyboardOnly = true)
        .padding(RingOutset)
        .onFocusChanged {
            hasFocus.value = it.isFocused
            if (!it.isFocused) imeAllowed.value = showKeyboardOnFocus
        }

    // A focused Compose text field swallows the D-pad (for cursor movement), which traps controller focus on
    // the field. Up/down are never editing keys in a single-line field, and no arrow is in a read-only one, so
    // intercept them *before* the field and turn them into focus moves. Typing is untouched.
    val gamepadModifier = ringed.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val direction = when (event.key) {
            Key.DirectionUp -> if (singleLine || readOnly) FocusDirection.Up else null
            Key.DirectionDown -> if (singleLine || readOnly) FocusDirection.Down else null
            Key.DirectionLeft -> if (readOnly) FocusDirection.Left else null
            Key.DirectionRight -> if (readOnly) FocusDirection.Right else null
            else -> null
        }
        if (direction != null) {
            focusManager.moveFocus(direction)
            return@onPreviewKeyEvent true
        }
        if (!editable) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionCenter, Key.ButtonA, Key.Enter, Key.NumPadEnter -> when {
                !imeAllowed.value -> {
                    imeAllowed.value = true
                    true
                }
                // keyboard dismissed by the system back key: A brings it back, enter still goes to the field
                (event.key == Key.DirectionCenter || event.key == Key.ButtonA) && !view.isImeVisible() -> {
                    keyboardController?.show()
                    true
                }
                else -> false
            }
            // B goes by the field's own state, not the window insets. a system back that reaches the field was not
            // taken by a shown keyboard, so it only closes one the insets report
            Key.ButtonB, Key.Back -> if (imeAllowed.value && (event.key == Key.ButtonB || view.isImeVisible())) {
                imeAllowed.value = false
                true
            } else {
                false
            }
            else -> false
        }
    }

    // focused by touch, or while the keyboard is up (ime next from another field), the field gets the keyboard
    // at once. otherwise the input method waits until A or a tap; B tears it down and it waits again.
    val interceptor = remember(inputModeManager, view) {
        PlatformTextInputInterceptor { request, nextHandler ->
            val modifiedRequest = PlatformTextInputMethodRequest { outAttributes ->
                request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI
                }
            }
            if (inputModeManager.inputMode != InputMode.Keyboard || view.isImeVisible()) imeAllowed.value = true
            snapshotFlow { imeAllowed.value }.collectLatest { allowed ->
                if (allowed) nextHandler.startInputMethod(modifiedRequest)
            }
            awaitCancellation()
        }
    }

    // under controller focus the ring is the focus mark, so the outline keeps its unfocused color (its focused
    // width is not exposed by OutlinedTextField)
    val isFocused by interactionSource.collectIsFocusedAsState()
    val fieldColors = if (isFocused && inputModeManager.inputMode == InputMode.Keyboard) {
        colors.copy(focusedIndicatorColor = colors.unfocusedIndicatorColor)
    } else {
        colors
    }

    InterceptPlatformTextInput(interceptor = interceptor) {
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
            colors = fieldColors,
        )
    }
}

private val RingOutset = 3.dp

/** A (or Enter) runs [action] on a read-only dropdown anchor field; a focused text field swallows the key otherwise. */
fun Modifier.onConfirmKey(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.ButtonA -> {
            action()
            true
        }
        else -> false
    }
}

private fun View.isImeVisible(): Boolean =
    ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/**
 * Grows the node that follows by [outset] on every side without changing this element's size or position; paired
 * with a padding of [outset] after it. focusRing draws inside its node's bounds, so this is what puts it outside.
 */
private fun Modifier.outset(outset: Dp): Modifier = layout { measurable, constraints ->
    val px = outset.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * px, 2 * px))
    layout(placeable.width - 2 * px, placeable.height - 2 * px) { placeable.place(-px, -px) }
}
