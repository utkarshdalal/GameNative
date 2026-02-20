package app.gamenative.ui.util

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@Composable
internal fun ListItemImage(
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier.clip(CircleShape),
    contentDescription: String? = null,
    size: Dp = 40.dp,
    image: () -> Any?,
    onFailure: () -> Unit = {},
) {
    CoilImage(
        modifier = modifier
            .size(size)
            .then(imageModifier),
        imageModel = image,
        imageOptions = ImageOptions(
            contentScale = ContentScale.Fit,
            contentDescription = contentDescription,
        ),
        loading = {
            CircularProgressIndicator()
        },
        failure = {
            onFailure()
            Icon(Icons.Filled.QuestionMark, null)
        },
        previewPlaceholder = painterResource(R.drawable.ic_logo_color),
    )
}

@Composable
internal fun SteamIconImage(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 40.dp,
    image: () -> Any?,
) {
    CoilImage(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp)),
        imageModel = image,
        imageOptions = ImageOptions(
            contentScale = ContentScale.Crop,
            contentDescription = contentDescription,
        ),
        loading = {
            CircularProgressIndicator()
        },
        failure = {
            Icon(Icons.Default.AccountCircle, null)
        },
        previewPlaceholder = painterResource(R.drawable.ic_logo_color),
    )
}

@Composable
fun EmoticonImage(
    size: Dp = 54.dp,
    image: () -> Any?,
) {
    CoilImage(
        modifier = Modifier.size(size),
        imageModel = image,
        loading = {
            CircularProgressIndicator()
        },
        failure = {
            Icon(Icons.Filled.QuestionMark, null)
        },
        previewPlaceholder = painterResource(R.drawable.ic_logo_color),
    )
}

@Composable
fun StickerImage(
    size: Dp = 150.dp,
    image: () -> Any?,
) {
    EmoticonImage(size, image)
}

@Preview
@Composable
private fun Preview_EmoticonImage() {
    PluviaTheme {
        EmoticonImage { "https://steamcommunity-a.akamaihd.net/economy/emoticonlarge/roar" }
    }
}

@Preview
@Composable
private fun Preview_StickerImage() {
    PluviaTheme {
        StickerImage { "https://steamcommunity-a.akamaihd.net/economy/sticker/Delivery%20Cat%20in%20a%20Blanket" }
    }
}
