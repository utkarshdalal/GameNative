package app.gamenative.ui.screen.library.components

import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.launch

@Composable
fun LibrarySearchBar(
    isVisible: Boolean,
    searchQuery: String,
    resultCount: Int,
    listState: LazyGridState,
    onSearchQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
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
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            Color.Transparent,
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
                    listState = listState,
                    onSearchQuery = onSearchQuery,
                    onDismiss = onDismiss,
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

@Composable
private fun SearchBarInput(
    searchQuery: String,
    listState: LazyGridState,
    onSearchQuery: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var editTextRef by remember { mutableStateOf<EditText?>(null) }
    var isFocused by remember { mutableStateOf(false) }

    // Request focus when search bar appears
    LaunchedEffect(editTextRef) {
        editTextRef?.requestFocus()
    }

    val onSearchText: (String) -> Unit = { newText ->
        onSearchQuery(newText)
        scope.launch {
            listState.scrollToItem(0)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ),
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(24.dp),
                    )
                } else {
                    Modifier
                },
            )
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
                .clickable { onDismiss() },
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

        // Text input using AndroidView with EditText
        // This allows setting IME_FLAG_NO_EXTRACT_UI to prevent fullscreen keyboard in landscape
        // TODO: there must be a better way of doing this
        val textColor = MaterialTheme.colorScheme.onSurface
        val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        val cursorColor = MaterialTheme.colorScheme.primary
        val placeholderText = stringResource(R.string.library_search_placeholder)

        AndroidView(
            factory = { context ->
                EditText(context).apply {
                    // Prevent fullscreen keyboard (extract mode) in landscape
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_SEARCH
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    isSingleLine = true
                    hint = placeholderText
                    background = ColorDrawable(android.graphics.Color.TRANSPARENT)
                    setPadding(0, 0, 0, 0)

                    // Set colors
                    setTextColor(textColor.toArgb())
                    setHintTextColor(hintColor.toArgb())

                    // Text size matching bodyLarge
                    textSize = 16f

                    // Handle search action
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                            keyboardController?.hide()
                            true
                        } else {
                            false
                        }
                    }

                    // Handle D-pad navigation
                    setOnKeyListener { v, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN &&
                            keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        ) {
                            keyboardController?.hide()
                            // Use native focus search to find next focusable view below
                            val nextFocus = v.focusSearch(View.FOCUS_DOWN)
                            nextFocus?.requestFocus()
                            true
                        } else {
                            false
                        }
                    }

                    // Text change listener
                    doAfterTextChanged { editable ->
                        onSearchText(editable?.toString() ?: "")
                    }

                    // Focus change listener for border highlight
                    setOnFocusChangeListener { _, hasFocus ->
                        isFocused = hasFocus
                    }

                    // Store reference for focus management
                    editTextRef = this
                }
            },
            update = { editText ->
                // Only update if text differs
                if (editText.text.toString() != searchQuery) {
                    editText.setText(searchQuery)
                    editText.setSelection(searchQuery.length)
                }

                // Update colors in case theme changes
                editText.setTextColor(textColor.toArgb())
                editText.setHintTextColor(hintColor.toArgb())
            },
            modifier = Modifier
                .weight(1f)
                .height(24.dp),
        )

        // Clear button
        if (searchQuery.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onSearchText("") },
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
                listState = rememberLazyGridState(),
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
                listState = rememberLazyGridState(),
                onSearchQuery = { },
                onDismiss = { },
            )
        }
    }
}
