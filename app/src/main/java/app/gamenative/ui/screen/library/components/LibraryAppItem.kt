package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import app.gamenative.R
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import app.gamenative.PrefManager
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.icons.Amazon
import app.gamenative.ui.icons.Steam
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.theme.PluviaTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face4
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/**
 * Library app item that displays a game in either list or grid view.
 *
 * This is the main entry point for displaying library items. It delegates to:
 * - [ListViewCard] for list view (PaneType.LIST)
 * - [GridViewCard] for grid views (PaneType.GRID_HERO, PaneType.GRID_CAPSULE)
 */
@Composable
internal fun AppItem(
    modifier: Modifier = Modifier,
    appInfo: LibraryItem,
    onClick: () -> Unit,
    paneType: PaneType = PaneType.LIST,
    onFocus: () -> Unit = {},
    isRefreshing: Boolean = false,
    imageRefreshCounter: Long = 0L,
    compatibilityStatus: GameCompatibilityStatus? = null,
) {
    val context = LocalContext.current
    var hideText by remember { mutableStateOf(true) }
    var alpha by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(paneType) {
        hideText = true
        alpha = 1f
    }

    LaunchedEffect(imageRefreshCounter) {
        if (paneType != PaneType.LIST) {
            hideText = true
            alpha = 1f
        }
    }

    var isFocused by remember { mutableStateOf(false) }

    // More subtle scale for list view, slightly larger for grid views
    val targetScale = when {
        !isFocused -> 1f
        paneType == PaneType.LIST -> 1.015f
        else -> 1.03f
    }

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "focusScale",
    )

    when (paneType) {
        PaneType.LIST -> ListViewCard(
            modifier = modifier,
            appInfo = appInfo,
            onClick = onClick,
            onFocus = onFocus,
            isFocused = isFocused,
            onFocusChanged = { isFocused = it },
            isRefreshing = isRefreshing,
            compatibilityStatus = compatibilityStatus,
            context = context,
        )

        else -> GridViewCard(
            modifier = modifier,
            appInfo = appInfo,
            onClick = onClick,
            onFocus = onFocus,
            isFocused = isFocused,
            onFocusChanged = { isFocused = it },
            scale = scale,
            paneType = paneType,
            imageRefreshCounter = imageRefreshCounter,
            hideText = hideText,
            imageAlpha = alpha,
            onImageLoadFailed = {
                hideText = false
                alpha = 0.1f
            },
            compatibilityStatus = compatibilityStatus,
            context = context,
        )
    }
}

@Composable
fun GameSourceIcon(gameSource: GameSource, modifier: Modifier = Modifier, iconSize: Int = 12) {
    when (gameSource) {
        GameSource.STEAM -> Icon(imageVector = Icons.Filled.Steam, contentDescription = "Steam", modifier = modifier.size(iconSize.dp).alpha(0.7f))
        GameSource.CUSTOM_GAME -> Icon(imageVector = Icons.Filled.Folder, contentDescription = "Custom Game", modifier = modifier.size(iconSize.dp).alpha(0.7f))
        GameSource.GOG -> Icon(painter = painterResource(R.drawable.ic_gog), contentDescription = "Gog", modifier = modifier.size(iconSize.dp).alpha(0.7f))
        GameSource.EPIC -> Icon(painter = painterResource(R.drawable.ic_epic), contentDescription = "Epic", modifier = modifier.size(iconSize.dp).alpha(0.7f))
        GameSource.AMAZON -> Icon(imageVector = Icons.Filled.Amazon, contentDescription = "Amazon", modifier = modifier.size(iconSize.dp).alpha(0.7f))
    }
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_AppItem() {
    PrefManager.init(LocalContext.current)
    PluviaTheme {
        Surface {
            LazyColumn(
                modifier = Modifier.padding(16.dp),
            ) {
                items(
                    items = List(5) { idx ->
                        val item = fakeAppInfo(idx)
                        LibraryItem(
                            index = idx,
                            appId = "${GameSource.STEAM.name}_${item.id}",
                            name = item.name,
                            iconHash = item.iconHash,
                            isShared = idx % 2 == 0,
                            gameSource = GameSource.STEAM,
                        )
                    },
                    itemContent = {
                        val status = when (it.index % 4) {
                            0 -> GameCompatibilityStatus.COMPATIBLE
                            1 -> GameCompatibilityStatus.GPU_COMPATIBLE
                            2 -> GameCompatibilityStatus.NOT_COMPATIBLE
                            else -> GameCompatibilityStatus.UNKNOWN
                        }
                        AppItem(
                            appInfo = it,
                            onClick = {},
                            compatibilityStatus = status,
                        )
                    },
                )
            }
        }
    }
}

@Preview(device = "spec:width=1920px,height=1080px,dpi=440")
@Composable
private fun Preview_AppItemGrid() {
    PrefManager.init(LocalContext.current)
    PluviaTheme {
        Surface {
            Column {
                val appInfoList = List(4) { idx ->
                    val item = fakeAppInfo(idx)
                    LibraryItem(
                        index = idx,
                        appId = "${GameSource.STEAM.name}_${item.id}",
                        name = item.name,
                        iconHash = item.iconHash,
                        isShared = idx % 2 == 0,
                        gameSource = GameSource.CUSTOM_GAME,
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(20.dp),
                ) {
                    items(items = appInfoList, key = { it.index }) { item ->
                        val status = when (item.index % 4) {
                            0 -> GameCompatibilityStatus.COMPATIBLE
                            1 -> GameCompatibilityStatus.GPU_COMPATIBLE
                            2 -> GameCompatibilityStatus.NOT_COMPATIBLE
                            else -> GameCompatibilityStatus.UNKNOWN
                        }
                        AppItem(
                            appInfo = item,
                            onClick = { },
                            paneType = PaneType.GRID_HERO,
                            compatibilityStatus = status,
                        )
                    }
                }
            }
        }
    }
}
