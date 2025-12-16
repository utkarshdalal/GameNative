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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
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
 * A themed game carousel that renders library items in a center-focused horizontal pager.
 * Features:
 * - Center-focused scrolling with snap-to-center behavior
 * - Highlighted/focused item scales up
 * - Vertically centered on screen
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        
        // Calculate item dimensions
        val itemWidth = dimToDp(carouselConfig.itemSize.width, viewportWidth, viewportHeight)
        val itemHeight = dimToDp(carouselConfig.itemSize.height, viewportWidth, viewportHeight)
        val spacing = carouselConfig.itemSpacing.dp
        val highlightScale = carouselConfig.highlightScale
        
        // Maximum scaled size for layout calculations
        val scaledItemHeight = itemHeight * highlightScale
        
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
        
        // Focus manager for moving focus to other elements (e.g., bottom buttons)
        val focusManager = LocalFocusManager.current
        
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
        
        // Calculate content padding to center items horizontally
        // The center of the current page should be at the center of the screen
        val horizontalContentPadding = (viewportWidth - itemWidth) / 2
        
        // Calculate vertical offset
        val verticalOffset = dimToDp(carouselConfig.verticalOffset, viewportWidth, viewportHeight)
        
        // Vertical alignment based on config
        val verticalArrangement = when (carouselConfig.verticalAlign) {
            VerticalAlign.CENTER -> Arrangement.Center
            VerticalAlign.BOTTOM -> Arrangement.Bottom
            VerticalAlign.TOP -> Arrangement.Top
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = verticalOffset)
                .focusRequester(focusRequester)
                .focusable()
                .onFocusChanged { focusState ->
                    // Track if this exact element has focus
                    val nowHasFocus = focusState.isFocused
                    carouselHasFocus = nowHasFocus
                    
                    // Once we've gained focus, mark it so we know future unfocus is intentional
                    if (nowHasFocus) {
                        hasEverHadFocus = true
                    }
                }
                // Apply alpha AFTER focus setup so changes are visible
                .graphicsLayer { alpha = carouselAlpha }
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.DirectionLeft -> {
                                if (pagerState.currentPage > 0) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                if (pagerState.currentPage < items.size - 1) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                // Move focus to elements below (e.g., bottom buttons)
                                focusManager.moveFocus(FocusDirection.Down)
                                true
                            }
                            Key.DirectionUp -> {
                                // Move focus to elements above (e.g., search bar, profile)
                                focusManager.moveFocus(FocusDirection.Up)
                                true
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
                },
            verticalArrangement = verticalArrangement,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(scaledItemHeight),
                contentPadding = PaddingValues(horizontal = horizontalContentPadding),
                pageSpacing = spacing,
                flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
                verticalAlignment = Alignment.CenterVertically,
                // Disable user scroll gesture to prevent conflicts with controller input
                userScrollEnabled = true,
            ) { page ->
                val item = items.getOrNull(page) ?: return@HorizontalPager
                val bindings = bindingProvider(item)
                
                // Calculate scale based on distance from current page
                val pageOffset = (
                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).absoluteValue.coerceIn(0f, 1f)
                
                // Scale from highlightScale (at center) to 1.0 (at edges)
                val scale = lerp(
                    start = highlightScale,
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
                
                Box(
                    modifier = Modifier
                        .zIndex(zIndex)
                        .size(itemWidth, itemHeight)
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
                                focusProgress = 1f - pageOffset, // 1 when focused, 0 when not
                            )
                        }
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
            val w = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) } ?: parentSize.width
            val h = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) } ?: parentSize.height
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
            val w = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) } ?: parentSize.width
            val h = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) } ?: parentSize.height
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
            val w = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) } ?: parentSize.width
            val h = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) } ?: 24.dp
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
            val w = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) } ?: parentSize.width
            val h = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) } ?: parentSize.height
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
            val w = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) } ?: parentSize.width
            val h = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) } ?: parentSize.height
            // Note: anchor support added but shadow rendering is simplified/skipped
            // val pos = calculateAnchoredPosition(rawX, rawY, w, h, parentSize.width, parentSize.height, layer.anchor)
        }

        is Layer.VideoLayer -> {
            // Video not supported in grid tiles - show poster or placeholder
            val rawX = dimToDp(layer.position.x, parentSize.width, parentSize.height)
            val rawY = dimToDp(layer.position.y, parentSize.width, parentSize.height)
            val w = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) } ?: parentSize.width
            val h = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) } ?: parentSize.height
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
            val w = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) } ?: parentSize.width
            val h = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) } ?: parentSize.height
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
            val w = layer.size?.let { dimToDp(it.width, parentSize.width, parentSize.height) } ?: 80.dp
            val h = layer.size?.let { dimToDp(it.height, parentSize.width, parentSize.height) } ?: 40.dp
            val pos = calculateAnchoredPosition(x, y, w, h, parentSize.width, parentSize.height, layer.anchor)
            val shape = parseCornerRadius(layer.cornerRadius)
            // Use color resolver for system color support (@color/primary, etc.)
            val bgColor = resolveColorBinding(layer.backgroundColor, MaterialTheme.colorScheme.primary, bindings)
            val txtColor = resolveColorBinding(layer.textColor, MaterialTheme.colorScheme.onPrimary, bindings)
            val rawText = resolveBinding(layer.text, bindings)
            val text = stringResolver.resolve(rawText, themePath)
            val textSizeSp = resolveFloatBinding(layer.textSize, 14f).sp
            val alpha = resolveFloatBinding(layer.opacity, 1f)

            Box(
                modifier = Modifier
                    .zIndex(layer.zIndex)
                    .offset(x = pos.x, y = pos.y)
                    .size(w, h)
                    .alpha(alpha)
                    .clip(shape)
                    .background(bgColor),
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

/**
 * Calculate actual x,y position based on anchor (CSS-like positioning).
 * 
 * With anchor="topLeft" (default): x is from left edge, y is from top edge
 * With anchor="topRight": x is from right edge (inward), y is from top edge
 * With anchor="bottomRight": x is from right edge (inward), y is from bottom edge (inward)
 * etc.
 * 
 * Positive values always mean "inset" from the anchor edge.
 */
private data class AnchoredPosition(val x: Dp, val y: Dp)

private fun calculateAnchoredPosition(
    rawX: Dp,
    rawY: Dp,
    elementWidth: Dp,
    elementHeight: Dp,
    parentWidth: Dp,
    parentHeight: Dp,
    anchor: Anchor
): AnchoredPosition {
    val x = when (anchor) {
        Anchor.TOP_LEFT, Anchor.CENTER_LEFT, Anchor.BOTTOM_LEFT -> rawX
        Anchor.TOP_CENTER, Anchor.CENTER, Anchor.BOTTOM_CENTER -> 
            (parentWidth - elementWidth) / 2 + rawX
        Anchor.TOP_RIGHT, Anchor.CENTER_RIGHT, Anchor.BOTTOM_RIGHT -> 
            parentWidth - elementWidth - rawX
    }
    
    val y = when (anchor) {
        Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT -> rawY
        Anchor.CENTER_LEFT, Anchor.CENTER, Anchor.CENTER_RIGHT -> 
            (parentHeight - elementHeight) / 2 + rawY
        Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT -> 
            parentHeight - elementHeight - rawY
    }
    
    return AnchoredPosition(x, y)
}

/**
 * Convert a Dimension to Dp, handling pixels and percentages.
 * @param d The dimension to convert
 * @param parentW The parent width for percentage calculations
 * @param parentH The parent height for percentage calculations
 */
private fun dimToDp(d: Dimension, parentW: Dp, parentH: Dp): Dp = when (d) {
    is Dimension.Px -> d.value.dp
    is Dimension.RelW -> parentW * d.fraction
    is Dimension.RelH -> parentH * d.fraction
}

/**
 * Parse CSS-like corner radius string into a RoundedCornerShape.
 * - "8" = all corners 8dp
 * - "8 4" = top-left/bottom-right 8dp, top-right/bottom-left 4dp
 * - "8 4 2" = top-left 8dp, top-right/bottom-left 4dp, bottom-right 2dp
 * - "8 4 2 1" = top-left 8dp, top-right 4dp, bottom-right 2dp, bottom-left 1dp
 */
private fun parseCornerRadius(value: String?): RoundedCornerShape {
    if (value.isNullOrBlank()) return RoundedCornerShape(0.dp)
    
    val parts = value.trim().split("\\s+".toRegex()).mapNotNull { it.toFloatOrNull() }
    
    return when (parts.size) {
        0 -> RoundedCornerShape(0.dp)
        1 -> RoundedCornerShape(parts[0].dp)
        2 -> RoundedCornerShape(
            topStart = parts[0].dp,
            topEnd = parts[1].dp,
            bottomEnd = parts[0].dp,
            bottomStart = parts[1].dp
        )
        3 -> RoundedCornerShape(
            topStart = parts[0].dp,
            topEnd = parts[1].dp,
            bottomEnd = parts[2].dp,
            bottomStart = parts[1].dp
        )
        else -> RoundedCornerShape(
            topStart = parts[0].dp,
            topEnd = parts[1].dp,
            bottomEnd = parts[2].dp,
            bottomStart = parts[3].dp
        )
    }
}

// endregion

