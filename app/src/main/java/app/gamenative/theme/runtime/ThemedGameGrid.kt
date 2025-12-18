package app.gamenative.theme.runtime

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.gamenative.theme.model.Card as ThemeCard
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import app.gamenative.data.LibraryItem
import app.gamenative.theme.io.ThemeStringResolver
import app.gamenative.theme.model.*
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlin.math.absoluteValue

/**
 * A themed game grid that renders library items using the theme engine's cards.
 * Unlike the basic ThemeLayout, this component:
 * - Supports scrolling via LazyVerticalGrid
 * - Handles click events for navigation
 * - Uses all items from the data source
 * - Supports focus for controller navigation
 */
@Composable
fun ThemedGameGrid(
    items: List<LibraryItem>,
    gridConfig: LayoutNode.Grid,
    card: ThemeCard,
    listState: LazyGridState,
    modifier: Modifier = Modifier,
    onItemClick: (LibraryItem) -> Unit = {},
    onItemFocus: (LibraryItem) -> Unit = {},
    bindingProvider: (LibraryItem) -> Map<String, String> = { emptyMap() },
    themePath: String? = null,
) {
    // String resolver for @string/ references
    val context = LocalContext.current
    val stringResolver = remember(themePath) {
        ThemeStringResolver(context, context.assets)
    }
    
    // Use content padding from grid config, with defaults
    val paddingTop = if (gridConfig.contentPaddingTop > 0) gridConfig.contentPaddingTop.dp else 80.dp
    val paddingBottom = if (gridConfig.contentPaddingBottom > 0) gridConfig.contentPaddingBottom.dp else 72.dp
    val paddingStart = if (gridConfig.contentPaddingStart > 0) gridConfig.contentPaddingStart.dp else 16.dp
    val paddingEnd = if (gridConfig.contentPaddingEnd > 0) gridConfig.contentPaddingEnd.dp else 16.dp

    // Use BoxWithConstraints to support percentage-based cell sizes and adaptive layouts
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        
        val hSpacing = gridConfig.hSpacing.dp
        val vSpacing = gridConfig.vSpacing.dp
        
        // Calculate minimum cell width from config
        val minCellWidthDp = dimToDp(gridConfig.cellWidth, viewportWidth, viewportHeight)
        
        // Calculate available width for content (excluding padding)
        val availableWidth = viewportWidth - paddingStart - paddingEnd
        
        // Calculate how many columns fit, ensuring at least 1
        val columnCount = maxOf(1, ((availableWidth + hSpacing) / (minCellWidthDp + hSpacing)).toInt())
        
        // Calculate actual cell width to fill the available space evenly
        // Formula: availableWidth = columnCount * cellWidth + (columnCount - 1) * spacing
        // Solving for cellWidth: cellWidth = (availableWidth - (columnCount - 1) * spacing) / columnCount
        val actualCellWidthDp = if (columnCount > 1) {
            (availableWidth - hSpacing * (columnCount - 1)) / columnCount
        } else {
            availableWidth // Single column fills entire width
        }
        
        // Calculate cell height:
        // 1. If aspectRatio is specified, use it to calculate from actual cell width
        // 2. Else if cellHeight is specified, use it
        // 3. Else fall back to card's canvas height
        val cellHeightDp = when {
            gridConfig.aspectRatio != null -> actualCellWidthDp / gridConfig.aspectRatio
            gridConfig.cellHeight != null -> dimToDp(gridConfig.cellHeight, viewportWidth, viewportHeight)
            else -> dimToDp(card.canvas.height, viewportWidth, viewportHeight)
        }
        
        // Calculate separator height if present (content height + margins)
        val separatorContentHeightDp = gridConfig.separator?.let { 
            dimToDp(it.height, viewportWidth, viewportHeight) 
        } ?: 0.dp
        val separatorTotalHeightDp = gridConfig.separator?.let {
            separatorContentHeightDp + it.marginTop.dp + it.marginBottom.dp
        } ?: 0.dp
        
        // Total cell height including separator
        val totalCellHeight = cellHeightDp + separatorTotalHeightDp

        // Use Fixed columns for precise control, since we've calculated the exact count
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = paddingStart,
                end = paddingEnd,
                top = paddingTop,
                bottom = paddingBottom,
            ),
            horizontalArrangement = Arrangement.spacedBy(hSpacing),
            verticalArrangement = Arrangement.spacedBy(vSpacing),
        ) {
            items(
                items = items,
                key = { it.appId }
            ) { item ->
                Column {
                    ThemedGameTile(
                        item = item,
                        card = card,
                        bindings = bindingProvider(item),
                        cellSize = DpSize(actualCellWidthDp, cellHeightDp),
                        onClick = { onItemClick(item) },
                        onFocus = { onItemFocus(item) },
                        stringResolver = stringResolver,
                        themePath = themePath,
                    )
                    // Render separator if configured
                    gridConfig.separator?.let { separator ->
                        SeparatorView(
                            separator = separator,
                            width = actualCellWidthDp,
                            contentHeight = separatorContentHeightDp,
                            stringResolver = stringResolver,
                            themePath = themePath,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A themed game carousel that renders library items in a center-focused pager.
 * Supports both horizontal and vertical orientations.
 * Features:
 * - Center-focused scrolling with snap-to-center behavior
 * - Highlighted/focused item scales up
 * - Configurable alignment within container
 * - Supports touch swipe and controller navigation
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThemedGameCarousel(
    items: List<LibraryItem>,
    carouselConfig: LayoutNode.Carousel,
    card: ThemeCard,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    onItemClick: (LibraryItem) -> Unit = {},
    onItemFocus: (LibraryItem) -> Unit = {},
    bindingProvider: (LibraryItem) -> Map<String, String> = { emptyMap() },
    themePath: String? = null,
) {
    val context = LocalContext.current
    val stringResolver = remember(themePath) {
        ThemeStringResolver(context, context.assets)
    }
    
    val isVertical = carouselConfig.orientation == CarouselOrientation.VERTICAL

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        
        // Calculate item dimensions
        val itemWidth = dimToDp(carouselConfig.itemSize.width, viewportWidth, viewportHeight)
        val itemHeight = dimToDp(carouselConfig.itemSize.height, viewportWidth, viewportHeight)
        val spacing = carouselConfig.itemSpacing.dp
        val focusedScale = carouselConfig.focusedScale

        // Maximum scaled size for layout calculations
        val scaledItemWidth = itemWidth * focusedScale
        val scaledItemHeight = itemHeight * focusedScale
        
        // Clamp initial page to valid range
        val safeInitialPage = initialPage.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        
        val pagerState = rememberPagerState(
            initialPage = safeInitialPage,
            pageCount = { items.size }
        )
        
        // Coroutine scope for animating page changes
        val coroutineScope = rememberCoroutineScope()
        
        // Focus requester for controller navigation
        val focusRequester = remember { FocusRequester() }
        
        // Spatial focus manager for directional navigation based on screen position
        val spatialFocusManager = LocalSpatialFocusManager.current
        
        // Use configured navigationId or default to "carousel"
        val carouselNavId = carouselConfig.navigationId ?: "carousel"
        
        // Track if carousel has focus (for fading effect)
        // Default to TRUE - carousel is considered focused until explicitly unfocused
        var carouselHasFocus by remember { mutableStateOf(true) }
        
        // Track if carousel has ever had focus (to know if unfocus is intentional)
        var hasEverHadFocus by remember { mutableStateOf(false) }
        
        // Animate carousel opacity based on focus state
        // Only fade if we've had focus before and now lost it
        val carouselAlpha by animateFloatAsState(
            targetValue = if (carouselHasFocus || !hasEverHadFocus) 1f else 0.5f,
            animationSpec = tween(durationMillis = 200),
            label = "carouselFocusAlpha"
        )
        
        // Request focus when carousel is first displayed
        LaunchedEffect(Unit) {
            // Small delay to ensure layout is complete
            kotlinx.coroutines.delay(100)
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // Focus request can fail if not attached yet, ignore
            }
        }
        
        // Notify when focused item changes and save position
        LaunchedEffect(pagerState.currentPage) {
            items.getOrNull(pagerState.currentPage)?.let { onItemFocus(it) }
            onPageChanged(pagerState.currentPage)
        }
        
        // Focused background image with crossfade transition
        carouselConfig.focusedBackground?.let { bgBinding ->
            val focusedItem = items.getOrNull(pagerState.currentPage)
            val focusedBindings = focusedItem?.let { bindingProvider(it) } ?: emptyMap()
            val bgImageUrl = resolveStringBinding(bgBinding, focusedBindings)
            
            // Preload adjacent images for smoother transitions
            val prevItem = items.getOrNull(pagerState.currentPage - 1)
            val nextItem = items.getOrNull(pagerState.currentPage + 1)
            val prevUrl = prevItem?.let { resolveStringBinding(bgBinding, bindingProvider(it)) }
            val nextUrl = nextItem?.let { resolveStringBinding(bgBinding, bindingProvider(it)) }
            
            PreloadImages(urls = listOfNotNull(prevUrl, nextUrl))
            
            CarouselBackgroundImage(
                imageUrl = bgImageUrl,
                opacity = carouselConfig.backgroundOpacity,
                transitionSpeed = carouselConfig.backgroundTransitionSpeed,
            )
        }
        
        // Calculate content padding to center items
        val horizontalContentPadding = (viewportWidth - itemWidth) / 2
        val verticalContentPadding = (viewportHeight - itemHeight) / 2
        
        // Calculate offsets
        val verticalOffset = dimToDp(carouselConfig.verticalOffset, viewportWidth, viewportHeight)
        val horizontalOffset = dimToDp(carouselConfig.horizontalOffset, viewportWidth, viewportHeight)
        
        // Alignment based on config
        val verticalArrangement = when (carouselConfig.verticalAlign) {
            VerticalAlign.CENTER -> Arrangement.Center
            VerticalAlign.BOTTOM -> Arrangement.Bottom
            VerticalAlign.TOP -> Arrangement.Top
        }
        
        val horizontalArrangement = when (carouselConfig.horizontalAlign) {
            HorizontalAlign.CENTER -> Arrangement.Center
            HorizontalAlign.END -> Arrangement.End
            HorizontalAlign.START -> Arrangement.Start
        }
        
        // Helper to navigate using explicit target or spatial navigation
        fun navigateToTarget(explicitTarget: String?, direction: SpatialFocusManager.Direction): Boolean {
            return if (explicitTarget != null) {
                spatialFocusManager?.navigateTo(explicitTarget) ?: false
            } else {
                spatialFocusManager?.navigateInDirection(carouselNavId, direction) ?: false
            }
        }
        
        // Key event handler - swap primary/secondary directions based on orientation
        val keyEventHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                when (keyEvent.key) {
                    // Primary scroll direction (Left/Right for horizontal, Up/Down for vertical)
                    Key.DirectionLeft -> {
                        if (!isVertical && pagerState.currentPage > 0) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                            true
                        } else if (isVertical || pagerState.currentPage == 0) {
                            // For vertical carousel or at start of horizontal, navigate left
                            navigateToTarget(carouselConfig.navigateLeft, SpatialFocusManager.Direction.LEFT)
                        } else false
                    }
                    Key.DirectionRight -> {
                        if (!isVertical && pagerState.currentPage < items.size - 1) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                            true
                        } else if (isVertical || pagerState.currentPage >= items.size - 1) {
                            // For vertical carousel or at end of horizontal, navigate right
                            navigateToTarget(carouselConfig.navigateRight, SpatialFocusManager.Direction.RIGHT)
                        } else false
                    }
                    Key.DirectionUp -> {
                        if (isVertical && pagerState.currentPage > 0) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                            true
                        } else if (!isVertical || pagerState.currentPage == 0) {
                            // For horizontal carousel or at start of vertical, navigate up
                            navigateToTarget(carouselConfig.navigateUp, SpatialFocusManager.Direction.UP)
                        } else false
                    }
                    Key.DirectionDown -> {
                        if (isVertical && pagerState.currentPage < items.size - 1) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                            true
                        } else if (!isVertical || pagerState.currentPage >= items.size - 1) {
                            // For horizontal carousel or at end of vertical, navigate down
                            navigateToTarget(carouselConfig.navigateDown, SpatialFocusManager.Direction.DOWN)
                        } else false
                    }
                    Key.Enter, Key.DirectionCenter, Key.ButtonA -> {
                        items.getOrNull(pagerState.currentPage)?.let { onItemClick(it) }
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        
        // Focus change handler
        val focusChangeHandler: (androidx.compose.ui.focus.FocusState) -> Unit = { focusState ->
            val nowHasFocus = focusState.isFocused
            carouselHasFocus = nowHasFocus
            if (nowHasFocus) {
                hasEverHadFocus = true
                spatialFocusManager?.setFocused(carouselNavId)
            }
        }
        
        // Get focused item offsets from config
        val focusedOffsetX = carouselConfig.focusedOffsetX.dp
        val focusedOffsetY = carouselConfig.focusedOffsetY.dp
        val focusedSpacing = carouselConfig.focusedSpacing.dp
        
        // Render pager content
        val renderPageContent: @Composable (Int) -> Unit = { page ->
            val item = items.getOrNull(page)
            if (item != null) {
                val bindings = bindingProvider(item)
                
                // Calculate signed offset from current page (negative = before, positive = after)
                val signedPageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                
                // Calculate absolute distance for scale/alpha calculations
                val pageOffset = signedPageOffset.absoluteValue.coerceIn(0f, 1f)
                
                // Scale from focusedScale (at center) to 1.0 (at edges)
                val scale = lerp(
                    start = focusedScale,
                    stop = 1f,
                    fraction = pageOffset
                )
                
                // Fade non-focused items slightly for depth effect
                val alpha = lerp(
                    start = 1f,
                    stop = 0.6f,
                    fraction = pageOffset
                )
                
                // Z-index: focused item (pageOffset=0) gets highest value
                val zIndex = 1f - pageOffset
                
                // Determine if this page is focused (near-zero offset from current)
                val isFocused = pageOffset < 0.5f
                
                // Calculate focused offset (smoothly interpolated based on focus)
                val focusProgress = 1f - pageOffset
                val offsetX = focusedOffsetX * focusProgress
                val offsetY = focusedOffsetY * focusProgress
                
                // Calculate spacing offset to push items away from the focused item
                // Uses signed offset directly for smooth, continuous transitions:
                // - signedPageOffset > 0 (item before focus) → push backward (negative)
                // - signedPageOffset < 0 (item after focus) → push forward (positive)
                // - signedPageOffset = 0 (focused item) → no push
                // Clamp to [-1, 1] so items far away don't get excessive push
                val clampedSignedOffset = signedPageOffset.coerceIn(-1f, 1f)
                val spacingOffset = -focusedSpacing * clampedSignedOffset
                
                // Apply spacing offset based on carousel orientation
                val finalOffsetX = offsetX + if (!isVertical) spacingOffset else 0.dp
                val finalOffsetY = offsetY + if (isVertical) spacingOffset else 0.dp
                
                Box(
                    modifier = Modifier
                        .zIndex(zIndex)
                        .size(itemWidth, itemHeight)
                        .offset(x = finalOffsetX, y = finalOffsetY)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                        .clickable { onItemClick(item) },
                ) {
                    // Render each layer from the card
                    val isPortrait = rememberIsPortrait()
                    card.layers
                        .filter { layer -> layer.visibility.isVisible(isPortrait) }
                        .forEach { layer ->
                            RenderCarouselLayer(
                                layer = layer,
                                bindings = bindings,
                                parentSize = DpSize(itemWidth, itemHeight),
                                stringResolver = stringResolver,
                                themePath = themePath,
                                isFocused = isFocused,
                                focusProgress = 1f - pageOffset,
                            )
                        }
                }
            }
        }
        
        if (isVertical) {
            // Vertical carousel layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = horizontalOffset, y = verticalOffset)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onFocusChanged(focusChangeHandler)
                    .graphicsLayer { alpha = carouselAlpha }
                    .onKeyEvent(keyEventHandler),
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier
                        .width(scaledItemWidth)
                        .fillMaxHeight()
                        .onGloballyPositioned { coordinates ->
                            spatialFocusManager?.register(
                                id = carouselNavId,
                                bounds = coordinates.boundsInRoot(),
                                focusRequester = focusRequester
                            )
                        },
                    contentPadding = PaddingValues(vertical = verticalContentPadding),
                    pageSpacing = spacing,
                    flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    userScrollEnabled = true,
                ) { page ->
                    renderPageContent(page)
                }
            }
        } else {
            // Horizontal carousel layout (original behavior)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = horizontalOffset, y = verticalOffset)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onFocusChanged(focusChangeHandler)
                    .graphicsLayer { alpha = carouselAlpha }
                    .onKeyEvent(keyEventHandler),
                verticalArrangement = verticalArrangement,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(scaledItemHeight)
                        .onGloballyPositioned { coordinates ->
                            spatialFocusManager?.register(
                                id = carouselNavId,
                                bounds = coordinates.boundsInRoot(),
                                focusRequester = focusRequester
                            )
                        },
                    contentPadding = PaddingValues(horizontal = horizontalContentPadding),
                    pageSpacing = spacing,
                    flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
                    verticalAlignment = Alignment.CenterVertically,
                    userScrollEnabled = true,
                ) { page ->
                    renderPageContent(page)
                }
            }
        }
    }
}

