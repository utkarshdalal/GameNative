package app.gamenative.theme.runtime

import android.annotation.SuppressLint
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
 *
 * When a SpatialFocusManager is available (via LocalSpatialFocusManager), this box
 * registers itself for spatial navigation and handles D-pad key events.
 *
 * @param id Unique identifier for spatial navigation registration
 * @param highlightStyle Visual styling for the highlight border
 * @param cornerRadius Border corner radius
 * @param navigationLinks Optional explicit navigation overrides
 * @param modifier Additional modifiers
 * @param content The content to render inside the box
 */
@Composable
private fun HighlightableBox(
    id: String,
    highlightStyle: HighlightStyle,
    cornerRadius: Dp,
    navigationLinks: SpatialFocusManager.NavigationLinks = SpatialFocusManager.NavigationLinks(),
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val spatialFocusManager = LocalSpatialFocusManager.current
    val focusRequester = remember { FocusRequester() }

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
            .focusRequester(focusRequester)
            // Register with spatial focus manager when positioned
            .onGloballyPositioned { coordinates ->
                spatialFocusManager?.register(
                    id = id,
                    bounds = coordinates.boundsInRoot(),
                    focusRequester = focusRequester,
                    navigationLinks = navigationLinks
                )
            }
            // Track focus on this element or any child for highlight border
            .onFocusChanged { focusState ->
                hasFocus = focusState.hasFocus
                if (focusState.hasFocus) {
                    spatialFocusManager?.setFocused(id)
                }
            }
            // Handle D-pad navigation using spatial focus manager
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && spatialFocusManager != null) {
                    val direction = when (keyEvent.key) {
                        Key.DirectionUp -> SpatialFocusManager.Direction.UP
                        Key.DirectionDown -> SpatialFocusManager.Direction.DOWN
                        Key.DirectionLeft -> SpatialFocusManager.Direction.LEFT
                        Key.DirectionRight -> SpatialFocusManager.Direction.RIGHT
                        else -> null
                    }
                    if (direction != null) {
                        spatialFocusManager.navigateInDirection(id, direction)
                    } else {
                        false
                    }
                } else {
                    false
                }
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
 * Create NavigationLinks from a FixedElement's navigation properties.
 */
private fun FixedElement.toNavigationLinks() = SpatialFocusManager.NavigationLinks(
    up = navigateUp,
    down = navigateDown,
    left = navigateLeft,
    right = navigateRight,
)

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
    themeRootDir: String? = null,
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

        // Parse CSS-style padding: "all" or "top right bottom left" (1-4 values)
        val paddingValues = container.padding?.let { parseCssPadding(it) } ?: PaddingValues(0.dp)
        val cornerRadius = container.cornerRadius.dp
        val shape = if (cornerRadius > 0.dp) RoundedCornerShape(cornerRadius) else RectangleShape

        // Render container background if specified
        if (container.backgroundColor != null) {
            Box(
                modifier = Modifier
                    .align(containerAlignment)
                    .fillMaxWidth()
                    .height(container.height?.dp ?: 80.dp)
                    .clip(shape)
                    .background(Color(container.backgroundColor))
                    .padding(paddingValues)
            )
        }

        // Render visible elements with IDs for spatial focus navigation
        visibleElements.forEachIndexed { index, element ->
            // Use custom navigationId if set, otherwise generate a unique ID
            val elementId = getNavigationId(container.id, element, index)
            RenderFixedElement(
                element = element,
                elementId = elementId,
                state = state,
                listState = listState,
                themeName = themeName,
                callbacks = callbacks,
                accountButtonContent = accountButtonContent,
                searchBarContent = searchBarContent,
                themeRootDir = themeRootDir,
            )
        }
    }
}

/**
 * Get the navigation ID for a fixed element.
 * Uses the custom navigationId if set, otherwise generates a unique ID
 * based on container ID, element type, and index.
 */
