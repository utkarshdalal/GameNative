package app.gamenative.ui.screen.library.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.theme.ThemeHero
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemeHeroImage
import app.gamenative.ui.theme.themeFill
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DYNAMIC_BACKDROP_BLUR_RADIUS = 7.dp

/**
 * The focused game's artwork behind the library. [defaultHero] is the look used where the theme sets none; with
 * [artHeight] the artwork covers only that much of the top and fades out at its bottom. With [fullResolution] it
 * loads the game's largest landscape art rather than its header image.
 */
@Composable
internal fun LibraryDynamicBackdrop(
    appInfo: LibraryItem?,
    imageRefreshCounter: Long,
    modifier: Modifier = Modifier,
    defaultHero: ThemeHero? = null,
    artHeight: Dp? = null,
    fullResolution: Boolean = false,
) {
    val context = LocalContext.current
    val scrimColor = MaterialTheme.colorScheme.scrim
    val themeHero = PluviaTheme.hero
    val hero = if (defaultHero == null) themeHero else themeHero?.let { defaultHero.overlay(it) } ?: defaultHero
    val desaturate = if (hero == null) {
        remember {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.25f) })
        }
    } else {
        val saturation = hero.saturation?.coerceIn(0f, 1f) ?: 0.25f
        remember(saturation) { if (saturation < 1f) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) }) else null }
    }

    // A theme backdrop is drawn behind the home screen; the base gradient would hide it.
    val baseModifier = if (PluviaTheme.hasBackdrop) {
        Modifier
    } else {
        Modifier.background(
            Brush.verticalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.background,
                    PluviaTheme.colors.surfacePanel,
                    MaterialTheme.colorScheme.background,
                ),
            ),
        )
    }
    val layout = PluviaTheme.layout
    val heroImage = layout.heroImage
    val band = layout.heroHeightFraction?.takeIf { it > 0f }?.coerceAtMost(1f)
    val banded = band != null || artHeight != null
    // A theme at artwork opacity 0 keeps its own page background: no artwork, and no darkening for it.
    val hidden = hero?.opacity?.let { it <= 0f } == true
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(baseModifier),
    ) {
        val artwork: @Composable () -> Unit = {
            if (heroImage == null) {
                Crossfade(
                    targetState = appInfo,
                    animationSpec = tween(durationMillis = 500),
                    label = "backdrop_fade",
                ) { targetInfo ->
                    if (targetInfo != null) {
                        val imageUrls by produceState(
                            initialValue = GridImageUrls("", ""),
                            key1 = targetInfo.appId,
                            key2 = imageRefreshCounter,
                        ) {
                            value = withContext(Dispatchers.IO) {
                                if (fullResolution) {
                                    getBackdropImageUrl(context, targetInfo)
                                } else {
                                    getGridImageUrl(context, targetInfo, PaneType.GRID_HERO)
                                }
                            }
                        }

                        var currentImageUrl by remember(
                            imageUrls.primary,
                            imageUrls.fallback,
                            targetInfo.appId,
                            imageRefreshCounter,
                        ) {
                            mutableStateOf(imageUrls.primary.ifEmpty { imageUrls.fallback })
                        }

                        if (currentImageUrl.isNotEmpty()) {
                            CoilImage(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = 1.06f
                                        scaleY = 1.06f
                                    }
                                    .then(if (hero == null) Modifier.alpha(0.38f).blur(DYNAMIC_BACKDROP_BLUR_RADIUS) else hero.artworkLook(fade = !banded)),
                                imageModel = { currentImageUrl },
                                imageOptions = ImageOptions(
                                    contentScale = ContentScale.Crop,
                                    contentDescription = null,
                                    colorFilter = desaturate,
                                ),
                                loading = {},
                                failure = {
                                    if (imageUrls.fallback.isNotEmpty() && currentImageUrl == imageUrls.primary) {
                                        currentImageUrl = imageUrls.fallback
                                    }
                                },
                                previewPlaceholder = painterResource(R.drawable.ic_logo_color),
                            )
                        }
                    }
                }
            } else if (hidden) {
                ThemeHeroImage(path = heroImage, tile = layout.heroImageTile == true, scale = layout.heroImageScale)
            } else {
                ThemeHeroImage(
                    path = heroImage,
                    tile = layout.heroImageTile == true,
                    scale = layout.heroImageScale,
                    colorFilter = desaturate,
                    modifier = if (hero == null) Modifier.alpha(0.38f).blur(DYNAMIC_BACKDROP_BLUR_RADIUS) else hero.artworkLook(fade = !banded),
                )
            }

            if (!hidden) {
                val overlay = hero?.overlay?.coerceIn(0f, ThemeHero.MAX_OVERLAY)
                if (overlay == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to scrimColor.copy(alpha = 0.48f),
                                        0.4f to scrimColor.copy(alpha = 0.48f),
                                        1.0f to scrimColor.copy(alpha = 0.62f),
                                    ),
                                ),
                            ),
                    )
                } else if (overlay > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to scrimColor.copy(alpha = (0.48f * overlay).coerceAtMost(1f)),
                                        0.4f to scrimColor.copy(alpha = (0.48f * overlay).coerceAtMost(1f)),
                                        1.0f to scrimColor.copy(alpha = (0.62f * overlay).coerceAtMost(1f)),
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
        // A theme that hides the artwork and puts its own image on the backdrop shows that image as it is.
        if (!hidden || heroImage != null) {
            when {
                band != null -> Box(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(band).clipToBounds().heroFade(hero?.fadeBottom),
                ) { artwork() }
                artHeight != null -> Box(
                    modifier = Modifier.fillMaxWidth().height(artHeight).clipToBounds().heroFade(hero?.fadeBottom),
                ) { artwork() }
                else -> artwork()
            }
        }
        layout.homeOverlay?.let { Spacer(modifier = Modifier.fillMaxSize().themeFill(it)) }
    }
}

/** The theme's opacity, blur and, with [fade], bottom fade for the artwork; unset values keep the app's own. */
private fun ThemeHero.artworkLook(fade: Boolean): Modifier {
    val blurDp = blurDp ?: DYNAMIC_BACKDROP_BLUR_RADIUS.value
    return Modifier
        .alpha(opacity?.coerceIn(0f, 1f) ?: 0.38f)
        .then(if (blurDp > 0f) Modifier.blur(blurDp.dp) else Modifier)
        .then(if (fade) Modifier.heroFade(fadeBottom) else Modifier)
}

/** Fades the bottom [fraction] of the content out to transparent; unchanged for null or 0. */
internal fun Modifier.heroFade(fraction: Float?): Modifier {
    val f = fraction?.coerceIn(0f, 1f)?.takeIf { it > 0f } ?: return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(0f to Color.Black, (1f - f) to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }
}
