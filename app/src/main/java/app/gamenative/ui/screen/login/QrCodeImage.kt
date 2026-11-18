package app.gamenative.ui.screen.login

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.gamenative.ui.theme.PluviaTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Displays a QR code for [content] at the desired [size].
 *
 * The QR code will render in the background before displaying. If this takes any amount of time, a circular progress
 * indicator will display until the QR code is rendered.
 * Source: https://gist.github.com/ryanholden8/6e921a4dc2a40bd40b3b5a15aaff4705
 */
@Composable
fun QrCodeImage(
    modifier: Modifier = Modifier,
    content: String,
    size: Dp,
) {
    // QR Code Image
    val qrBitmap = rememberQrBitmap(content = content, size = size)

    Crossfade(
        modifier = Modifier,
        targetState = qrBitmap,
    ) { bitmap ->
        Box(
            modifier = modifier
                .size(size)
                .background(Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                val bitmapPainter = remember(bitmap) { BitmapPainter(bitmap.asImageBitmap()) }
                Image(
                    painter = bitmapPainter,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.size(size),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(92.dp), color = PluviaTheme.progressColor)
            }
        }
    }
}

/** Taken from: https://gist.github.com/dev-niiaddy/8f936062291e3d328c7d10bb644273d0 */
@Composable
private fun rememberQrBitmap(content: String, size: Dp): Bitmap? {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }

    var bitmap by remember(content) {
        mutableStateOf<Bitmap?>(null)
    }

    val ioScope = rememberCoroutineScope { Dispatchers.IO }
    val bgColor = MaterialTheme.colorScheme.background.toArgb()
    val onBgColor = MaterialTheme.colorScheme.onBackground.toArgb()

    LaunchedEffect(bitmap) {
        if (bitmap != null) return@LaunchedEffect

        ioScope.launch {
            val qrCodeWriter = QRCodeWriter()

            val encodeHints = mutableMapOf<EncodeHintType, Any?>().apply {
                this[EncodeHintType.MARGIN] = 0
            }

            val bitmapMatrix = try {
                qrCodeWriter.encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    encodeHints,
                )
            } catch (ex: WriterException) {
                null
            }

            val matrixWidth = bitmapMatrix?.width ?: sizePx
            val matrixHeight = bitmapMatrix?.height ?: sizePx

            val newBitmap = createBitmap(bitmapMatrix?.width ?: sizePx, bitmapMatrix?.height ?: sizePx)

            val pixels = IntArray(matrixWidth * matrixHeight)

            for (x in 0 until matrixWidth) {
                for (y in 0 until matrixHeight) {
                    val shouldColorPixel = bitmapMatrix?.get(x, y) ?: false
                    val pixelColor = if (shouldColorPixel) onBgColor else bgColor

                    pixels[y * matrixWidth + x] = pixelColor
                }
            }

            newBitmap.setPixels(pixels, 0, matrixWidth, 0, 0, matrixWidth, matrixHeight)

            bitmap = newBitmap
        }
    }

    return bitmap
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_QrCodeImage() {
    PluviaTheme {
        Surface {
            QrCodeImage(Modifier, "Hello World", 256.dp)
        }
    }
}
