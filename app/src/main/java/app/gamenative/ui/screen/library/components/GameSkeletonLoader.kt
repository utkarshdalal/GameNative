package app.gamenative.ui.screen.library.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.gamenative.ui.enums.PaneType

/**
 * Skeleton loader for game items that matches the actual game item appearance
 */
@Composable
fun GameSkeletonLoader(
    modifier: Modifier = Modifier,
    paneType: PaneType = PaneType.LIST,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val skeletonColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
    ) {
        val outerPadding = if (paneType == PaneType.LIST) {
            16.dp
        } else {
            0.dp
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(outerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (paneType) {
                PaneType.LIST -> {
                    // List view: icon + text + button
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(skeletonColor)
                    )
                    
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Title
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(skeletonColor)
                        )
                        // Status line
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(skeletonColor)
                        )
                    }
                    
                    // Button skeleton
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(skeletonColor)
                    )
                }
                PaneType.GRID_CAPSULE -> {
                    // Capsule view: vertical image (2:3 aspect ratio)
                    Box(
                        modifier = Modifier
                            .aspectRatio(2f / 3f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp))
                            .background(skeletonColor)
                    )
                }
                PaneType.GRID_HERO -> {
                    // Hero view: horizontal image (460:215 aspect ratio)
                    Box(
                        modifier = Modifier
                            .aspectRatio(460f / 215f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp))
                            .background(skeletonColor)
                    )
                }
                else -> {
                    // Default to list view
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(skeletonColor)
                    )
                }
            }
        }
    }
}

