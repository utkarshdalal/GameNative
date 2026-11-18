package app.gamenative.ui.screen.library.components

import android.view.inputmethod.EditorInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.LocalBarBlur
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.component.ringClickable
import app.gamenative.ui.component.themedBar
import app.gamenative.ui.component.themedHeaderOrNull
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.scaleAlpha
import kotlinx.coroutines.launch

@Composable
fun LibrarySearchBar(
    isVisible: Boolean,
    searchQuery: String,
    resultCount: Int,
    onScrollToTop: suspend () -> Unit,
    onSearchQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateDown: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            expandFrom = Alignment.Top,
        ) + fadeIn(),
        exit = shrinkVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(),
        modifier = modifier,
    ) {
        // Gradient background container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    themedHeaderOrNull()?.let { Modifier.themedBar(it, contentBelow = true, blurState = LocalBarBlur.current) }
                        ?: Modifier.background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    PluviaTheme.colors.bar.scaleAlpha(0.98f),
                                    PluviaTheme.colors.bar.scaleAlpha(0.85f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                .padding(top = 8.dp, bottom = 20.dp, start = 12.dp, end = 12.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchBarInput(
                    searchQuery = searchQuery,
                    onScrollToTop = onScrollToTop,
                    onSearchQuery = onSearchQuery,
                    onDismiss = onDismiss,
                    onNavigateDown = onNavigateDown,
                )

                // Results count
                if (searchQuery.isNotEmpty()) {
                    Text(
                        text = if (resultCount == 1) {
                            stringResource(R.string.search_results_one, resultCount)
                        } else {
                            stringResource(R.string.search_results_many, resultCount)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchBarInput(
    searchQuery: String,
    onScrollToTop: suspend () -> Unit,
    onSearchQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    onNavigateDown: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val currentOnSearchQuery = rememberUpdatedState(onSearchQuery)
    val currentOnScrollToTop = rememberUpdatedState(onScrollToTop)
    val currentOnNavigateDown = rememberUpdatedState(onNavigateDown)
    val fieldInteraction = remember { MutableInteractionSource() }
    val isFocused by fieldInteraction.collectIsFocusedAsState()
    val fieldFocusRequester = remember { FocusRequester() }
    var fieldValue by remember { mutableStateOf(TextFieldValue(searchQuery, TextRange(searchQuery.length))) }

    // Request focus when search bar appears
    LaunchedEffect(Unit) {
        try {
            fieldFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {}
    }

    // the query is only changed from outside to clear it
    LaunchedEffect(searchQuery) {
        if (searchQuery.isEmpty() && fieldValue.text.isNotEmpty()) fieldValue = TextFieldValue()
    }

    val onSearchText: (String) -> Unit = { newText ->
        if (fieldValue.text != newText) fieldValue = TextFieldValue(newText, TextRange(newText.length))
        currentOnSearchQuery.value(newText)
        scope.launch {
            currentOnScrollToTop.value()
        }
    }

    Row(
        modifier = Modifier
            // up from the results lands on the field, not the geometrically nearer close button
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection == FocusDirection.Up) fieldFocusRequester.requestFocus()
                }
            }
            .focusGroup()
            .fillMaxWidth()
            .clip(PluviaTheme.shape(24.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ),
            )
            .focusRing(fieldInteraction, PluviaTheme.shape(24.dp), width = 2.dp, keyboardOnly = true)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Back/Close button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .ringClickable(shape = CircleShape) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.library_search_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        // Search icon
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )

        val textColor = MaterialTheme.colorScheme.onSurface
        val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        val cursorColor = MaterialTheme.colorScheme.primary
        val textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor, fontSize = 16.sp)

        // no fullscreen (extract) keyboard in landscape
        InterceptPlatformTextInput(
            interceptor = { request, nextHandler ->
                val modifiedRequest = PlatformTextInputMethodRequest { outAttributes ->
                    request.createInputConnection(outAttributes).also {
                        outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    }
                }
                nextHandler.startInputMethod(modifiedRequest)
            },
        ) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { newValue ->
                    val changed = newValue.text != fieldValue.text
                    fieldValue = newValue
                    if (changed) onSearchText(newValue.text)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocusRequester)
                    // before the soft keyboard, which can take D-pad down to hide itself: the same press hides
                    // it and moves focus to the first result
                    .onInterceptKeyBeforeSoftKeyboard { event ->
                        if (event.key != Key.DirectionDown) return@onInterceptKeyBeforeSoftKeyboard false
                        if (event.type == KeyEventType.KeyDown) {
                            keyboardController?.hide()
                            currentOnNavigateDown.value()
                        }
                        true
                    },
                textStyle = textStyle,
                singleLine = true,
                cursorBrush = SolidColor(cursorColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                interactionSource = fieldInteraction,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (fieldValue.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.library_search_placeholder),
                                style = textStyle.copy(color = hintColor),
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }

        // Clear button
        if (searchQuery.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .ringClickable(shape = CircleShape) { onSearchText("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.library_search_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_LibrarySearchBar() {
    val context = LocalContext.current
    PrefManager.init(context)
    PluviaTheme {
        Surface {
            LibrarySearchBar(
                isVisible = true,
                searchQuery = "Balatro",
                resultCount = 5,
                onScrollToTop = { },
                onSearchQuery = { },
                onDismiss = { },
            )
        }
    }
}

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_LibrarySearchBar_Empty() {
    val context = LocalContext.current
    PrefManager.init(context)
    PluviaTheme {
        Surface {
            LibrarySearchBar(
                isVisible = true,
                searchQuery = "",
                resultCount = 0,
                onScrollToTop = { },
                onSearchQuery = { },
                onDismiss = { },
            )
        }
    }
}
