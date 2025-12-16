package app.gamenative.theme.runtime

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.service.DownloadService
import app.gamenative.theme.model.Anchor
import app.gamenative.theme.model.Dimension
import app.gamenative.theme.model.FixedContainer
import app.gamenative.theme.model.FixedElement
import app.gamenative.theme.model.Visibility
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter

/**
 * Data class to hold all the callbacks and state needed by fixed elements.
 */
data class FixedElementCallbacks(
    val onNavigateRoute: (String) -> Unit,
    val onLogout: () -> Unit,
    val onGoOnline: () -> Unit,
    val onFilterClick: () -> Unit,
    val onAddClick: () -> Unit,
    val onSearchQuery: (String) -> Unit,
    val isOffline: Boolean,
    val filterExpanded: Boolean,
    val isSearching: Boolean,
)

/**
 * Data class holding highlight styling configuration for controller navigation.
 */
data class HighlightStyle(
    val color: Color,
    val opacity: Float,
    val borderWidth: Dp,
    val transitionSpeed: Int,
)

/**
 * Extract highlight style from a FixedElement using its highlight properties.
 */
@Composable
private fun FixedElement.toHighlightStyle(): HighlightStyle = HighlightStyle(
    color = highlightColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
    opacity = highlightOpacity,
    borderWidth = highlightBorderWidth.dp,
    transitionSpeed = highlightTransitionSpeed,
)

/**
 * A composable wrapper that adds animated highlight border indication for controller navigation.
 * Used only for themed fixed elements (not default layout).
 * Uses hasFocus to detect focus on any descendant (like buttons inside).
 */
@Composable
private fun HighlightableBox(
    highlightStyle: HighlightStyle,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // Use hasFocus to detect focus on this element OR any descendant
    var hasFocus by remember { mutableStateOf(false) }
    
    val highlightAlpha by animateFloatAsState(
        targetValue = if (hasFocus) highlightStyle.opacity else 0f,
        animationSpec = tween(durationMillis = highlightStyle.transitionSpeed),
        label = "highlightBorderAlpha"
    )
    
    val borderColor = highlightStyle.color.copy(alpha = highlightAlpha)
    
    Box(
        modifier = modifier
            // Track focus on this element or any child for highlight border
            // Don't use focusGroup() as it can interfere with focus traversal
            .onFocusChanged { focusState ->
                hasFocus = focusState.hasFocus
            }
            .then(
                if (highlightAlpha > 0f) {
                    Modifier.border(
                        width = highlightStyle.borderWidth,
                        color = borderColor,
                        shape = RoundedCornerShape(cornerRadius)
                    )
                } else {
                    Modifier
                }
            ),
        content = content
    )
}

/**
 * Enum to specify which position of fixed containers to render.
 * Used to split rendering for proper focus traversal order.
 */
enum class FixedContainerPosition {
    TOP,     // Containers with "top" in their id
    BOTTOM,  // Containers with "bottom" in their id
    ALL      // All containers (original behavior)
}

/**
 * Renders fixed UI elements from theme configuration.
 * Falls back to default positioning if no fixed containers are defined.
 * 
 * @param position Filter which containers to render based on their visual position.
 *                 This enables rendering top elements before the main content and
 *                 bottom elements after, ensuring proper focus traversal order.
 */
@Composable
fun BoxScope.RenderFixedElements(
    fixedContainers: List<FixedContainer>,
    state: LibraryState,
    listState: LazyGridState,
    themeName: String,
    callbacks: FixedElementCallbacks,
    accountButtonContent: @Composable (iconSize: Dp) -> Unit,
    searchBarContent: @Composable (app.gamenative.ui.screen.library.components.SearchBarStyle) -> Unit,
    position: FixedContainerPosition = FixedContainerPosition.ALL,
) {
    if (fixedContainers.isEmpty()) {
        // Fallback to default positioning when no fixed containers are defined
        // Render when ALL is requested, or when TOP is requested (since we render top first in split mode)
        if (position == FixedContainerPosition.ALL || position == FixedContainerPosition.TOP) {
            RenderDefaultFixedElements(
                state = state,
                listState = listState,
                themeName = themeName,
                callbacks = callbacks,
                accountButtonContent = accountButtonContent,
                searchBarContent = searchBarContent,
            )
        }
        return
    }

    // Determine current orientation for visibility filtering (centralized)
    val isPortrait = rememberIsPortrait()

    // Filter containers based on requested position
    val containersToRender = when (position) {
        FixedContainerPosition.TOP -> fixedContainers.filter { 
            it.id.contains("top", ignoreCase = true) 
        }
        FixedContainerPosition.BOTTOM -> fixedContainers.filter { 
            it.id.contains("bottom", ignoreCase = true) 
        }
        FixedContainerPosition.ALL -> fixedContainers
    }

    // Render each fixed container with optional background
    containersToRender.forEach { container ->
        // Check container visibility first - skip entire container if not visible
        if (!container.visibility.isVisible(isPortrait)) return@forEach
        
        // Filter elements by visibility
        // Elements inherit container's visibility unless they specify their own
        val visibleElements = container.elements.filter { element -> 
            element.visibility.isVisible(isPortrait) 
        }
        
        // Skip container if no elements are visible
        if (visibleElements.isEmpty()) return@forEach
        
        // Determine container alignment based on id (topBar at top, bottomBar at bottom)
        val containerAlignment = when {
            container.id.contains("top", ignoreCase = true) -> Alignment.TopCenter
            container.id.contains("bottom", ignoreCase = true) -> Alignment.BottomCenter
            else -> Alignment.TopCenter
        }
        
        // Render container background if specified
        if (container.backgroundColor != null) {
            Box(
                modifier = Modifier
                    .align(containerAlignment)
                    .fillMaxWidth()
                    .height(container.height?.dp ?: 80.dp)
                    .background(Color(container.backgroundColor))
            )
        }
        
        // Render visible elements
        visibleElements.forEach { element ->
            RenderFixedElement(
                element = element,
                state = state,
                listState = listState,
                themeName = themeName,
                callbacks = callbacks,
                accountButtonContent = accountButtonContent,
                searchBarContent = searchBarContent,
            )
        }
    }
}

