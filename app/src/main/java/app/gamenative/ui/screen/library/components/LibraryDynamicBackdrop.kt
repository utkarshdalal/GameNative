package app.gamenative.ui.screen.library.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DYNAMIC_BACKDROP_BLUR_RADIUS = 7.dp

@Composable
internal fun LibraryDynamicBackdrop(
    appInfo: LibraryItem?,
    imageRefreshCounter: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrimColor = MaterialTheme.colorScheme.scrim
    val desaturate = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.25f) })
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        PluviaTheme.colors.surfacePanel,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    ) {
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
                        getGridImageUrl(context, targetInfo, PaneType.GRID_HERO)
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
                            .alpha(0.38f)
                            .blur(DYNAMIC_BACKDROP_BLUR_RADIUS),
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
    }
}
