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
import app.gamenative.data.FeaturedItem
import app.gamenative.data.RecommendationRepository
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
import app.gamenative.utils.ConversionTracker
import com.posthog.PostHog
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.util.EnumSet
import timber.log.Timber

@Composable
fun RecommendedTabPane(
    currentPaneType: PaneType,
    onNavigate: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GogRecommendationsViewModel = hiltViewModel(),
    firstCarouselItemFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    firstGridItemFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    focusTargetListIndex: Int = 0,
    onFocusedIndexChanged: (Int) -> Unit = {},
    onItemCountChanged: (Int) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val featured by RecommendationRepository.featuredList.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadIfNeeded()
        PrefManager.recommendedTabSeenDay = System.currentTimeMillis() / (24L * 60 * 60 * 1000)
        if (PrefManager.usageAnalyticsEnabled) {
            PostHog.capture(
                event = "recommendation_tab_opened",
                properties = mapOf("\$set" to mapOf("recommendation_enabled" to true)),
            )
        }
    }

    val items = remember(state.cards, featured) {
        val campaigns = featured.mapIndexed { index, item -> item.toLibraryItem(index) }
        campaigns + state.cards.mapIndexed { index, card -> card.toLibraryItem(campaigns.size + index) }
    }

    LaunchedEffect(items.size) {
        onItemCountChanged(items.size)
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
            val seenRanks = seenIndices.map { it - featured.size }.filter { it >= 0 }.sorted()
            if (PrefManager.usageAnalyticsEnabled && seenRanks.isNotEmpty()) {
                val gameIds = seenRanks.mapNotNull { currentCards.getOrNull(it)?.productId }
                PostHog.capture(
                    event = "recommendation_tab_viewed",
                    properties = mapOf(
                        "impressed_count" to seenRanks.size,
                        "max_rank" to (seenRanks.lastOrNull() ?: -1),
                        "game_ids" to gameIds,
                    ),
                )
            }
        }
    }
    // Per-card impressions: a card counts once it has been on screen for a full second.
    val currentItems by rememberUpdatedState(items)
    val currentFeatured by rememberUpdatedState(featured)
    val currentLayout by rememberUpdatedState(currentPaneType)
    val impressions = remember { RecImpressionTracker() }
    LaunchedEffect(gridState, listState) {
        snapshotFlow {
            val grid = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            val list = listState.layoutInfo.visibleItemsInfo.map { it.index }
            (grid + list).toSet()
        }.collect { impressions.update(it, currentItems, currentFeatured, currentLayout) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            impressions.tick(currentItems, currentFeatured, currentLayout)
        }
    }
    DisposableEffect(Unit) {
        onDispose { impressions.tick(currentItems, currentFeatured, currentLayout) }
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
                    firstCarouselItemFocusRequester = firstCarouselItemFocusRequester,
                    focusTargetListIndex = focusTargetListIndex,
                    onFocusedIndexChanged = onFocusedIndexChanged,
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
                    firstGridItemFocusRequester = firstGridItemFocusRequester,
                    focusTargetListIndex = focusTargetListIndex,
                )
            }
        }
    }
}

private fun FeaturedItem.toLibraryItem(index: Int): LibraryItem = LibraryItem(
    index = index,
    appId = "FEATURED_$campaignId",
    name = title,
    heroImageUrl = heroImageUrl,
    headerImageUrl = heroImageUrl,
    capsuleImageUrl = capsuleImageUrl ?: heroImageUrl,
    iconHash = iconUrl ?: capsuleImageUrl ?: heroImageUrl,
    gameSource = GameSource.STEAM,
    isRecommended = true,
    isFeatured = true,
    recommendedGameId = campaignId,
    recSource = "tab",
)

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

/** Cards already reported this process, so re-entering the tab or a list reorder doesn't re-count them. */
private object RecImpressionSession {
    val seen = mutableSetOf<String>()
}

private class RecImpressionTracker {
    private val visibleSince = mutableMapOf<Int, Long>()
    private val impressed = mutableSetOf<Int>()

    fun update(visible: Set<Int>, items: List<LibraryItem>, featured: List<FeaturedItem>, layout: PaneType) {
        val now = SystemClock.elapsedRealtime()
        Timber.tag("RecImpression").d("visible=%d items=%d layout=%s", visible.size, items.size, layout)
        visibleSince.keys.filter { it !in visible }.forEach { index ->
            val since = visibleSince.remove(index) ?: return@forEach
            if (now - since >= MIN_VISIBLE_MS) emit(index, items, featured, layout)
        }
        visible.forEach { visibleSince.putIfAbsent(it, now) }
        tick(items, featured, layout)
    }

    fun tick(items: List<LibraryItem>, featured: List<FeaturedItem>, layout: PaneType) {
        val now = SystemClock.elapsedRealtime()
        visibleSince.forEach { (index, since) ->
            if (index !in impressed && now - since >= MIN_VISIBLE_MS) emit(index, items, featured, layout)
        }
    }

    private fun emit(index: Int, items: List<LibraryItem>, featured: List<FeaturedItem>, layout: PaneType) {
        if (!impressed.add(index)) return
        val item = items.getOrNull(index) ?: return
        val key = (if (item.isFeatured) "featured:" else "gog:") + item.recommendedGameId
        if (!RecImpressionSession.seen.add(key)) return
        Timber.tag("RecImpression").d("emit rank=%d %s", index, item.name)
        if (item.isFeatured) {
            val campaign = featured.getOrNull(index)
            ConversionTracker.track(
                "featured_impression",
                mapOf(
                    "campaign_id" to item.recommendedGameId,
                    "game_name" to item.name,
                    "rank" to index,
                    "source" to item.recSource,
                    "layout" to layout.name,
                    "status" to (campaign?.status ?: ""),
                    "cta_count" to (campaign?.actions?.size ?: 0),
                    "cta_types" to (campaign?.actions?.map { it.type.uppercase() } ?: emptyList()),
                ),
            )
        } else {
            ConversionTracker.track(
                "recommendation_impression",
                mapOf(
                    "game_id" to item.recommendedGameId,
                    "game_name" to item.name,
                    "rank" to index,
                    "source" to item.recSource,
                    "layout" to layout.name,
                    "seed_count" to item.recSeedCount,
                    "discount" to (item.recDiscount ?: ""),
                    "price" to (item.recPrice ?: ""),
                ),
            )
        }
    }

    private companion object {
        const val MIN_VISIBLE_MS = 1000L
    }
}
