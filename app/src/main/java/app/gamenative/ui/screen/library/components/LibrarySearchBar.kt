@file:OptIn(ExperimentalMaterial3Api::class)

package app.gamenative.ui.screen.library.components

import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.ExperimentalMaterial3Api
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.theme.runtime.LocalSpatialFocusManager
import app.gamenative.theme.runtime.SpatialFocusManager
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.theme.PluviaTheme
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
    /** Direction to expand when collapsible: "left" or "right". */
    val expandDirection: String = "left",
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
    /** Text shadow color, null = no shadow. */
    val textShadowColor: Color? = null,
    /** Text shadow blur radius. */
    val textShadowRadius: Float = 0f,
    /** Text shadow horizontal offset. */
    val textShadowOffsetX: Float = 0f,
    /** Text shadow vertical offset. */
    val textShadowOffsetY: Float = 0f,
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

    /**
     * Create a Shadow from the shadow properties, or null if no shadow color is set.
     */
    fun toShadow(): Shadow? = textShadowColor?.let {
        Shadow(
            color = it,
            offset = Offset(textShadowOffsetX, textShadowOffsetY),
            blurRadius = textShadowRadius
        )
    }
}

/**
 * Theme-engine search bar variant: uses LibraryState and SearchBarStyle for themed rendering.
 * Called from the theme engine rendering path in LibraryScreen.
 */
@Composable
fun LibrarySearchBar(
    state: LibraryState,
    listState: LazyGridState,
    onSearchQuery: (String) -> Unit,
    style: SearchBarStyle = SearchBarStyle(),
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Spatial focus manager for position-based navigation (null in non-theme mode)
    val spatialFocusManager = LocalSpatialFocusManager.current

    val onSearchText: (String) -> Unit = { newText ->
        onSearchQuery(newText)
        scope.launch {
            listState.scrollToItem(0)
        }
    }

    // Prevent focus by default, so it doesn't scoop up every controller input for focus
    val allowFocusing = remember { mutableStateOf(false) }

    // Track if user has activated the search (clicked/tapped) - separate from focus highlight
    var isActivated by remember { mutableStateOf(false) }

    // Determine if search bar should be expanded (not collapsed)
    val isExpanded = !style.collapsible || isActivated || state.searchQuery.isNotEmpty()

    // Background color from theme or default
    val bgColor = style.backgroundColor ?: MaterialTheme.colorScheme.surfaceVariant
    // Text/icon color from theme or default
    val iconColor = style.textColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    val cornerRadius = style.borderRadius.dp
    // Text shadow from theme
    val textShadow = style.toShadow()

    // Collapsed size equals height (makes it round/square when collapsed)
    val collapsedSize = style.height

    // Animate width for collapsible search bar
    val targetWidth = if (style.collapsible) {
        if (isExpanded) style.expandedWidth.coerceAtLeast(200.dp) else collapsedSize
    } else {
        style.expandedWidth.coerceAtLeast(200.dp)
    }
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 250),
        label = "searchBarWidth"
    )

    // Alignment based on expand direction
    val contentAlignment = if (style.collapsible) {
        if (style.expandDirection.lowercase() == "right") Alignment.CenterStart else Alignment.CenterEnd
    } else {
        if (style.anchorRight) Alignment.CenterEnd else Alignment.CenterStart
    }

    // Highlight border animation for controller navigation (theme-only feature)
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
            .onGloballyPositioned { coordinates ->
                spatialFocusManager?.register(
                    id = style.navigationId,
                    bounds = coordinates.boundsInRoot(),
                    focusRequester = focusRequester,
                    navigationLinks = navigationLinks
                )
            }
            .focusGroup()
            .onFocusChanged { focusState ->
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
                allowFocusing.value = true
                isActivated = true
            },
        contentAlignment = contentAlignment
    ) {
        if (style.collapsible) {
            AnimatedContent(
                targetState = isExpanded,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
                },
                label = "searchBarContent"
            ) { expanded ->
                if (!expanded) {
                    Box(
                        modifier = Modifier
                            .size(collapsedSize)
                            .clip(RoundedCornerShape(cornerRadius))
                            .background(bgColor)
                            .focusRequester(focusRequester)
                            .focusable()
                            .onFocusChanged { focusState ->
                                isFocused = focusState.isFocused || focusState.hasFocus
                                if (focusState.isFocused) {
                                    spatialFocusManager?.setFocused(style.navigationId)
                                }
                            }
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        androidx.compose.ui.input.key.Key.Enter,
                                        androidx.compose.ui.input.key.Key.DirectionCenter,
                                        androidx.compose.ui.input.key.Key.ButtonA -> {
                                            allowFocusing.value = true
                                            isActivated = true
                                            true
                                        }
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
                                allowFocusing.value = true
                                isActivated = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.library_search_description),
                            tint = iconColor
                        )
                    }
                } else {
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
                                if (!focusState.isFocused && hasHadFocus && state.searchQuery.isEmpty()) {
                                    allowFocusing.value = false
                                    isActivated = false
                                }
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = iconColor, shadow = textShadow),
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
                                visualTransformation = VisualTransformation.None,
                                interactionSource = interactionSource,
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.library_search_placeholder),
                                        color = iconColor.copy(alpha = 0.7f),
                                        style = textShadow?.let { androidx.compose.ui.text.TextStyle(shadow = it) } ?: androidx.compose.ui.text.TextStyle.Default
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = stringResource(R.string.library_search_description),
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
                                                    contentDescription = stringResource(R.string.library_search_clear),
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

                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }
                }
            }
        } else {
            // Non-collapsible mode
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
                                            androidx.compose.ui.input.key.Key.Enter,
                                            androidx.compose.ui.input.key.Key.DirectionCenter,
                                            androidx.compose.ui.input.key.Key.ButtonA -> {
                                                isTextFieldActive = true
                                                true
                                            }
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
                            if (!focusState.isFocused && isTextFieldActive) {
                                isTextFieldActive = false
                            }
                            isFocused = focusState.isFocused || focusState.hasFocus
                        },
                    enabled = isTextFieldActive,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = iconColor, shadow = textShadow),
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
                            visualTransformation = VisualTransformation.None,
                            interactionSource = interactionSource,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.library_search_placeholder),
                                    color = iconColor.copy(alpha = 0.7f),
                                    style = textShadow?.let { androidx.compose.ui.text.TextStyle(shadow = it) } ?: androidx.compose.ui.text.TextStyle.Default
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.library_search_description),
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
                                                contentDescription = stringResource(R.string.library_search_clear),
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

            LaunchedEffect(isTextFieldActive) {
                if (isTextFieldActive) {
                    textFieldFocusRequester.requestFocus()
                }
            }
        }
    }
}

