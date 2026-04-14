package app.gamenative.ui.screen.library.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face4
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.ui.component.CompatibilityBadge
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.ListItemImage
import app.gamenative.utils.CustomGameScanner
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Grid card for Hero/Capsule layout views.
 */
@Composable
internal fun GridViewCard(
    modifier: Modifier,
    appInfo: LibraryItem,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    scale: Float,
    paneType: PaneType,
    imageRefreshCounter: Long,
    hideText: Boolean,
    imageAlpha: Float,
    onImageLoadFailed: () -> Unit,
    compatibilityStatus: GameCompatibilityStatus?,
    showFocusGlow: Boolean,
    context: Context,
) {
    val aspectRatio = if (paneType == PaneType.GRID_CAPSULE) 2f / 3f else 460f / 215f
    val isCapsule = paneType == PaneType.GRID_CAPSULE
    val topOverlayPadding = if (isCapsule) 8.dp else 4.dp
    val cardContentBottomPadding = if (isCapsule) 12.dp else 8.dp
    val topIconPadding = if (isCapsule) 10.dp else 8.dp
    val bottomGradientHeight = if (isCapsule) 80.dp else 56.dp
    val glowColor = MaterialTheme.colorScheme.primary
    val focusHaloModifier = if (isFocused && showFocusGlow) {
        Modifier.drawWithCache {
            val glowBrush = Brush.radialGradient(
                colors = listOf(
                    glowColor.copy(alpha = 0.3f),
                    Color.Transparent,
                ),
                radius = size.maxDimension * 0.7f,
            )
            val glowRadius = size.maxDimension * 0.6f
            onDrawBehind {
                drawCircle(
                    brush = glowBrush,
                    radius = glowRadius,
                    center = center,
                )
            }
        }
    } else {
        Modifier
    }
    val focusBorderBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
        ),
    )

    Box(
        modifier = modifier
            .padding(vertical = 4.dp)
            .scale(scale)
            .then(focusHaloModifier),
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isItemFocused by interactionSource.collectIsFocusedAsState()

        LaunchedEffect(isItemFocused) {
            onFocusChanged(isItemFocused)
            if (isItemFocused) onFocus()
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clickable(
                    onClick = onClick,
                    interactionSource = interactionSource,
                    indication = null,
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent,
            ),
            border = if (isFocused) {
                BorderStroke(2.dp, focusBorderBrush)
            } else {
                null
            },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Game image (primary + optional fallback for Steam header/hero)
                val imageUrls by produceState(
                    initialValue = GridImageUrls("", ""),
                    key1 = appInfo.appId,
                    key2 = paneType,
                    key3 = imageRefreshCounter,
                ) {
                    value = withContext(Dispatchers.IO) {
                        getGridImageUrl(context, appInfo, paneType)
                    }
                }

                var currentImageUrl by remember(
                    imageUrls.primary,
                    imageUrls.fallback,
                    appInfo.appId,
                    imageRefreshCounter,
                ) {
                    mutableStateOf(imageUrls.primary)
                }

                if (isCapsule && currentImageUrl.isNotEmpty()) {
                    CapsuleFallbackBackdrop(
                        imageUrl = currentImageUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                val gridHeroZoom = if (!isCapsule && appInfo.gridHeroImageScale != 1f) {
                    Modifier.graphicsLayer {
                        scaleX = appInfo.gridHeroImageScale
                        scaleY = appInfo.gridHeroImageScale
                        transformOrigin = TransformOrigin.Center
                    }
                } else {
                    Modifier
                }

                ListItemImage(
                    modifier = Modifier.fillMaxSize(),
                    imageModifier = Modifier
                        .fillMaxSize()
                        .alpha(imageAlpha)
                        .then(gridHeroZoom),
                    contentScale = getGridContentScale(paneType),
                    image = { currentImageUrl },
                    onFailure = {
                        if (imageUrls.fallback.isNotEmpty() && currentImageUrl == imageUrls.primary) {
                            currentImageUrl = imageUrls.fallback
                        } else {
                            onImageLoadFailed()
                        }
                    },
                )

                // Fallback text when image fails to load (drawn before overlays so badges/icons stay visible)
                if (!hideText) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = appInfo.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                // Gradient overlay at bottom for title
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(bottomGradientHeight)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.85f),
                                ),
                            ),
                        ),
                )

                // Title and status icons at bottom
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = cardContentBottomPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = appInfo.name,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            shadow = Shadow(
                                color = Color.Black,
                                offset = Offset(1f, 1f),
                                blurRadius = 2f,
                            ),
                        ),
                        color = Color.White,
                        maxLines = if (paneType == PaneType.GRID_CAPSULE) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    GridStatusIcons(appInfo = appInfo)
                }

                // Compatibility badge (top left, including UNKNOWN)
                compatibilityStatus?.let { status ->
                    CompatibilityBadge(
                        status = status,
                        showLabel = true,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = topOverlayPadding, start = topOverlayPadding),
                    )
                }

                GameSourceIcon(
                    gameSource = appInfo.gameSource,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = topIconPadding, end = topIconPadding),
                    iconSize = if (isCapsule) 14 else 12,
                )
            }
        }
    }
}

