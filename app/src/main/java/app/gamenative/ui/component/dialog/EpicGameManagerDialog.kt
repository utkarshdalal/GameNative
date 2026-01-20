package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.EpicGame
import app.gamenative.service.epic.EpicConstants
import app.gamenative.service.epic.EpicService
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.dialog.InstallSizeInfo
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.utils.StorageUtils
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import com.winlator.core.StringUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpicGameManagerDialog(
    visible: Boolean,
    onGetDisplayInfo: @Composable (Context) -> GameDisplayInfo,
    onInstall: (List<Int>) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val allDownloadableGames = remember { mutableStateListOf<EpicGame>() }
    val selectedGameIds = remember { mutableStateMapOf<Int, Boolean>() }

    val displayInfo = onGetDisplayInfo(context)
    val gameId = displayInfo.gameId

    LaunchedEffect(visible) {
        scrollState.animateScrollTo(0)

        allDownloadableGames.clear()
        selectedGameIds.clear()

        // Get base game
        val baseGame = EpicService.getEpicGameOf(gameId)
        if (baseGame != null) {
            // Fetch manifest to get accurate sizes
            val sizes = EpicService.fetchManifestSizes(context, baseGame.id ?: 0)
            val updatedGame = baseGame.copy(
                downloadSize = sizes.downloadSize,
                installSize = sizes.installSize
            )
            allDownloadableGames.add(updatedGame)
            // Base game is always selected and can't be deselected
            selectedGameIds[updatedGame.id ?: 0] = true
        }

        // Get DLCs and fetch their manifest sizes
        val dlcs = EpicService.getDLCForGame(gameId)
        dlcs.forEach { dlc ->
            // Fetch manifest to get accurate sizes for each DLC
            val sizes = EpicService.fetchManifestSizes(context, dlc.id ?: 0)
            val updatedDlc = dlc.copy(
                downloadSize = sizes.downloadSize,
                installSize = sizes.installSize
            )
            allDownloadableGames.add(updatedDlc)
            selectedGameIds[updatedDlc.id ?: 0] = true
        }
    }

    fun getInstallSizeInfo(): InstallSizeInfo {
        val installPath = EpicConstants.defaultEpicGamesPath(context)
        val availableBytes = try {
            StorageUtils.getAvailableSpace(installPath)
        } catch (e: Exception) {
            0L
        }

        val selectedGames = allDownloadableGames.filter {
            selectedGameIds[it.id ?: 0] == true
        }

        val totalDownloadBytes = selectedGames.sumOf { it.downloadSize }
        val totalInstallBytes = selectedGames.sumOf { it.installSize }

        return InstallSizeInfo(
            downloadSize = StringUtils.formatBytes(totalDownloadBytes),
            installSize = StringUtils.formatBytes(totalInstallBytes),
            availableSpace = StringUtils.formatBytes(availableBytes),
            installBytes = totalInstallBytes,
            availableBytes = availableBytes
        )
    }

    val installSizeInfo by remember(selectedGameIds.toMap()) {
        derivedStateOf { getInstallSizeInfo() }
    }

    fun installSizeDisplay(): String {
        return context.getString(
            R.string.steam_install_space,
            installSizeInfo.downloadSize,
            installSizeInfo.installSize,
            installSizeInfo.availableSpace
        )
    }

    fun installButtonEnabled(): Boolean {
        // Check if there's enough space
        if (installSizeInfo.availableBytes < installSizeInfo.installBytes) {
            return false
        }

        // At least base game should be selected
        return selectedGameIds.filter { it.value }.isNotEmpty()
    }

    when {
        visible -> {
            Dialog(
                onDismissRequest = onDismissRequest,
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                ),
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        // Hero Section with Game Image Background
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                        ) {
                            // Hero background image
                            if (displayInfo.heroImageUrl != null) {
                                CoilImage(
                                    modifier = Modifier.fillMaxSize(),
                                    imageModel = { displayInfo.heroImageUrl },
                                    imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                                    loading = { LoadingScreen() },
                                    failure = {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            // Gradient background as fallback
                                            Surface(
                                                modifier = Modifier.fillMaxSize(),
                                                color = MaterialTheme.colorScheme.primary
                                            ) { }
                                        }
                                    },
                                    previewPlaceholder = painterResource(R.drawable.testhero),
                                )
                            } else {
                                // Fallback gradient background when no hero image
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.primary
                                ) { }
                            }

                            // Gradient overlay
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.8f)
                                            )
                                        )
                                    )
                            )

                            // Back button (top left)
                            Box(
                                modifier = Modifier
                                    .padding(20.dp)
                                    .background(
                                        color = Color.Black.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                            ) {
                                BackButton(onClick = onDismissRequest)
                            }

                            // Game title and subtitle
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(20.dp)
                            ) {
                                Text(
                                    text = displayInfo.name,
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        shadow = Shadow(
                                            color = Color.Black.copy(alpha = 0.5f),
                                            offset = Offset(0f, 2f),
                                            blurRadius = 10f
                                        )
                                    ),
                                    color = Color.White
                                )

                                Text(
                                    text = "${displayInfo.developer} • ${
                                        remember(displayInfo.releaseDate) {
                                            if (displayInfo.releaseDate > 0) {
                                                SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(displayInfo.releaseDate * 1000))
                                            } else {
                                                ""
                                            }
                                        }
                                    }",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            allDownloadableGames.forEach { game ->
                                val gameIdValue = game.id ?: 0
                                val checked = selectedGameIds[gameIdValue] ?: false
                                val isBaseGame = gameIdValue == gameId

                                ListItem(
                                    headlineContent = {
                                        Column {
                                            Text(
                                                text = game.title
                                            )
                                            // Add size display
                                            val downloadSize = StringUtils.formatBytes(game.downloadSize)
                                            val installSize = StringUtils.formatBytes(game.installSize)
                                            Text(
                                                text = "$downloadSize download • $installSize install",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                            // Show if it's DLC
                                            if (game.isDLC) {
                                                Text(
                                                    text = "DLC",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Checkbox(
                                            checked = checked,
                                            enabled = !isBaseGame, // Base game can't be deselected
                                            onCheckedChange = { isChecked ->
                                                if (!isBaseGame) {
                                                    selectedGameIds[gameIdValue] = isChecked
                                                }
                                            }
                                        )
                                    },
                                    modifier = Modifier.clickable(enabled = !isBaseGame) {
                                        if (!isBaseGame) {
                                            selectedGameIds[gameIdValue] = !checked
                                        }
                                    }
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, start = 8.dp, bottom = 8.dp, end = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    modifier = Modifier.weight(0.5f),
                                    text = installSizeDisplay()
                                )

                                Button(
                                    enabled = installButtonEnabled(),
                                    onClick = {
                                        val selectedIds = selectedGameIds
                                            .filter { it.value }
                                            .keys
                                            .toList()
                                        onInstall(selectedIds)
                                    }
                                ) {
                                    Text(stringResource(R.string.install))
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}