private fun getNavigationId(containerId: String, element: FixedElement, index: Int): String {
    // Use custom navigationId if set by theme creator
    element.navigationId?.let { return it }

    // Otherwise generate a unique ID
    val typePrefix = when (element) {
        is FixedElement.Header -> "header"
        is FixedElement.SearchBar -> "search-bar"
        is FixedElement.ProfileButton -> "profile-button"
        is FixedElement.FilterButton -> "filter-button"
        is FixedElement.AddButton -> "add-button"
        is FixedElement.Image -> "image"
        is FixedElement.Video -> "video"
    }
    return "$containerId-$typePrefix-$index"
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun BoxScope.RenderFixedElement(
    element: FixedElement,
    elementId: String,
    state: LibraryState,
    listState: LazyGridState,
    themeName: String,
    callbacks: FixedElementCallbacks,
    accountButtonContent: @Composable (iconSize: Dp) -> Unit,
    searchBarContent: @Composable (app.gamenative.ui.screen.library.components.SearchBarStyle) -> Unit,
    themeRootDir: String? = null,
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

            // Header styling from theme
            val bgColor = element.backgroundColor?.let { Color(it) }
            val cornerRadius = element.cornerRadius.dp
            val padding = element.padding.dp
            val textColor = Color(element.textColor)
            val textSizeSp = element.textSize.sp
            val fontWeight = when (element.fontWeight.lowercase()) {
                "bold" -> FontWeight.Bold
                "semibold" -> FontWeight.SemiBold
                "medium" -> FontWeight.Medium
                "light" -> FontWeight.Light
                "thin" -> FontWeight.Thin
                "extrabold", "black" -> FontWeight.ExtraBold
                else -> FontWeight.Normal
            }

            // Calculate size if specified
            val sizeModifier = element.size?.let { size ->
                Modifier.size(
                    width = dimToDp(size.width, parentWidth, parentHeight),
                    height = dimToDp(size.height, parentWidth, parentHeight)
                )
            } ?: Modifier

            Column(
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
                    .then(sizeModifier)
                    .then(
                        if (bgColor != null) {
                            Modifier
                                .clip(RoundedCornerShape(cornerRadius))
                                .background(bgColor)
                        } else Modifier
                    )
                    .padding(padding)
            ) {
                if (element.showAppName) {
                    Text(
                        text = "GameNative",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = fontWeight,
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
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = textSizeSp),
                        color = textColor.copy(alpha = 0.7f)
                    )
                }
                if (element.showGameCount) {
                    Text(
                        text = stringResource(
                            R.string.library_game_count,
                            state.totalAppsInFilter,
                            installedCount
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = textSizeSp),
                        color = textColor.copy(alpha = 0.7f)
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

            // Create style from theme element with highlight properties and navigation links
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
                navigationId = elementId,
                navigateUp = element.navigateUp,
                navigateDown = element.navigateDown,
                navigateLeft = element.navigateLeft,
                navigateRight = element.navigateRight,
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
            val navigationLinks = element.toNavigationLinks()

            HighlightableBox(
                id = elementId,
                highlightStyle = highlightStyle,
                cornerRadius = radius,
                navigationLinks = navigationLinks,
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
                val navigationLinks = element.toNavigationLinks()
                val buttonSize = element.size.dp
                val iconSize = element.iconSize.dp
                val bgColor = element.backgroundColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                val iconTint = element.iconColor?.let { Color(it) } ?: MaterialTheme.colorScheme.onPrimary
                val cornerRadius = element.cornerRadius.dp

                HighlightableBox(
                    id = elementId,
                    highlightStyle = highlightStyle,
                    cornerRadius = cornerRadius,
                    navigationLinks = navigationLinks,
                    modifier = Modifier
                        .align(alignment)
                        .offset(x = offsetX, y = offsetY)
                ) {
                    // FABs are focusable by default - no extra focusable() modifier needed
                    ExtendedFloatingActionButton(
                        text = { Text(text = "Filters") },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(iconSize),
                                tint = iconTint,
                            )
                        },
                        expanded = element.expanded && callbacks.filterExpanded,
                        onClick = callbacks.onFilterClick,
                        containerColor = bgColor,
                        contentColor = iconTint,
                        modifier = Modifier.defaultMinSize(minWidth = buttonSize, minHeight = buttonSize),
                    )
                }
            }
        }

        is FixedElement.AddButton -> {
            val highlightStyle = element.toHighlightStyle()
            val navigationLinks = element.toNavigationLinks()
            val buttonSize = element.size.dp
            val iconSize = element.iconSize.dp
            val bgColor = element.backgroundColor?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary
            val iconTint = element.iconColor?.let { Color(it) } ?: MaterialTheme.colorScheme.onSecondary
            val cornerRadius = element.cornerRadius.dp

            HighlightableBox(
                id = elementId,
                highlightStyle = highlightStyle,
                cornerRadius = cornerRadius,
                navigationLinks = navigationLinks,
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
            ) {
                // FABs are focusable by default - no extra focusable() modifier needed
                FloatingActionButton(
                    onClick = callbacks.onAddClick,
                    containerColor = bgColor,
                    contentColor = iconTint,
                    modifier = Modifier.size(buttonSize),
                    shape = RoundedCornerShape(cornerRadius),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add custom game",
                        modifier = Modifier.size(iconSize),
                        tint = iconTint,
                    )
                }
            }
        }

        is FixedElement.Image -> {
            val width = dimToDp(element.size.width, parentWidth, parentHeight)
            val height = dimToDp(element.size.height, parentWidth, parentHeight)
            val shape = ThemeUtils.parseCornerRadius(element.cornerRadius)
            val contentScale = when (element.scaleType.lowercase()) {
                "contain", "fit" -> ContentScale.Fit
                "stretch", "fill" -> ContentScale.FillBounds
                "none" -> ContentScale.None
                else -> ContentScale.Crop // "cover" is default
            }
            // Resolve asset path
            val resolvedSrc = resolveAssetPath(element.src, themeRootDir)

            Box(
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
                    .size(width, height)
                    .clip(shape)
                    .graphicsLayer(alpha = element.opacity)
            ) {
                if (resolvedSrc.isNotEmpty()) {
                    com.skydoves.landscapist.coil.CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { resolvedSrc },
                        imageOptions = com.skydoves.landscapist.ImageOptions(
                            contentScale = contentScale,
                            contentDescription = "Fixed image",
                        ),
                    )
                } else {
                    // Placeholder when no src is provided
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF555555))
                    )
                }
            }
        }

        is FixedElement.Video -> {
            FixedVideoElement(
                element = element,
                width = dimToDp(element.size.width, parentWidth, parentHeight),
                height = dimToDp(element.size.height, parentWidth, parentHeight),
                themeRootDir = themeRootDir,
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
            )
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
 * Parse CSS-style padding string into Compose PaddingValues.
 * - "8" = 8dp all sides
 * - "8 16" = 8dp top/bottom, 16dp left/right
 * - "8 16 8" = 8dp top, 16dp left/right, 8dp bottom
 * - "8 16 8 16" = top, right, bottom, left
 */
private fun parseCssPadding(value: String): PaddingValues {
    val parts = value.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
    return when (parts.size) {
        0 -> PaddingValues(0.dp)
        1 -> PaddingValues(parts[0].dp)
        2 -> PaddingValues(vertical = parts[0].dp, horizontal = parts[1].dp)
        3 -> PaddingValues(top = parts[0].dp, start = parts[1].dp, bottom = parts[2].dp, end = parts[1].dp)
        else -> PaddingValues(top = parts[0].dp, end = parts[1].dp, bottom = parts[2].dp, start = parts[3].dp)
    }
}

// dimToDp is imported from ThemeUtils (same package)

/**
 * Resolves a relative asset path to a full URI.
 * - If path starts with "http://" or "https://", returns as-is
 * - If path starts with "file://", returns as-is
 * - If path starts with "assets/", resolves relative to theme root directory
 * - Otherwise returns as-is (assumes it's a full path)
 */
private fun resolveAssetPath(path: String, themeRootDir: String?): String {
    if (path.isEmpty()) return path
    if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("file://")) {
        return path
    }
    // Resolve relative paths (like "assets/sample.mp4") using theme root
    if (themeRootDir != null && !path.contains("://")) {
        val fullPath = java.io.File(themeRootDir, path)
        if (fullPath.exists()) {
            return "file://${fullPath.absolutePath}"
        }
    }
    return path
}

