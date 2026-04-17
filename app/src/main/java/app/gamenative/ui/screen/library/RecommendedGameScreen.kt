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
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.gamenative.R
import app.gamenative.data.RecommendedGame
import app.gamenative.ui.screen.library.components.VideoHero
import app.gamenative.PrefManager
import com.posthog.PostHog

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecommendedGameScreen(
    game: RecommendedGame,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

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
            VideoHero(
                videoUrl = game.videoUrl,
                fallbackImageUrl = game.heroImageUrl,
                contentDescription = game.name,
                modifier = Modifier.fillMaxSize(),
            )

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
                Text(
                    text = game.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitleText = if (game.releaseDate != null) {
                    "${game.developer} • ${game.releaseDate}"
                } else {
                    game.developer
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }

        // Content section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
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

            Spacer(modifier = Modifier.height(20.dp))

            // Description
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
