package app.gamenative.theme.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Renders fixed UI elements from theme configuration.
 * Falls back to default positioning if no fixed containers are defined.
 */
@Composable
fun BoxScope.RenderFixedElements(
    fixedContainers: List<FixedContainer>,
    state: LibraryState,
    listState: LazyGridState,
    themeName: String,
    callbacks: FixedElementCallbacks,
    accountButtonContent: @Composable () -> Unit,
    searchBarContent: @Composable () -> Unit,
) {
    if (fixedContainers.isEmpty()) {
        // Fallback to default positioning when no fixed containers are defined
        RenderDefaultFixedElements(
            state = state,
            listState = listState,
            themeName = themeName,
            callbacks = callbacks,
            accountButtonContent = accountButtonContent,
            searchBarContent = searchBarContent,
        )
        return
    }

    // Determine current orientation for visibility filtering (centralized)
    val isPortrait = rememberIsPortrait()

    // Render each fixed container with optional background
    fixedContainers.forEach { container ->
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
    accountButtonContent: @Composable () -> Unit,
    searchBarContent: @Composable () -> Unit,
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
            Box(
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
                    .width(dimToDp(element.size.width, parentWidth, parentHeight))
                    .height(dimToDp(element.size.height, parentWidth, parentHeight))
            ) {
                searchBarContent()
            }
        }

        is FixedElement.ProfileButton -> {
            Box(
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(8.dp)
            ) {
                accountButtonContent()
            }
        }

        is FixedElement.FilterButton -> {
            if (!callbacks.isSearching) {
                ExtendedFloatingActionButton(
                    text = { Text(text = "Filters") },
                    icon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = null) },
                    expanded = element.expanded && callbacks.filterExpanded,
                    onClick = callbacks.onFilterClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(alignment)
                        .offset(x = offsetX, y = offsetY)
                )
            }
        }

        is FixedElement.AddButton -> {
            FloatingActionButton(
                onClick = callbacks.onAddClick,
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier
                    .align(alignment)
                    .offset(x = offsetX, y = offsetY)
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

/**
 * Default fixed element layout when no theme configuration is provided.
 */
@Composable
private fun BoxScope.RenderDefaultFixedElements(
    state: LibraryState,
    listState: LazyGridState,
    themeName: String,
    callbacks: FixedElementCallbacks,
    accountButtonContent: @Composable () -> Unit,
    searchBarContent: @Composable () -> Unit,
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
            accountButtonContent()
        }
        // Search bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            searchBarContent()
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