/**
 * Renders a fixed video element with ExoPlayer.
 * Shows poster image with play indicator if no video source is provided,
 * otherwise plays the video with the specified settings.
 */
@OptIn(UnstableApi::class)
@Composable
private fun FixedVideoElement(
    element: FixedElement.Video,
    width: Dp,
    height: Dp,
    themeRootDir: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shape = ThemeUtils.parseCornerRadius(element.cornerRadius)

    // Resolve asset paths
    val resolvedSrc = resolveAssetPath(element.src, themeRootDir)
    val resolvedPoster = element.poster?.let { resolveAssetPath(it, themeRootDir) }

    // If no video source, show placeholder with poster
    if (resolvedSrc.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(width, height)
                .clip(shape)
                .graphicsLayer(alpha = element.opacity)
                .background(Color(0xFF303030))
        ) {
            if (!resolvedPoster.isNullOrEmpty()) {
                com.skydoves.landscapist.coil.CoilImage(
                    modifier = Modifier.fillMaxSize(),
                    imageModel = { resolvedPoster },
                    imageOptions = com.skydoves.landscapist.ImageOptions(
                        contentScale = ContentScale.Crop,
                        contentDescription = "Video poster",
                    ),
                )
            }
            Text(
                text = "▶",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    // Create and remember ExoPlayer instance
    val exoPlayer = remember(resolvedSrc) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(resolvedSrc)
            setMediaItem(mediaItem)
            repeatMode = if (element.loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            volume = if (element.muted) 0f else 1f
            playWhenReady = element.autoplay
            prepare()
        }
    }

    // Clean up player when composable leaves composition
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .size(width, height)
            .clip(shape)
            .graphicsLayer(alpha = element.opacity)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // Hide playback controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
