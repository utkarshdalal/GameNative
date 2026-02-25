package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.data.GameDisplayInfo
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen install confirmation dialog for Amazon games.
 *
 * Mirrors the layout of [GameManagerDialog] used for Steam games so both platforms
 * present a consistent pre-install experience.
 */
@Composable
fun AmazonInstallDialog(
    visible: Boolean,
    displayInfo: GameDisplayInfo,
    downloadSize: String,
    installSize: String,
    availableSpace: String,
    installEnabled: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sizeDisplay = stringResource(
        R.string.steam_install_space,
        downloadSize,
        installSize,
        availableSpace,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
        ) {
            // ── Hero section ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp),
            ) {
                if (displayInfo.heroImageUrl != null) {
                    CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { displayInfo.heroImageUrl },
                        imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                        loading = { LoadingScreen() },
                        failure = {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.primary,
                            ) {}
                        },
                        previewPlaceholder = painterResource(R.drawable.testhero),
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                    ) {}
                }

                // Gradient overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.8f),
                                ),
                            ),
                        ),
                )

                // Back / dismiss button
                Box(
                    modifier = Modifier
                        .padding(20.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                        ),
                ) {
                    BackButton(onClick = onDismiss)
                }

                // Game title + developer/year
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.dp),
                ) {
                    Text(
                        text = displayInfo.name,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.5f),
                                offset = Offset(0f, 2f),
                                blurRadius = 10f,
                            ),
                        ),
                        color = Color.White,
                    )
                    val yearText = remember(displayInfo.releaseDate) {
                        if (displayInfo.releaseDate > 0)
                            SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(displayInfo.releaseDate * 1000))
                        else ""
                    }
                    Text(
                        text = "${displayInfo.developer} • $yearText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }

            // ── Game row (mirrors Steam DLC list — single item, always selected) ──
            ListItem(
                headlineContent = {
                    Column {
                        Text(text = displayInfo.name)
                        Text(
                            text = "$downloadSize download • $installSize install",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                },
                trailingContent = {
                    Checkbox(
                        checked = true,
                        enabled = false,
                        onCheckedChange = null,
                    )
                },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )

            // ── Bottom: size summary + action buttons ─────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 8.dp, bottom = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(0.5f),
                    text = sizeDisplay,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        enabled = installEnabled,
                        onClick = onInstall,
                    ) {
                        Text(stringResource(R.string.install))
                    }
                }
            }
        }
    }
}
