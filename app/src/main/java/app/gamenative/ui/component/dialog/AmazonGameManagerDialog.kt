package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.service.amazon.AmazonConstants
import app.gamenative.service.amazon.AmazonService
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@Composable
fun AmazonGameManagerDialog(
    visible: Boolean,
    productId: String,
    onGetDisplayInfo: @Composable (Context) -> GameDisplayInfo,
    onInstall: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var loading by remember { mutableStateOf(true) }
    var downloadBytes by remember { mutableLongStateOf(0L) }
    var installBytes by remember { mutableLongStateOf(0L) }
    var availableBytes by remember { mutableLongStateOf(0L) }

    val displayInfo = onGetDisplayInfo(context)

    LaunchedEffect(visible, productId) {
        launch { scrollState.scrollTo(0) }

        loading = true
        val sizes = withContext(Dispatchers.IO) {
            try {
                val game = AmazonService.getAmazonGameOf(productId)
                val download = game?.downloadSize?.takeIf { it > 0L }
                    ?: AmazonService.fetchDownloadSize(productId)
                    ?: 0L
                download to (game?.installSize ?: 0L)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to load sizes for Amazon product $productId")
                0L to 0L
            }
        }
        downloadBytes = sizes.first
        installBytes = sizes.second
        availableBytes = try {
            StorageUtils.getAvailableSpace(AmazonConstants.defaultAmazonGamesPath(context))
        } catch (e: Exception) {
            0L
        }
        loading = false
    }

    if (!visible) return

    GameContentDialog(
        displayInfo = displayInfo,
        scrollState = scrollState,
        loading = loading,
        rows = listOf(
            GameContentRow(
                key = productId,
                title = displayInfo.name,
                downloadSize = downloadBytes,
                installSize = installBytes,
                checked = true,
                enabled = false,
            )
        ),
        onCheckedChange = { _, _ -> },
        formatSize = { StorageUtils.formatBinarySize(it) },
        footerText = stringResource(
            R.string.steam_install_space,
            StorageUtils.formatBinarySize(downloadBytes),
            StorageUtils.formatBinarySize(installBytes),
            StorageUtils.formatBinarySize(availableBytes),
        ),
        installEnabled = availableBytes >= installBytes,
        onInstall = onInstall,
        onDismissRequest = onDismissRequest,
    )
}
