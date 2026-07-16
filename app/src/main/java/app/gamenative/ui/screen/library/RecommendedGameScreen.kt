package app.gamenative.ui.screen.library

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import app.gamenative.R
import app.gamenative.data.RecommendedGame
import app.gamenative.ui.screen.library.components.VideoHero
import app.gamenative.PrefManager
import com.posthog.PostHog
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecommendedGameScreen(
    game: RecommendedGame,
    recRank: Int = -1,
    recSource: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val media = remember(game) {
        val list = mutableListOf<Pair<Boolean, String>>()
        game.videos.ifEmpty { listOfNotNull(game.videoUrl) }.forEach { list += true to it }
        game.screenshots.take(10).forEach { list += false to it }
        list
    }
    val pagerState = rememberPagerState(pageCount = { media.size.coerceAtLeast(1) })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState),
    ) {
        // Hero section
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clipToBounds(),
        ) {
            if (media.isEmpty()) {
                VideoHero(
                    videoUrl = null,
                    fallbackImageUrl = game.heroImageUrl,
                    contentDescription = game.name,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val item = media[page]
                    if (item.first) {
                        VideoHero(
                            videoUrl = item.second,
                            fallbackImageUrl = game.heroImageUrl,
                            contentDescription = game.name,
                            active = page == pagerState.currentPage,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CoilImage(
                            imageModel = { item.second },
                            imageOptions = ImageOptions(
                                contentDescription = game.name,
                                contentScale = ContentScale.Crop,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // Gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.3f),
                                Color.Black.copy(alpha = 0.7f),
                            ),
                        ),
                    ),
            )

            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                )
            }

            // Title and developer
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                if (game.isFeatured) {
                    Row(
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FeaturedAmber)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(R.string.featured_badge),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Text(
                    text = game.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val releaseDate = game.releaseDate?.let { formatReleaseDate(it) }
                val subtitleText = listOfNotNull(
                    game.developer.ifBlank { null },
                    releaseDate,
                ).joinToString(" • ")
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }

            if (media.size > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1}/${media.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }

        // Content section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            if (game.becauseGames.isNotEmpty()) {
                Text(
                    text = "Because you played",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Text(
                    text = game.becauseGames.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            } else {
                game.becausePlayed?.let { because ->
                    Text(
                        text = because,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
            }

            // Review score
            if (game.reviewScore != null) {
                val scoreColor = when {
                    game.reviewScore >= 70 -> Color(0xFF4CAF50)
                    game.reviewScore >= 40 -> Color(0xFFB9A074)
                    else -> MaterialTheme.colorScheme.error
                }
                val summaryResId = when {
                    game.reviewScore >= 95 -> R.string.review_overwhelmingly_positive
                    game.reviewScore >= 80 -> R.string.review_very_positive
                    game.reviewScore >= 70 -> R.string.review_mostly_positive
                    game.reviewScore >= 40 -> R.string.review_mixed
                    game.reviewScore >= 20 -> R.string.review_mostly_negative
                    else -> R.string.review_overwhelmingly_negative
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                scoreColor.copy(alpha = 0.15f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${game.reviewScore}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(summaryResId),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (game.reviewCount != null) {
                            Text(
                                text = stringResource(
                                    R.string.recommended_review_count,
                                    String.format("%,d", game.reviewCount),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (game.isFeatured) {
                // Status callout (e.g. "Coming Soon")
                featuredStatusText(game.featuredStatus)?.let { (titleRes, bodyRes) ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = FeaturedAmber.copy(alpha = 0.12f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Notifications,
                                contentDescription = null,
                                tint = FeaturedAmber,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    text = stringResource(titleRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(bodyRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Featured actions (wishlist / pre-order / etc.)
                game.featuredCtas.forEach { action ->
                    val onClick = {
                        if (PrefManager.usageAnalyticsEnabled) {
                            PostHog.capture(
                                event = "featured_action_clicked",
                                properties = mapOf(
                                    "campaign_id" to game.id,
                                    "action_label" to action.label,
                                    "url" to action.url,
                                    "source" to recSource,
                                ),
                            )
                        }
                        context.startActivity(Intent(Intent.ACTION_VIEW, action.url.toUri()))
                    }
                    if (action.primary) {
                        Button(
                            onClick = onClick,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(text = action.label, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onClick,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(text = action.label, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            } else {
                // Buy button
                Button(
                    onClick = {
                        if (PrefManager.usageAnalyticsEnabled) {
                            PostHog.capture(
                                event = "recommendation_link_clicked",
                                properties = mapOf(
                                    "game_name" to game.name,
                                    "game_id" to game.id,
                                    "affiliate_url" to game.affiliateUrl,
                                    "rank" to recRank,
                                    "source" to recSource,
                                    "because_played" to (game.becausePlayed ?: ""),
                                ),
                            )
                        }
                        val browserIntent = Intent(Intent.ACTION_VIEW, game.affiliateUrl.toUri())
                        context.startActivity(browserIntent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.recommended_buy_button),
                        modifier = Modifier.padding(start = 8.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Support message
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.recommended_support_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Description
            if (game.description.isNotBlank()) {
                Text(
                    text = stringResource(R.string.recommended_about_heading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = game.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            }

            // Tags
            if (game.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    game.tags.forEach { tag ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private val FeaturedAmber = Color(0xFFFFC107)

/** Maps a featured status to a localized (title, body) callout, or null for none. */
private fun featuredStatusText(status: String?): Pair<Int, Int>? = when (status?.uppercase()) {
    "COMING_SOON" -> R.string.featured_status_coming_soon_title to R.string.featured_status_coming_soon_body
    "EARLY_ACCESS" -> R.string.featured_status_early_access_title to R.string.featured_status_early_access_body
    "PREORDER_OPEN" -> R.string.featured_status_preorder_title to R.string.featured_status_preorder_body
    "OUT_NOW" -> R.string.featured_status_out_now_title to R.string.featured_status_out_now_body
    else -> null
}

private fun formatReleaseDate(raw: String): String? = try {
    val input = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val parsed = input.parse(raw.take(10))
    if (parsed != null) {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(parsed)
    } else {
        raw
    }
} catch (e: Exception) {
    raw
}
