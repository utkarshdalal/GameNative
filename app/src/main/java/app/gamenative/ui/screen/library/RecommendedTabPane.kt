package app.gamenative.ui.screen.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.gog.GogRecCard
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.model.GogRecommendationsViewModel
import app.gamenative.ui.screen.library.components.LibraryCarouselPane
import app.gamenative.ui.screen.library.components.LibraryListPane
import com.posthog.PostHog
import java.util.EnumSet

@Composable
fun RecommendedTabPane(
    currentPaneType: PaneType,
    onNavigate: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GogRecommendationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadIfNeeded()
        if (PrefManager.usageAnalyticsEnabled) {
            PostHog.capture(
                event = "recommendation_tab_opened",
                properties = mapOf("\$set" to mapOf("recommendation_enabled" to true)),
            )
        }
    }

    val items = remember(state.cards) {
        state.cards.mapIndexed { index, card -> card.toLibraryItem(index) }
    }

    // Batched impression tracking: accumulate which cards actually scrolled into view and
    // emit a single summary event when the tab leaves composition, rather than one event per card.
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val seenIndices = remember { mutableSetOf<Int>() }
    val currentCards by rememberUpdatedState(state.cards)

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { seenIndices.addAll(it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { seenIndices.addAll(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (PrefManager.usageAnalyticsEnabled && seenIndices.isNotEmpty()) {
                val gameIds = seenIndices.sorted().mapNotNull { currentCards.getOrNull(it)?.productId }
                PostHog.capture(
                    event = "recommendation_tab_viewed",
                    properties = mapOf(
                        "impressed_count" to seenIndices.size,
                        "max_rank" to (seenIndices.maxOrNull() ?: -1),
                        "game_ids" to gameIds,
                    ),
                )
            }
        }
    }
    val recState = remember(items, state.compatibilityMap, state.deviceGameStats, state.gpuGameStats) {
        LibraryState(
            appInfoList = items,
            totalAppsInFilter = items.size,
            appInfoSortType = EnumSet.of(AppFilter.GAME),
            compatibilityMap = state.compatibilityMap,
            deviceGameStats = state.deviceGameStats,
            gpuGameStats = state.gpuGameStats,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            items.isEmpty() -> {
                Text(
                    text = stringResource(R.string.gog_rec_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                )
            }

            currentPaneType == PaneType.CAROUSEL -> {
                LibraryCarouselPane(
                    state = recState,
                    listState = listState,
                    onPageChange = {},
                    onNavigate = { appId -> items.find { it.appId == appId }?.let(onNavigate) },
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                LibraryListPane(
                    state = recState,
                    listState = gridState,
                    currentLayout = currentPaneType,
                    onPageChange = {},
                    onNavigate = { appId -> items.find { it.appId == appId }?.let(onNavigate) },
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun GogRecCard.toLibraryItem(index: Int): LibraryItem = LibraryItem(
    index = index,
    appId = "GOGREC_$productId",
    name = title,
    capsuleImageUrl = capsuleImage,
    headerImageUrl = heroImage,
    heroImageUrl = heroImage,
    gameSource = GameSource.GOG,
    isRecommended = true,
    recommendedGameId = productId.toString(),
    recRating = rating,
    recDiscount = discountLabel,
    recPrice = priceLabel,
    recBasePrice = basePriceLabel,
    recSeedCount = seedCount,
    recSeedIconUrl = seedIconUrl,
    recStoreCard = true,
    recSource = "tab",
)