@Composable
private fun CapsuleFallbackBackdrop(
    imageUrl: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        CoilImage(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.08f
                    scaleY = 1.08f
                }
                .blur(14.dp),
            imageModel = { imageUrl },
            imageOptions = ImageOptions(
                contentScale = ContentScale.Crop,
                contentDescription = null,
            ),
            loading = {},
            failure = {},
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.28f),
                            0.45f to Color.Black.copy(alpha = 0.12f),
                            1.0f to Color.Black.copy(alpha = 0.34f),
                        ),
                    ),
                ),
        )
    }
}

/**
 * Status icons for grid view (installed, family share).
 */
@Composable
private fun GridStatusIcons(appInfo: LibraryItem) {
    val isInstalled = appInfo.isInstalled

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isInstalled) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.library_installed),
                    tint = PluviaTheme.colors.statusInstalled,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        if (appInfo.isShared) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Face4,
                    contentDescription = stringResource(R.string.library_family_shared),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * Primary and optional fallback image URL for grid view (e.g. Steam header -> hero).
 */
internal data class GridImageUrls(val primary: String, val fallback: String = "")

private fun getGridContentScale(paneType: PaneType): ContentScale {
    return if (paneType == PaneType.GRID_HERO) {
        ContentScale.Crop
    } else {
        ContentScale.Fit
    }
}

/**
 * Gets the appropriate image URL(s) for a game in grid view.
 * Matches master: source-specific URLs, Steam uses headerImageUrl with heroImageUrl fallback.
 */
internal fun getGridImageUrl(
    context: Context,
    appInfo: LibraryItem,
    paneType: PaneType,
): GridImageUrls {
    fun findSteamGridDBImage(imageType: String): String? {
        if (appInfo.gameSource == GameSource.CUSTOM_GAME) {
            val gameFolderPath = CustomGameScanner.getFolderPathFromAppId(appInfo.appId)
            gameFolderPath?.let { path ->
                val folder = File(path)
                val imageFile = folder.listFiles()?.firstOrNull { file ->
                    file.name.startsWith("steamgriddb_$imageType") &&
                        (
                            file.name.endsWith(".png", ignoreCase = true) ||
                                file.name.endsWith(".jpg", ignoreCase = true) ||
                                file.name.endsWith(".webp", ignoreCase = true)
                            )
                }
                return imageFile?.let { android.net.Uri.fromFile(it).toString() }
            }
        }
        return null
    }

    return when (appInfo.gameSource) {
        GameSource.CUSTOM_GAME -> {
            val primary = when (paneType) {
                PaneType.GRID_CAPSULE ->
                    findSteamGridDBImage("grid_capsule") ?: appInfo.capsuleImageUrl
                PaneType.GRID_HERO ->
                    findSteamGridDBImage("grid_hero") ?: appInfo.headerImageUrl
                else -> {
                    val gameFolderPath = CustomGameScanner.getFolderPathFromAppId(appInfo.appId)
                    val heroUrl = gameFolderPath?.let { path ->
                        val folder = File(path)
                        val heroFile = folder.listFiles()?.firstOrNull { file ->
                            file.name.startsWith("steamgriddb_hero") &&
                                !file.name.contains("grid") &&
                                (
                                    file.name.endsWith(".png", ignoreCase = true) ||
                                        file.name.endsWith(".jpg", ignoreCase = true) ||
                                        file.name.endsWith(".webp", ignoreCase = true)
                                    )
                        }
                        heroFile?.let { android.net.Uri.fromFile(it).toString() }
                    }
                    heroUrl ?: appInfo.headerImageUrl
                }
            }
            GridImageUrls(primary = primary)
        }

        GameSource.GOG, GameSource.EPIC, GameSource.AMAZON -> {
            val primary = when (paneType) {
                PaneType.GRID_CAPSULE -> appInfo.capsuleImageUrl.ifEmpty { appInfo.iconHash }
                else -> appInfo.headerImageUrl.ifEmpty {
                    appInfo.heroImageUrl.ifEmpty { appInfo.iconHash }
                }
            }
            val fallback = when {
                paneType == PaneType.GRID_CAPSULE ->
                    appInfo.iconHash.takeIf { it.isNotEmpty() && it != primary } ?: ""
                appInfo.heroImageUrl.isNotEmpty() && appInfo.heroImageUrl != primary ->
                    appInfo.heroImageUrl
                appInfo.iconHash.isNotEmpty() && appInfo.iconHash != primary ->
                    appInfo.iconHash
                else -> ""
            }
            GridImageUrls(primary = primary, fallback = fallback)
        }

        GameSource.STEAM -> when (paneType) {
            PaneType.GRID_CAPSULE ->
                GridImageUrls(primary = appInfo.capsuleImageUrl)
            else ->
                GridImageUrls(
                    primary = appInfo.headerImageUrl,
                    fallback = appInfo.heroImageUrl,
                )
        }
    }
}