@Composable
private fun BoxScope.RenderFixedElement(
    element: FixedElement,
    state: LibraryState,
    listState: LazyGridState,
    themeName: String,
    callbacks: FixedElementCallbacks,
    accountButtonContent: @Composable (iconSize: Dp) -> Unit,
    searchBarContent: @Composable (app.gamenative.ui.screen.library.components.SearchBarStyle) -> Unit,
) {
    // Use BoxWithConstraints to get parent dimensions for relative size calculations
    BoxWithConstraints(modifier = Modifier.matchParentSize()) {
        val parentWidth = maxWidth
        val parentHeight = maxHeight
        
    val alignment = element.anchor.toComposeAlignment()
        val rawX = dimToDp(element.position.x, parentWidth, parentHeight)
        val rawY = dimToDp(element.position.y, parentWidth, parentHeight)
    val (offsetX, offsetY) = calculateCssLikeOffset(rawX, rawY, element.anchor)

    when (element) {
        is FixedElement.Header -> {
            // Calculate installed count like LibraryListPane does
            val installedCount = remember(
                state.appInfoSortType,
                state.showSteamInLibrary,
                state.showCustomGamesInLibrary,
                state.totalAppsInFilter
            ) {
                if (state.appInfoSortType.contains(AppFilter.INSTALLED)) {
                    state.totalAppsInFilter
                } else {
                    val steamCount = if (state.showSteamInLibrary) {
                        DownloadService.getDownloadDirectoryApps().count()
                    } else 0
                    val customGameCount = if (state.showCustomGamesInLibrary) {
                        PrefManager.customGamesCount
                    } else 0
                    steamCount + customGameCount
                }
            }

            Column(
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
                    .padding(8.dp)
            ) {
                if (element.showAppName) {
                    Text(
                        text = "GameNative",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        )
                    )
                }
                if (element.showThemeName) {
                    Text(
                        text = "Theme: $themeName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (element.showGameCount) {
                    Text(
                        text = stringResource(
                            R.string.library_game_count,
                            state.totalAppsInFilter,
                            installedCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        is FixedElement.SearchBar -> {
            val bgColor = element.backgroundColor?.let { Color(it) }
            val radius = element.borderRadius
            val expandedWidth = dimToDp(element.size.width, parentWidth, parentHeight)
            val highlightStyle = element.toHighlightStyle()
            
            // Check if anchor is on the right side
            val isAnchorRight = element.anchor == Anchor.TOP_RIGHT ||
                element.anchor == Anchor.CENTER_RIGHT ||
                element.anchor == Anchor.BOTTOM_RIGHT
            
            // Create style from theme element with highlight properties
            val searchStyle = app.gamenative.ui.screen.library.components.SearchBarStyle(
                backgroundColor = bgColor,
                borderRadius = radius,
                collapsible = element.collapsible,
                anchorRight = isAnchorRight,
                expandedWidth = expandedWidth,
                highlightColor = highlightStyle.color,
                highlightOpacity = highlightStyle.opacity,
                highlightBorderWidth = highlightStyle.borderWidth,
                highlightTransitionSpeed = highlightStyle.transitionSpeed,
            )
            
            Box(
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
                    .height(dimToDp(element.size.height, parentWidth, parentHeight))
            ) {
                searchBarContent(searchStyle)
            }
        }

        is FixedElement.ProfileButton -> {
            val bgColor = element.backgroundColor?.let { Color(it) }
                ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            val radius = element.cornerRadius.dp
            val buttonSize = element.size.dp
            val buttonPadding = element.padding.dp
            val iconSize = element.iconSize.dp
            val highlightStyle = element.toHighlightStyle()
            
            HighlightableBox(
                highlightStyle = highlightStyle,
                cornerRadius = radius,
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
                    .size(buttonSize)
                    .clip(RoundedCornerShape(radius))
                    .background(bgColor)
                    .padding(buttonPadding),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    accountButtonContent(iconSize)
                }
            }
        }

        is FixedElement.FilterButton -> {
            if (!callbacks.isSearching) {
                val highlightStyle = element.toHighlightStyle()
                HighlightableBox(
                    highlightStyle = highlightStyle,
                    cornerRadius = 16.dp, // Material FAB default radius
                    modifier = Modifier
                        .align(alignment)
                        .offset(x = offsetX, y = offsetY)
                ) {
                    // FABs are focusable by default - no extra focusable() modifier needed
                    ExtendedFloatingActionButton(
                        text = { Text(text = "Filters") },
                        icon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = null) },
                        expanded = element.expanded && callbacks.filterExpanded,
                        onClick = callbacks.onFilterClick,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        is FixedElement.AddButton -> {
            val highlightStyle = element.toHighlightStyle()
            HighlightableBox(
                highlightStyle = highlightStyle,
                cornerRadius = 16.dp, // Material FAB default radius
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
            ) {
                // FABs are focusable by default - no extra focusable() modifier needed
                FloatingActionButton(
                    onClick = callbacks.onAddClick,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add custom game",
                    )
                }
            }
            }
        }
    }
}

/**
 * Default fixed element layout when no theme configuration is provided.
 */
@Composable
private fun BoxScope.RenderDefaultFixedElements(
    state: LibraryState,
    listState: LazyGridState,
    themeName: String,
    callbacks: FixedElementCallbacks,
    accountButtonContent: @Composable (iconSize: Dp) -> Unit,
    searchBarContent: @Composable (app.gamenative.ui.screen.library.components.SearchBarStyle) -> Unit,
) {
    // Calculate installed count like LibraryListPane does
    val installedCount = remember(
        state.appInfoSortType,
        state.showSteamInLibrary,
        state.showCustomGamesInLibrary,
        state.totalAppsInFilter
    ) {
        if (state.appInfoSortType.contains(AppFilter.INSTALLED)) {
            state.totalAppsInFilter
        } else {
            val steamCount = if (state.showSteamInLibrary) {
                DownloadService.getDownloadDirectoryApps().count()
            } else 0
            val customGameCount = if (state.showCustomGamesInLibrary) {
                PrefManager.customGamesCount
            } else 0
            steamCount + customGameCount
        }
    }

    // Top bar with header and search
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "GameNative",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
                )
                Text(
                    text = "Theme: $themeName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.library_game_count,
                        state.totalAppsInFilter,
                        installedCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            accountButtonContent(40.dp) // Default icon size
        }
        // Search bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            searchBarContent(app.gamenative.ui.screen.library.components.SearchBarStyle())
        }
    }

    // Bottom buttons
    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(bottom = 24.dp, end = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!callbacks.isSearching) {
            ExtendedFloatingActionButton(
                text = { Text(text = "Filters") },
                icon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = null) },
                expanded = callbacks.filterExpanded,
                onClick = callbacks.onFilterClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }

        FloatingActionButton(
            onClick = callbacks.onAddClick,
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add custom game",
            )
        }
    }
}