/**
 * Resolves a StringOrBinding to an actual string value using the provided bindings map.
 */
private fun resolveStringBinding(binding: StringOrBinding, bindings: Map<String, String>): String? {
    return when (binding) {
        is StringOrBinding.Literal -> binding.value
        is StringOrBinding.Ref -> bindings[binding.binding.path]
    }
}

/**
 * Preloads images into Coil's cache for smoother transitions.
 */
@Composable
private fun PreloadImages(urls: List<String>) {
    val context = LocalContext.current
    val imageLoader = remember { coil.ImageLoader(context) }
    
    LaunchedEffect(urls) {
        urls.forEach { url ->
            if (url.isNotBlank()) {
                val request = coil.request.ImageRequest.Builder(context)
                    .data(url)
                    .build()
                imageLoader.enqueue(request)
            }
        }
    }
}

/**
 * Full-screen background image with crossfade transition.
 * Used by carousels to show the focused item's hero image behind the content.
 */
@Composable
private fun BoxScope.CarouselBackgroundImage(
    imageUrl: String?,
    opacity: Float,
    transitionSpeed: Int,
) {
    // Crossfade between different images when URL changes
    Crossfade(
        targetState = imageUrl,
        animationSpec = tween(durationMillis = transitionSpeed),
        modifier = Modifier.matchParentSize(),
        label = "backgroundCrossfade"
    ) { url ->
        if (url != null) {
            CoilImage(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = opacity },
                imageModel = { url },
                imageOptions = ImageOptions(
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                ),
            )
        }
    }
}