/**
 * Standard search bar variant used by the non-themed library UI (master's new design).
 * Uses AnimatedVisibility and AndroidView EditText for better IME handling.
 */
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
        val textColor = MaterialTheme.colorScheme.onSurface
        val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        val cursorColor = MaterialTheme.colorScheme.primary
        val placeholderText = stringResource(R.string.library_search_placeholder)

        AndroidView(
            factory = { context ->
                EditText(context).apply {
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_SEARCH
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    isSingleLine = true
                    hint = placeholderText
                    background = ColorDrawable(android.graphics.Color.TRANSPARENT)
                    setPadding(0, 0, 0, 0)

                    setTextColor(textColor.toArgb())
                    setHintTextColor(hintColor.toArgb())

                    textSize = 16f

                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                            keyboardController?.hide()
                            true
                        } else {
                            false
                        }
                    }

                    setOnKeyListener { v, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN &&
                            keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        ) {
                            keyboardController?.hide()
                            val nextFocus = v.focusSearch(View.FOCUS_DOWN)
                            nextFocus?.requestFocus()
                            true
                        } else {
                            false
                        }
                    }

                    doAfterTextChanged { editable ->
                        onSearchText(editable?.toString() ?: "")
                    }

                    setOnFocusChangeListener { _, hasFocus ->
                        isFocused = hasFocus
                    }

                    editTextRef = this
                }
            },
            update = { editText ->
                if (editText.text.toString() != searchQuery) {
                    editText.setText(searchQuery)
                    editText.setSelection(searchQuery.length)
                }

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
