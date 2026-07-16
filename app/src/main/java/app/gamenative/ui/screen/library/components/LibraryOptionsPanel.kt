package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoSizeSelectActual
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.SteamCollection
import app.gamenative.ui.component.GameStatsKey
import app.gamenative.ui.component.OptionListItem
import app.gamenative.ui.component.OptionRadioItem
import app.gamenative.ui.component.OptionSectionHeader
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.adaptivePanelWidth
import java.util.EnumSet

@Composable
fun LibraryOptionsPanel(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    selectedFilters: EnumSet<AppFilter>,
    onFilterChanged: (AppFilter) -> Unit,
    currentSortOption: SortOption,
    onSortOptionChanged: (SortOption) -> Unit,
    currentView: PaneType,
    onViewChanged: (PaneType) -> Unit,
    steamCollections: List<SteamCollection>?,
    selectedSteamCollectionIds: Set<String>,
    steamCollectionCounts: Map<String, Int>,
    skippedDynamicCollections: Boolean,
    isSteamConnected: Boolean,
    isOffline: Boolean,
    onSteamCollectionToggle: (String) -> Unit,
    onClearSteamCollections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = isOpen) {
        onDismiss()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        ) {
            Surface(
                modifier = Modifier
                    .width(adaptivePanelWidth(300.dp))
                    .fillMaxHeight(),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.options_panel_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.options_panel_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp)
                    ) {
                        GameStatsKey(modifier = Modifier.padding(horizontal = 8.dp))

                        Spacer(modifier = Modifier.height(20.dp))

                        OptionSectionHeader(text = stringResource(R.string.options_sort_by))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            SortOption.entries.forEachIndexed { index, option ->
                                OptionRadioItem(
                                    text = stringResource(option.displayTextRes),
                                    selected = currentSortOption == option,
                                    onClick = { onSortOptionChanged(option) },
                                    icon = option.icon(),
                                    focusRequester = if (index == 0) firstItemFocusRequester else remember { FocusRequester() },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        OptionSectionHeader(text = stringResource(R.string.library_app_type))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            AppFilter.entries.forEach { appFilter ->
                                if (appFilter in listOf(
                                        AppFilter.GAME,
                                        AppFilter.APPLICATION,
                                        AppFilter.TOOL,
                                        AppFilter.DEMO,
                                    )
                                ) {
                                    OptionListItem(
                                        text = stringResource(appFilter.displayTextRes),
                                        selected = selectedFilters.contains(appFilter),
                                        onClick = { onFilterChanged(appFilter) },
                                        icon = appFilter.icon,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        OptionSectionHeader(text = stringResource(R.string.library_app_status))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            AppFilter.entries.forEach { appFilter ->
                                if (appFilter in listOf(
                                        AppFilter.INSTALLED,
                                        AppFilter.SHARED,
                                        AppFilter.COMPATIBLE,
                                        AppFilter.EXPIRED,
                                        AppFilter.PLAYABLE,
                                        AppFilter.FIVE_STAR,
                                        AppFilter.FIVE_STAR_GPU,
                                        AppFilter.PROVEN_GPU,
                                    )
                                ) {
                                    OptionListItem(
                                        text = stringResource(appFilter.displayTextRes),
                                        selected = selectedFilters.contains(appFilter),
                                        onClick = { onFilterChanged(appFilter) },
                                        icon = appFilter.icon,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        OptionSectionHeader(text = stringResource(R.string.library_layout_title))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusGroup()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            OptionRadioItem(
                                text = stringResource(R.string.library_layout_list),
                                selected = currentView == PaneType.LIST,
                                onClick = { onViewChanged(PaneType.LIST) },
                                icon = Icons.AutoMirrored.Filled.List,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OptionRadioItem(
                                text = stringResource(R.string.library_layout_capsule),
                                selected = currentView == PaneType.GRID_CAPSULE,
                                onClick = { onViewChanged(PaneType.GRID_CAPSULE) },
                                icon = Icons.Default.PhotoAlbum,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OptionRadioItem(
                                text = stringResource(R.string.library_layout_hero),
                                selected = currentView == PaneType.GRID_HERO,
                                onClick = { onViewChanged(PaneType.GRID_HERO) },
                                icon = Icons.Default.PhotoSizeSelectActual,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OptionRadioItem(
                                text = stringResource(R.string.library_layout_carousel),
                                selected = currentView == PaneType.CAROUSEL,
                                onClick = { onViewChanged(PaneType.CAROUSEL) },
                                icon = Icons.Default.ViewCarousel,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Steam collections — local view filter, shown only when Steam is connected.
                        if (isSteamConnected) {
                            Spacer(modifier = Modifier.height(20.dp))
                            var collectionsExpanded by rememberSaveable {
                                mutableStateOf(selectedSteamCollectionIds.isNotEmpty())
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { collectionsExpanded = !collectionsExpanded }
                                    .padding(end = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OptionSectionHeader(text = stringResource(R.string.steam_collections_title))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (selectedSteamCollectionIds.isNotEmpty()) {
                                        TextButton(onClick = onClearSteamCollections) {
                                            Text(stringResource(R.string.steam_collections_clear))
                                        }
                                    }
                                    Icon(
                                        imageVector = if (collectionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            AnimatedVisibility(visible = collectionsExpanded) {
                                Column {
                                    when {
                                        steamCollections == null -> {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = stringResource(R.string.steam_collections_loading),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        steamCollections.isEmpty() -> {
                                            // No static collections to list, but still explain why (offline /
                                            // only smart collections) so the section doesn't look broken.
                                            if (isOffline) {
                                                Text(
                                                    text = stringResource(R.string.steam_collections_offline),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                                )
                                            }
                                            if (skippedDynamicCollections) {
                                                Text(
                                                    text = stringResource(R.string.steam_collections_smart_unsupported),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                                )
                                            }
                                        }
                                        else -> {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .focusGroup()
                                                    .padding(horizontal = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                steamCollections.sortedBy { it.name.lowercase() }.forEach { collection ->
                                                    OptionListItem(
                                                        text = collection.name,
                                                        selected = selectedSteamCollectionIds.contains(collection.id),
                                                        onClick = { onSteamCollectionToggle(collection.id) },
                                                        trailingText = steamCollectionCounts[collection.id]?.toString(),
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                            }
                                            if (isOffline) {
                                                Text(
                                                    text = stringResource(R.string.steam_collections_offline),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                                )
                                            }
                                            if (skippedDynamicCollections) {
                                                Text(
                                                    text = stringResource(R.string.steam_collections_smart_unsupported),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            try {
                firstItemFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus request may fail if composition is not ready
            }
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1920px,height=1080px,dpi=440,orientation=landscape"
)
@Composable
private fun Preview_LibraryOptionsPanel() {
    val context = LocalContext.current
    PrefManager.init(context)
    PluviaTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Game Library",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                LibraryOptionsPanel(
                    isOpen = true,
                    onDismiss = { },
                    selectedFilters = EnumSet.of(AppFilter.GAME),
                    onFilterChanged = { },
                    currentSortOption = SortOption.INSTALLED_FIRST,
                    onSortOptionChanged = { },
                    currentView = PaneType.GRID_HERO,
                    onViewChanged = { },
                    steamCollections = listOf(
                        SteamCollection(id = "fav", name = "Favorites", appIds = setOf(440, 570)),
                        SteamCollection(id = "rpg", name = "RPGs", appIds = setOf(292030)),
                    ),
                    selectedSteamCollectionIds = setOf("fav"),
                    steamCollectionCounts = mapOf("fav" to 2, "rpg" to 1),
                    skippedDynamicCollections = true,
                    isSteamConnected = true,
                    isOffline = false,
                    onSteamCollectionToggle = { },
                    onClearSteamCollections = { },
                )
            }
        }
    }
}

private fun SortOption.icon(): ImageVector = when (this) {
    SortOption.INSTALLED_FIRST -> Icons.Default.Download
    SortOption.NAME_ASC -> Icons.Default.SortByAlpha
    SortOption.NAME_DESC -> Icons.Default.SortByAlpha
    SortOption.RECENTLY_PLAYED -> Icons.Default.Schedule
    SortOption.SIZE_SMALLEST -> Icons.Default.Compress
    SortOption.SIZE_LARGEST -> Icons.Default.Storage
    SortOption.FPS_HIGH -> Icons.Rounded.Speed
    SortOption.RUNS_HIGH -> Icons.Rounded.SportsEsports
    SortOption.REVIEWS_HIGH -> Icons.Rounded.Star
    SortOption.REVIEWS_GPU_HIGH -> Icons.Rounded.Stars
}