/**
 * Render a single theme layer for carousel items.
 * Handles focusOnly layers with fade transitions.
 */
@Composable
private fun BoxScope.RenderCarouselLayer(
    layer: Layer,
    bindings: Map<String, String>,
    parentSize: DpSize,
    stringResolver: ThemeStringResolver,
    themePath: String?,
    isFocused: Boolean,
    focusProgress: Float, // 0 to 1, where 1 = fully focused
) {
    // Check conditional visibility based on binding value
    layer.visibleWhen?.let { bindingPath ->
        val bindingValue = bindings[bindingPath] ?: "false"
        if (bindingValue != "true") return
    }
    
    // If layer is focusOnly and not focused, animate opacity
    if (layer.focusOnly) {
        // Animate the alpha based on focus state
        val targetAlpha = if (isFocused) 1f else 0f
        val animatedAlpha by animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec = tween(durationMillis = layer.focusTransitionSpeed),
            label = "focusOnlyAlpha"
        )
        
        // Skip rendering entirely if invisible
        if (animatedAlpha <= 0.01f) return
        
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = animatedAlpha }
        ) {
            RenderThemedLayer(
                layer = layer,
                bindings = bindings,
                parentSize = parentSize,
                onImageLoadFailed = {},
                stringResolver = stringResolver,
                themePath = themePath,
            )
        }
    } else {
        // Regular layer, render normally
        RenderThemedLayer(
            layer = layer,
            bindings = bindings,
            parentSize = parentSize,
            onImageLoadFailed = {},
            stringResolver = stringResolver,
            themePath = themePath,
        )
    }
}