// Extension functions
private fun Anchor.toComposeAlignment(): Alignment = when (this) {
    Anchor.TOP_LEFT -> Alignment.TopStart
    Anchor.TOP_CENTER -> Alignment.TopCenter
    Anchor.TOP_RIGHT -> Alignment.TopEnd
    Anchor.CENTER_LEFT -> Alignment.CenterStart
    Anchor.CENTER -> Alignment.Center
    Anchor.CENTER_RIGHT -> Alignment.CenterEnd
    Anchor.BOTTOM_LEFT -> Alignment.BottomStart
    Anchor.BOTTOM_CENTER -> Alignment.BottomCenter
    Anchor.BOTTOM_RIGHT -> Alignment.BottomEnd
}

/**
 * Convert CSS-like positioning to Compose offset.
 * With CSS-like positioning, positive values always mean "inward" from the anchor edge.
 * 
 * For example, with anchor=topRight and x=16, y=8:
 * - x=16 means 16px from the right edge (so Compose offsetX = -16)
 * - y=8 means 8px from the top edge (so Compose offsetY = 8)
 */
private fun calculateCssLikeOffset(rawX: Dp, rawY: Dp, anchor: Anchor): Pair<Dp, Dp> {
    val offsetX = when (anchor) {
        Anchor.TOP_LEFT, Anchor.CENTER_LEFT, Anchor.BOTTOM_LEFT -> rawX
        Anchor.TOP_CENTER, Anchor.CENTER, Anchor.BOTTOM_CENTER -> rawX
        Anchor.TOP_RIGHT, Anchor.CENTER_RIGHT, Anchor.BOTTOM_RIGHT -> -rawX
    }
    
    val offsetY = when (anchor) {
        Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT -> rawY
        Anchor.CENTER_LEFT, Anchor.CENTER, Anchor.CENTER_RIGHT -> rawY
        Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT -> -rawY
    }
    
    return Pair(offsetX, offsetY)
}

/**
 * Convert a Dimension to Dp, resolving relative dimensions using parent size.
 */
private fun dimToDp(d: Dimension, parentW: Dp, parentH: Dp): Dp = when (d) {
    is Dimension.Px -> d.value.dp
    is Dimension.RelW -> parentW * d.fraction
    is Dimension.RelH -> parentH * d.fraction
}
