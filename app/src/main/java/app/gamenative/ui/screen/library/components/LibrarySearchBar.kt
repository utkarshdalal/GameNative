package app.gamenative.ui.screen.library.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.data.LibraryItem
import app.gamenative.data.GameSource
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Theme-aware search bar styling.
 */
data class SearchBarStyle(
    val backgroundColor: Color? = null,
    val borderRadius: Float = 16f,
    val collapsible: Boolean = false,
    /** Whether the anchor is on the right side (for collapsed icon positioning). */
    val anchorRight: Boolean = false,
    /** Full width of the search bar when expanded (for animation). */
    val expandedWidth: Dp = 0.dp,
)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
internal fun LibrarySearchBar(
    state: LibraryState,
    listState: LazyGridState,
    onSearchQuery: (String) -> Unit,
    style: SearchBarStyle = SearchBarStyle(),
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val internalSearchText = remember { MutableStateFlow(state.searchQuery) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Lambda function to provide new test to both onSearchQuery and internalSearchText
    val onSearchText: (String) -> Unit = {
        onSearchQuery(it)
        if (internalSearchText.value != it) {
            // Input text changed, so update and scroll to top
            internalSearchText.value = it
            scope.launch {
                listState.scrollToItem(0)
            }
        }
    }

    // Prevent focus by default, so it doesn't scoop up every controller input for focus
    val allowFocusing = remember { mutableStateOf(false) }
    
    // Determine if search bar should be expanded (not collapsed)
    val isExpanded = !style.collapsible || isFocused || state.searchQuery.isNotEmpty()
    
    // Background color from theme or default
    val bgColor = style.backgroundColor ?: MaterialTheme.colorScheme.surfaceVariant
    val cornerRadius = style.borderRadius.dp
    
    // Icon size for collapsed mode
    val iconSize = 48.dp
    
    // Animate width for collapsible search bar
    val targetWidth = if (style.collapsible) {
        if (isExpanded) style.expandedWidth.coerceAtLeast(200.dp) else iconSize
    } else {
        style.expandedWidth.coerceAtLeast(200.dp)
    }
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 250),
        label = "searchBarWidth"
    )
    
    // Alignment based on anchor
    val contentAlignment = if (style.anchorRight) Alignment.CenterEnd else Alignment.CenterStart

    // Modern search field with rounded corners
    Box(
        modifier = Modifier
            .then(
                if (style.collapsible) {
                    Modifier.width(animatedWidth)
                } else {
                    Modifier.fillMaxWidth()
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // When tapped, allow and request focus
                allowFocusing.value = true
                isFocused = true
            },
        contentAlignment = contentAlignment
    ) {
        if (style.collapsible) {
            // Use AnimatedContent for smooth transition between collapsed and expanded states
            AnimatedContent(
                targetState = isExpanded,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
                },
                label = "searchBarContent"
            ) { expanded ->
                if (!expanded) {
                    // Collapsed mode - show only the search icon
                    Box(
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(cornerRadius))
                            .background(bgColor)
                            .clickable {
                                allowFocusing.value = true
                                isFocused = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_description),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Expanded mode - show full search field
                    TextField(
                        value = state.searchQuery,
                        onValueChange = onSearchText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(cornerRadius))
                            .focusable(allowFocusing.value)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                isFocused = focusState.isFocused
                                if (!focusState.isFocused && state.searchQuery.isEmpty()) {
                                    // Lost focus and no text - collapse if collapsible
                                    allowFocusing.value = false
                                }
                            },
                        placeholder = {
                            Text(
                                text = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_description),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { onSearchText("") },
                                    content = {
                                        Icon(
                                            Icons.Default.Clear,
                                            contentDescription = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_clear),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                )
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = bgColor,
                            unfocusedContainerColor = bgColor,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
                    )
                    
                    // Request focus when expanded in collapsible mode
                    if (isFocused) {
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                    }
                }
            }
        } else {
            // Non-collapsible mode - always show full search field
            TextField(
                value = state.searchQuery,
                onValueChange = onSearchText,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(cornerRadius))
                    .focusable(allowFocusing.value)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                    },
                placeholder = {
                    Text(
                        text = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchText("") },
                            content = {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_clear),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = bgColor,
                    unfocusedContainerColor = bgColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
            )
        }
    }

    // The dropdown search results are handled elsewhere in the LibraryList component
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
                state = LibraryState(
                    isSearching = false,
                    appInfoList = List(5) { idx ->
                        val item = fakeAppInfo(idx)
                        LibraryItem(
                            index = idx,
                            appId = "${GameSource.STEAM.name}_${item.id}",
                            name = item.name,
                            iconHash = item.iconHash,
                        )
                    },
                ),
                listState = rememberLazyGridState(),
                onSearchQuery = { },
            )
        }
    }
}