/**
 * Renders a separator between grid items.
 * Layers are rendered without game bindings (static content only).
 */
@Composable
private fun SeparatorView(
    separator: GridSeparator,
    width: Dp,
    contentHeight: Dp,
    stringResolver: ThemeStringResolver,
    themePath: String?,
) {
    // Apply margins
    val marginTop = separator.marginTop.dp
    val marginBottom = separator.marginBottom.dp
    val marginStart = separator.marginStart.dp
    val marginEnd = separator.marginEnd.dp
    
    // Content area size (excluding margins)
    val contentWidth = width - marginStart - marginEnd
    val parentSize = DpSize(contentWidth, contentHeight)
    
    // Empty bindings - separator doesn't have access to game data
    val emptyBindings = emptyMap<String, String>()
    
    // Total height includes margins
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = marginTop, bottom = marginBottom, start = marginStart, end = marginEnd)
    ) {
        Box(
            modifier = Modifier.size(contentWidth, contentHeight)
        ) {
            // Get current orientation for visibility filtering (centralized)
            val isPortrait = rememberIsPortrait()
            
            separator.layers
                .filter { layer -> layer.visibility.isVisible(isPortrait) }
                .forEach { layer ->
                    RenderThemedLayer(
                        layer = layer,
                        bindings = emptyBindings,
                        parentSize = parentSize,
                        stringResolver = stringResolver,
                        themePath = themePath,
                    )
                }
        }
    }
}

