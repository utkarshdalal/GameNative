package app.gamenative.ui.screen.library.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import app.gamenative.theme.runtime.LocalSpatialFocusManager
import app.gamenative.theme.runtime.SpatialFocusManager
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
    /** Text and icon color, null = use default theme colors. */
    val textColor: Color? = null,
    val borderRadius: Float = 16f,
    val collapsible: Boolean = false,
    /** Whether the anchor is on the right side (for collapsed icon positioning). */
    val anchorRight: Boolean = false,
    /** Full width of the search bar when expanded (for animation). */
    val expandedWidth: Dp = 0.dp,
    /** Height of the search bar. */
    val height: Dp = 56.dp,
    // Highlight styling for controller navigation (theme-only, null = no highlight)
    /** Highlight border color, null = no highlight border (non-theme mode). */
    val highlightColor: Color? = null,
    /** Highlight border opacity (0.0 - 1.0). */
    val highlightOpacity: Float = 0.8f,
    /** Highlight border width. */
    val highlightBorderWidth: Dp = 2.dp,
    /** Highlight transition animation duration in milliseconds. */
    val highlightTransitionSpeed: Int = 200,
    /** 
     * Navigation ID for this element. Used for registration with SpatialFocusManager
     * and for navigation references from other elements.
     */
    val navigationId: String = "search-bar",
    // Navigation overrides for controller navigation (theme-only)
    /** Element navigationId to navigate to when pressing UP, null = use spatial navigation. */
    val navigateUp: String? = null,
    /** Element navigationId to navigate to when pressing DOWN, null = use spatial navigation. */
    val navigateDown: String? = null,
    /** Element navigationId to navigate to when pressing LEFT, null = use spatial navigation. */
    val navigateLeft: String? = null,
    /** Element navigationId to navigate to when pressing RIGHT, null = use spatial navigation. */
    val navigateRight: String? = null,
) {
    /**
     * Convert navigation properties to NavigationLinks for the SpatialFocusManager.
     */
    fun toNavigationLinks() = SpatialFocusManager.NavigationLinks(
        up = navigateUp,
        down = navigateDown,
        left = navigateLeft,
        right = navigateRight,
    )
}

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
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    
    // Spatial focus manager for position-based navigation (null in non-theme mode)
    val spatialFocusManager = LocalSpatialFocusManager.current

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
    
    // Track if user has activated the search (clicked/tapped) - separate from focus highlight
    var isActivated by remember { mutableStateOf(false) }
    
    // Determine if search bar should be expanded (not collapsed)
    // Only expand when activated (clicked) or has text, NOT just from controller focus highlight
    val isExpanded = !style.collapsible || isActivated || state.searchQuery.isNotEmpty()
    
    // Background color from theme or default
    val bgColor = style.backgroundColor ?: MaterialTheme.colorScheme.surfaceVariant
    // Text/icon color from theme or default
    val iconColor = style.textColor ?: MaterialTheme.colorScheme.onSurfaceVariant
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
    
    // Highlight border animation for controller navigation (theme-only feature)
    // Use gradient brush for default focus styling (matching grid behavior)
    val hasHighlightStyling = style.highlightColor != null
    val highlightAlpha by animateFloatAsState(
        targetValue = if (isFocused && hasHighlightStyling) style.highlightOpacity else 0f,
        animationSpec = tween(durationMillis = style.highlightTransitionSpeed),
        label = "searchBarHighlightAlpha"
    )
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.tertiary.copy(alpha = highlightAlpha),
            MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha),
        )
    )
    val highlightBorderModifier = if (hasHighlightStyling && highlightAlpha > 0f) {
        Modifier.border(
            width = style.highlightBorderWidth,
            brush = gradientBrush,
            shape = RoundedCornerShape(cornerRadius)
        )
    } else {
        Modifier
    }

    // Navigation links for explicit navigation overrides
    val navigationLinks = style.toNavigationLinks()

    // Modern search field with rounded corners
    // Use explicit width if specified (> 0), otherwise fill width
    val hasExplicitWidth = style.expandedWidth > 0.dp
    Box(
        modifier = Modifier
            .then(
                if (style.collapsible) {
                    Modifier.width(animatedWidth)
                } else if (hasExplicitWidth) {
                    Modifier.width(style.expandedWidth)
                } else {
                    Modifier.fillMaxWidth()
                }
            )
            // Register with spatial focus manager for position-based navigation
            .onGloballyPositioned { coordinates ->
                spatialFocusManager?.register(
                    id = style.navigationId,
                    bounds = coordinates.boundsInRoot(),
                    focusRequester = focusRequester,
                    navigationLinks = navigationLinks
                )
            }
            // Use focusGroup to track child focus for highlight border
            .focusGroup()
            .onFocusChanged { focusState ->
                // Update isFocused when any child gains/loses focus
                isFocused = focusState.hasFocus
                if (focusState.hasFocus) {
                    spatialFocusManager?.setFocused(style.navigationId)
                }
            }
            .then(highlightBorderModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // When tapped, activate and expand the search bar
                allowFocusing.value = true
                isActivated = true
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
                    // Make it focusable for controller navigation (highlight only, not expand)
                    Box(
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(cornerRadius))
                            .background(bgColor)
                            .focusRequester(focusRequester)
                            .focusable()
                            .onFocusChanged { focusState ->
                                // Only update highlight state, don't expand
                                isFocused = focusState.isFocused || focusState.hasFocus
                                if (focusState.isFocused) {
                                    spatialFocusManager?.setFocused(style.navigationId)
                                }
                            }
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        // Activate on Enter/A button press
                                        androidx.compose.ui.input.key.Key.Enter,
                                        androidx.compose.ui.input.key.Key.DirectionCenter,
                                        androidx.compose.ui.input.key.Key.ButtonA -> {
                                            allowFocusing.value = true
                                            isActivated = true
                                            true
                                        }
                                        // Handle D-pad navigation using spatial focus manager
                                        androidx.compose.ui.input.key.Key.DirectionUp -> {
                                            spatialFocusManager?.navigateInDirection(
                                                style.navigationId,
                                                SpatialFocusManager.Direction.UP
                                            ) ?: false
                                        }
                                        androidx.compose.ui.input.key.Key.DirectionDown -> {
                                            spatialFocusManager?.navigateInDirection(
                                                style.navigationId,
                                                SpatialFocusManager.Direction.DOWN
                                            ) ?: false
                                        }
                                        androidx.compose.ui.input.key.Key.DirectionLeft -> {
                                            spatialFocusManager?.navigateInDirection(
                                                style.navigationId,
                                                SpatialFocusManager.Direction.LEFT
                                            ) ?: false
                                        }
                                        androidx.compose.ui.input.key.Key.DirectionRight -> {
                                            spatialFocusManager?.navigateInDirection(
                                                style.navigationId,
                                                SpatialFocusManager.Direction.RIGHT
                                            ) ?: false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .clickable {
                                // Activate on tap/click
                                allowFocusing.value = true
                                isActivated = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_description),
                            tint = iconColor
                        )
                    }
                } else {
                    // Expanded mode - show full search field
                    var hasHadFocus by remember { mutableStateOf(false) }
                    val interactionSource = remember { MutableInteractionSource() }
                    BasicTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(style.height)
                            .clip(RoundedCornerShape(cornerRadius))
                            .background(bgColor, RoundedCornerShape(cornerRadius))
                            .focusable(allowFocusing.value)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                isFocused = focusState.isFocused
                                if (focusState.isFocused) {
                                    hasHadFocus = true
                                }
                                // Only collapse if TextField had focus before and now lost it
                                if (!focusState.isFocused && hasHadFocus && state.searchQuery.isEmpty()) {
                                    allowFocusing.value = false
                                    isActivated = false
                                }
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = iconColor),
                        cursorBrush = SolidColor(iconColor),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                        interactionSource = interactionSource,
                        decorationBox = { innerTextField ->
                            TextFieldDefaults.DecorationBox(
                                value = state.searchQuery,
                                innerTextField = innerTextField,
                                enabled = true,
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                                interactionSource = interactionSource,
                                placeholder = {
                                    Text(
                                        text = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_placeholder),
                                        color = iconColor.copy(alpha = 0.7f)
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_description),
                                        tint = iconColor
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
                                                    tint = iconColor
                                                )
                                            }
                                        )
                                    }
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                            )
                        }
                    )
                    
                    // Request focus when expanded in collapsible mode
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }
                }
            }
        } else {
            // Non-collapsible mode - show search field but don't activate until user presses Enter/A
            var isTextFieldActive by remember { mutableStateOf(false) }
            val textFieldFocusRequester = remember { FocusRequester() }
            val interactionSource = remember { MutableInteractionSource() }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(style.height)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(bgColor, RoundedCornerShape(cornerRadius))
                    .then(
                        if (!isTextFieldActive) {
                            // When not active, the outer box is focusable for navigation
                            Modifier
                                .focusRequester(focusRequester)
                                .focusable()
                                .onFocusChanged { focusState ->
                                    isFocused = focusState.isFocused
                                    if (focusState.isFocused) {
                                        spatialFocusManager?.setFocused(style.navigationId)
                                    }
                                }
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                                        when (keyEvent.key) {
                                            // Activate text input on Enter/A button
                                            androidx.compose.ui.input.key.Key.Enter,
                                            androidx.compose.ui.input.key.Key.DirectionCenter,
                                            androidx.compose.ui.input.key.Key.ButtonA -> {
                                                isTextFieldActive = true
                                                true
                                            }
                                            // Handle D-pad navigation
                                            androidx.compose.ui.input.key.Key.DirectionUp -> {
                                                spatialFocusManager?.navigateInDirection(
                                                    style.navigationId,
                                                    SpatialFocusManager.Direction.UP
                                                ) ?: false
                                            }
                                            androidx.compose.ui.input.key.Key.DirectionDown -> {
                                                spatialFocusManager?.navigateInDirection(
                                                    style.navigationId,
                                                    SpatialFocusManager.Direction.DOWN
                                                ) ?: false
                                            }
                                            androidx.compose.ui.input.key.Key.DirectionLeft -> {
                                                spatialFocusManager?.navigateInDirection(
                                                    style.navigationId,
                                                    SpatialFocusManager.Direction.LEFT
                                                ) ?: false
                                            }
                                            androidx.compose.ui.input.key.Key.DirectionRight -> {
                                                spatialFocusManager?.navigateInDirection(
                                                    style.navigationId,
                                                    SpatialFocusManager.Direction.RIGHT
                                                ) ?: false
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                .clickable {
                                    // Activate on tap/click
                                    isTextFieldActive = true
                                }
                        } else Modifier
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(textFieldFocusRequester)
                        .onFocusChanged { focusState ->
                            // Deactivate when text field loses focus
                            if (!focusState.isFocused && isTextFieldActive) {
                                isTextFieldActive = false
                            }
                            isFocused = focusState.isFocused || focusState.hasFocus
                        },
                    enabled = isTextFieldActive,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = iconColor),
                    cursorBrush = SolidColor(iconColor),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                    interactionSource = interactionSource,
                    decorationBox = { innerTextField ->
                        TextFieldDefaults.DecorationBox(
                            value = state.searchQuery,
                            innerTextField = innerTextField,
                            enabled = isTextFieldActive,
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                            interactionSource = interactionSource,
                            placeholder = {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_placeholder),
                                    color = iconColor.copy(alpha = 0.7f)
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_search_description),
                                    tint = iconColor
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
                                                tint = iconColor
                                            )
                                        }
                                    )
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                        )
                    }
                )
            }
            
            // Request focus on text field when activated
            LaunchedEffect(isTextFieldActive) {
                if (isTextFieldActive) {
                    textFieldFocusRequester.requestFocus()
                }
            }
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
