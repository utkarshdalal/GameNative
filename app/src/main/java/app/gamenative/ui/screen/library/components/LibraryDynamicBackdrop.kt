package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.ui.enums.PaneType
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DYNAMIC_BACKDROP_BLUR_RADIUS = 12.dp

@Composable
internal fun LibraryDynamicBackdrop(
    appInfo: LibraryItem?,
    imageRefreshCounter: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (appInfo != null) {
            val imageUrls by produceState(
                initialValue = GridImageUrls("", ""),
                key1 = appInfo.appId,
                key2 = imageRefreshCounter,
            ) {
                value = withContext(Dispatchers.IO) {
                    getGridImageUrl(context, appInfo, PaneType.GRID_HERO)
                }
            }

            var currentImageUrl by remember(
                imageUrls.primary,
                imageUrls.fallback,
                appInfo.appId,
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
                        .blur(DYNAMIC_BACKDROP_BLUR_RADIUS),
                    imageModel = { currentImageUrl },
                    imageOptions = ImageOptions(
                        contentScale = ContentScale.Crop,
                        contentDescription = null,
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f)),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.74f),
                            0.16f to Color.Black.copy(alpha = 0.52f),
                            0.38f to Color.Black.copy(alpha = 0.24f),
                            0.62f to Color.Black.copy(alpha = 0.34f),
                            1.0f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.34f),
                            0.14f to Color.Black.copy(alpha = 0.16f),
                            0.5f to Color.Transparent,
                            0.86f to Color.Black.copy(alpha = 0.16f),
                            1.0f to Color.Black.copy(alpha = 0.34f),
                        ),
                    ),
                ),
        )
    }
}