/**
 * A single game tile rendered using theme card layers.
 */
@Composable
private fun ThemedGameTile(
    item: LibraryItem,
    card: ThemeCard,
    bindings: Map<String, String>,
    cellSize: DpSize,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    stringResolver: ThemeStringResolver,
    themePath: String?,
) {
    var isFocused by remember { mutableStateOf(false) }
    var imageLoadFailed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(cellSize.width, cellSize.height)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (isFocused) onFocus()
            }
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 3.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            )
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                } else Modifier
            )
    ) {
        // Get current orientation for visibility filtering (centralized)
        val isPortrait = rememberIsPortrait()
        
        // Render each layer from the card, filtering by visibility
        card.layers
            .filter { layer -> layer.visibility.isVisible(isPortrait) }
            .forEach { layer ->
                RenderThemedLayer(
                    layer = layer,
                    bindings = bindings,
                    parentSize = cellSize,
                    onImageLoadFailed = { imageLoadFailed = true },
                    stringResolver = stringResolver,
                    themePath = themePath,
                )
            }

        // Fallback: Show title prominently if image failed
        if (imageLoadFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

/**
 * Render a single theme layer with proper binding resolution.
 */
@Composable
private fun BoxScope.RenderThemedLayer(
    layer: Layer,
    bindings: Map<String, String>,
    parentSize: DpSize,
    onImageLoadFailed: () -> Unit = {},
    stringResolver: ThemeStringResolver,
    themePath: String?,
) {
    // Check conditional visibility based on binding value
    layer.visibleWhen?.let { bindingPath ->
        val bindingValue = bindings[bindingPath] ?: "false"
        if (bindingValue != "true") return
    }
    
    when (layer) {
        is Layer.ImageLayer -> {
            val src = resolveBinding(layer.source.src, bindings)
            val shape = parseCornerRadius(layer.cornerRadius)
            val rawX = dimToDp(layer.position.x, parentSize.width, parentSize.height)
            val rawY = dimToDp(layer.position.y, parentSize.width, parentSize.height)
            val rawW = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) }
            val rawH = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) }
            val w = if (rawW == null || rawW == Dp.Unspecified) parentSize.width else rawW
            val h = if (rawH == null || rawH == Dp.Unspecified) parentSize.height else rawH
            val pos = calculateAnchoredPosition(rawX, rawY, w, h, parentSize.width, parentSize.height, layer.anchor)
            val alpha = resolveFloatBinding(layer.opacity, 1f)

            if (src.isNotBlank()) {
                val contentScale = when (layer.scaleType.lowercase()) {
                    "contain", "fit" -> ContentScale.Fit
                    "stretch", "fill" -> ContentScale.FillBounds
                    else -> ContentScale.Crop // "cover" is default
                }
                Box(
                    modifier = Modifier
                        .zIndex(layer.zIndex)
                        .offset(x = pos.x, y = pos.y)
                        .size(w, h)
                        .clip(shape)
                        .alpha(alpha)
                ) {
                    CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { src },
                        imageOptions = ImageOptions(
                            contentScale = contentScale,
                            contentDescription = null,
                        ),
                        failure = {
                            onImageLoadFailed()
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF444444))
                            )
                        },
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF333333))
                            )
                        }
                    )
                }
            } else {
                // No image URL - show placeholder
                Box(
                    modifier = Modifier
                        .zIndex(layer.zIndex)
                        .offset(x = pos.x, y = pos.y)
                        .size(w, h)
                        .clip(shape)
                        .background(Color(0xFF555555))
                )
            }
        }

        is Layer.RectLayer -> {
            val rawX = dimToDp(layer.position.x, parentSize.width, parentSize.height)
            val rawY = dimToDp(layer.position.y, parentSize.width, parentSize.height)
            val rawW = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) }
            val rawH = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) }
            val w = if (rawW == null || rawW == Dp.Unspecified) parentSize.width else rawW
            val h = if (rawH == null || rawH == Dp.Unspecified) parentSize.height else rawH
            val pos = calculateAnchoredPosition(rawX, rawY, w, h, parentSize.width, parentSize.height, layer.anchor)
            val shape = parseCornerRadius(layer.cornerRadius)
            val fillColor = resolveIntBinding(layer.color, 0x66000000, bindings)
            val alpha = resolveFloatBinding(layer.opacity, 1f)
            val borderWidth = resolveFloatBinding(layer.borderWidth, 0f)
            val borderColor = resolveIntBinding(layer.borderColor, 0xFFFFFFFF.toInt(), bindings)

            Box(
                modifier = Modifier
                    .zIndex(layer.zIndex)
                    .offset(x = pos.x, y = pos.y)
                    .size(w, h)
                    .clip(shape)
                    .alpha(alpha)
                    .background(Color(fillColor))
                    .then(
                        if (borderWidth > 0f) {
                            Modifier.border(borderWidth.dp, Color(borderColor), shape)
                        } else {
                            Modifier
                        }
                    )
            )
        }

        is Layer.TextLayer -> {
            val rawText = resolveBinding(layer.text, bindings)
            val text = stringResolver.resolve(rawText, themePath)
            val rawX = dimToDp(layer.position.x, parentSize.width, parentSize.height)
            val rawY = dimToDp(layer.position.y, parentSize.width, parentSize.height)
            // Handle Dp.Unspecified properly - use default if unspecified
            val rawW = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) }
            val rawH = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) }
            val w = if (rawW == null || rawW == Dp.Unspecified) parentSize.width else rawW
            val h = if (rawH == null || rawH == Dp.Unspecified) 24.dp else rawH
            val pos = calculateAnchoredPosition(rawX, rawY, w, h, parentSize.width, parentSize.height, layer.anchor)
            val textSize = resolveFloatBinding(layer.textSize, 14f)
            val color = resolveIntBinding(layer.color, 0xFFFFFFFF.toInt(), bindings)
            val alpha = resolveFloatBinding(layer.opacity, 1f)

            // Treat textSize as sp directly (theme authors specify logical size)
            val textSizeSp = textSize.sp

            val textAlignment = when (layer.textAlign.lowercase()) {
                "center" -> androidx.compose.ui.text.style.TextAlign.Center
                "right" -> androidx.compose.ui.text.style.TextAlign.End
                else -> androidx.compose.ui.text.style.TextAlign.Start
            }
            val boxAlignment = when (layer.textAlign.lowercase()) {
                "center" -> Alignment.Center
                "right" -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }
            val fontWeight = when (layer.fontWeight.lowercase()) {
                "bold" -> FontWeight.Bold
                "semibold" -> FontWeight.SemiBold
                "medium" -> FontWeight.Medium
                "light" -> FontWeight.Light
                "thin" -> FontWeight.Thin
                "extrabold", "black" -> FontWeight.ExtraBold
                else -> FontWeight.Normal
            }
            val fontStyle = when (layer.fontStyle.lowercase()) {
                "italic" -> androidx.compose.ui.text.font.FontStyle.Italic
                else -> androidx.compose.ui.text.font.FontStyle.Normal
            }

            Box(
                modifier = Modifier
                    .zIndex(layer.zIndex)
                    .offset(x = pos.x, y = pos.y)
                    .size(w, h)
                    .alpha(alpha),
                contentAlignment = boxAlignment
            ) {
                Text(
                    text = text,
                    color = Color(color),
                    fontSize = textSizeSp,
                    fontWeight = fontWeight,
                    fontStyle = fontStyle,
                    maxLines = layer.maxLines ?: 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlignment,
                )
            }
        }

        is Layer.BorderLayer -> {
            val rawX = dimToDp(layer.position.x, parentSize.width, parentSize.height)
            val rawY = dimToDp(layer.position.y, parentSize.width, parentSize.height)
            val rawW = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) }
            val rawH = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) }
            val w = if (rawW == null || rawW == Dp.Unspecified) parentSize.width else rawW
            val h = if (rawH == null || rawH == Dp.Unspecified) parentSize.height else rawH
            val pos = calculateAnchoredPosition(rawX, rawY, w, h, parentSize.width, parentSize.height, layer.anchor)
            val shape = parseCornerRadius(layer.cornerRadius)
            val borderWidth = resolveFloatBinding(layer.strokeWidth, 1f).dp
            val color = resolveIntBinding(layer.color, 0xFFFFFFFF.toInt(), bindings)
            val alpha = resolveFloatBinding(layer.opacity, 1f)

            Box(
                modifier = Modifier
                    .zIndex(layer.zIndex)
                    .offset(x = pos.x, y = pos.y)
                    .size(w, h)
                    .alpha(alpha)
                    .border(width = borderWidth, color = Color(color), shape = shape)
            )
        }

        is Layer.ShadowLayer -> {
            // Shadows are complex in Compose - simplified implementation
            val rawX = dimToDp(layer.position.x, parentSize.width, parentSize.height)
            val rawY = dimToDp(layer.position.y, parentSize.width, parentSize.height)
            val rawW = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) }
            val rawH = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) }
            val w = if (rawW == null || rawW == Dp.Unspecified) parentSize.width else rawW
            val h = if (rawH == null || rawH == Dp.Unspecified) parentSize.height else rawH
            // Note: anchor support added but shadow rendering is simplified/skipped
            // val pos = calculateAnchoredPosition(rawX, rawY, w, h, parentSize.width, parentSize.height, layer.anchor)
        }

        is Layer.VideoLayer -> {
            // Video not supported in grid tiles - show poster or placeholder
            val rawX = dimToDp(layer.position.x, parentSize.width, parentSize.height)
            val rawY = dimToDp(layer.position.y, parentSize.width, parentSize.height)
            val rawW = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) }
            val rawH = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) }
            val w = if (rawW == null || rawW == Dp.Unspecified) parentSize.width else rawW
            val h = if (rawH == null || rawH == Dp.Unspecified) parentSize.height else rawH
            val pos = calculateAnchoredPosition(rawX, rawY, w, h, parentSize.width, parentSize.height, layer.anchor)

            Box(
                modifier = Modifier
                    .zIndex(layer.zIndex)
                    .offset(x = pos.x, y = pos.y)
                    .size(w, h)
                    .background(Color(0xFF303030)),
                contentAlignment = Alignment.Center
            ) {
                Text("VIDEO", color = Color.White, fontSize = 10.sp)
            }
        }

        is Layer.BackdropLayer -> {
            // Backdrop blur effects - simplified
            val rawX = dimToDp(layer.position.x, parentSize.width, parentSize.height)
            val rawY = dimToDp(layer.position.y, parentSize.width, parentSize.height)
            val rawW = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) }
            val rawH = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) }
            val w = if (rawW == null || rawW == Dp.Unspecified) parentSize.width else rawW
            val h = if (rawH == null || rawH == Dp.Unspecified) parentSize.height else rawH
            val pos = calculateAnchoredPosition(rawX, rawY, w, h, parentSize.width, parentSize.height, layer.anchor)
            val tint = resolveIntBinding(layer.tintColor, 0, bindings)

            if (tint != 0) {
                Box(
                    modifier = Modifier
                        .zIndex(layer.zIndex)
                        .offset(x = pos.x, y = pos.y)
                        .size(w, h)
                        .background(Color(tint).copy(alpha = 0.5f))
                )
            }
        }

        is Layer.ButtonLayer -> {
            val x = dimToDp(layer.position.x, parentSize.width, parentSize.height)
            val y = dimToDp(layer.position.y, parentSize.width, parentSize.height)
            // Handle Dp.Unspecified properly - use default if unspecified
            val rawW = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) }
            val rawH = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) }
            val w = if (rawW == null || rawW == Dp.Unspecified) 80.dp else rawW
            val h = if (rawH == null || rawH == Dp.Unspecified) 40.dp else rawH
            val pos = calculateAnchoredPosition(x, y, w, h, parentSize.width, parentSize.height, layer.anchor)
            val shape = parseCornerRadius(layer.cornerRadius)
            // Use color resolver for system color support (@color/primary, etc.)
            val bgColor = resolveColorBinding(layer.backgroundColor, MaterialTheme.colorScheme.primary, bindings)
            val txtColor = resolveColorBinding(layer.textColor, MaterialTheme.colorScheme.onPrimary, bindings)
            val rawText = resolveBinding(layer.text, bindings)
            val text = stringResolver.resolve(rawText, themePath)
            val textSizeSp = resolveFloatBinding(layer.textSize, 14f).sp
            val alpha = resolveFloatBinding(layer.opacity, 1f)

            // Parse padding: "vertical horizontal" or single value
            val (paddingVertical, paddingHorizontal) = layer.padding?.let { paddingStr ->
                val parts = paddingStr.trim().split("\\s+".toRegex())
                when (parts.size) {
                    1 -> {
                        val value = parts[0].toFloatOrNull() ?: 0f
                        Pair(value.dp, value.dp)
                    }
                    2 -> {
                        val vertical = parts[0].toFloatOrNull() ?: 0f
                        val horizontal = parts[1].toFloatOrNull() ?: 0f
                        Pair(vertical.dp, horizontal.dp)
                    }
                    else -> Pair(0.dp, 0.dp)
                }
            } ?: Pair(0.dp, 0.dp)

            Box(
                modifier = Modifier
                    .zIndex(layer.zIndex)
                    .offset(x = pos.x, y = pos.y)
                    .size(w, h)
                    .alpha(alpha)
                    .clip(shape)
                    .background(bgColor)
                    .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = txtColor,
                    fontSize = textSizeSp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// region Binding Helpers

private fun resolveBinding(value: StringOrBinding, bindings: Map<String, String>): String {
    return when (value) {
        is StringOrBinding.Literal -> value.value
        is StringOrBinding.Ref -> bindings[value.binding.path] ?: ""
    }
}

private fun resolveFloatBinding(value: FloatOrBinding?, default: Float): Float {
    return when (value) {
        null -> default
        is FloatOrBinding.Literal -> value.value
        is FloatOrBinding.Ref -> default // Runtime bindings not supported yet
    }
}

private fun resolveIntBinding(value: IntOrBinding?, default: Int, bindings: Map<String, String> = emptyMap()): Int {
    return when (value) {
        null -> default
        is IntOrBinding.Literal -> value.value
        is IntOrBinding.Ref -> bindings[value.binding.path]?.let { parseColorString(it) } ?: default
    }
}

/**
 * Resolve a color binding with support for @color/ system references.
 * Supported system colors:
 * - @color/primary, @color/onPrimary
 * - @color/secondary, @color/onSecondary
 * - @color/tertiary, @color/onTertiary
 * - @color/background, @color/onBackground
 * - @color/surface, @color/onSurface
 * - @color/error, @color/onError
 * - @color/surfaceVariant, @color/onSurfaceVariant
 */
@Composable
private fun resolveColorBinding(value: IntOrBinding?, default: Color, bindings: Map<String, String> = emptyMap()): Color {
    val colorScheme = MaterialTheme.colorScheme
    
    return when (value) {
        null -> default
        is IntOrBinding.Literal -> {
            // Check if the literal value encodes a system color reference
            // This happens when the XML has @color/primary as the value
            Color(value.value)
        }
        is IntOrBinding.Ref -> {
            val path = value.binding.path
            // Check for @color/ prefix (system colors)
            if (path.startsWith("@color/")) {
                val colorName = path.removePrefix("@color/")
                resolveSystemColor(colorName, colorScheme) ?: default
            } else {
                // Regular binding from data
                bindings[path]?.let { parseColorString(it) }?.let { Color(it) } ?: default
            }
        }
    }
}

@Composable
private fun resolveSystemColor(name: String, colorScheme: androidx.compose.material3.ColorScheme): Color? {
    return when (name.lowercase()) {
        "primary" -> colorScheme.primary
        "onprimary" -> colorScheme.onPrimary
        "primarycontainer" -> colorScheme.primaryContainer
        "onprimarycontainer" -> colorScheme.onPrimaryContainer
        "secondary" -> colorScheme.secondary
        "onsecondary" -> colorScheme.onSecondary
        "secondarycontainer" -> colorScheme.secondaryContainer
        "onsecondarycontainer" -> colorScheme.onSecondaryContainer
        "tertiary" -> colorScheme.tertiary
        "ontertiary" -> colorScheme.onTertiary
        "tertiarycontainer" -> colorScheme.tertiaryContainer
        "ontertiarycontainer" -> colorScheme.onTertiaryContainer
        "background" -> colorScheme.background
        "onbackground" -> colorScheme.onBackground
        "surface" -> colorScheme.surface
        "onsurface" -> colorScheme.onSurface
        "surfacevariant" -> colorScheme.surfaceVariant
        "onsurfacevariant" -> colorScheme.onSurfaceVariant
        "error" -> colorScheme.error
        "onerror" -> colorScheme.onError
        "errorcontainer" -> colorScheme.errorContainer
        "onerrorcontainer" -> colorScheme.onErrorContainer
        "outline" -> colorScheme.outline
        "outlinevariant" -> colorScheme.outlineVariant
        else -> null
    }
}

private fun parseColorString(s: String): Int? {
    return try {
        android.graphics.Color.parseColor(s)
    } catch (e: Exception) {
        null
    }
}

// Utility functions imported from ThemeUtils - see ThemeUtils.kt for implementations

/** Alias for ThemeUtils.calculateAnchoredPosition with cleaner return type access */
private fun calculateAnchoredPosition(
    rawX: Dp,
    rawY: Dp,
    elementWidth: Dp,
    elementHeight: Dp,
    parentWidth: Dp,
    parentHeight: Dp,
    anchor: Anchor
): ThemeUtils.Placement = ThemeUtils.calculateAnchoredPosition(rawX, rawY, elementWidth, elementHeight, parentWidth, parentHeight, anchor)

// endregion

