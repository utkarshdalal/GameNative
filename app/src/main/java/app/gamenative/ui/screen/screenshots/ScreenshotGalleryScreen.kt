@file:OptIn(ExperimentalFoundationApi::class)

package app.gamenative.ui.screen.screenshots

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.component.rememberScreenshots
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ScreenshotItem
import app.gamenative.utils.ScreenshotManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// Rows rendered per page.
private const val PAGE_SIZE = 100

// Load the next page when this many rows from the end become visible.
private const val LOAD_MORE_THRESHOLD = 10

@Composable
fun ScreenshotGalleryScreen(
    appId: String,
    onBack: () -> Unit,
    initialViewerIndex: Int = -1,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    val items by rememberScreenshots(appId, refreshKey)

    // Render the first [visibleCount] rows, growing on scroll; resets when the list changes.
    val listState = rememberLazyListState()
    var visibleCount by remember(items) { mutableIntStateOf(PAGE_SIZE.coerceAtMost(items.size)) }
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            visibleCount < items.size && lastVisible >= visibleCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) visibleCount = (visibleCount + PAGE_SIZE).coerceAtMost(items.size)
    }

    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // Open the viewer at the deep-linked shot once the list loads.
    var initialApplied by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        if (!initialApplied && initialViewerIndex >= 0 && items.isNotEmpty()) {
            viewerIndex = initialViewerIndex.coerceIn(0, items.size - 1)
            initialApplied = true
        }
    }
    var pendingDelete by remember { mutableStateOf<ScreenshotItem?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    // Translate raw D-pad keys / HAT axis into focus moves.
    val focusManager = LocalFocusManager.current
    val rootFocus = remember { FocusRequester() }
    // Auto-focus the first row on entry (unless deep-linked into the viewer).
    val firstRowFocus = remember { FocusRequester() }
    // Auto-focus only once per screen.
    var didInitialFocus by remember { mutableStateOf(false) }
    LaunchedEffect(items.isNotEmpty()) {
        if (!didInitialFocus && items.isNotEmpty() && initialViewerIndex < 0) {
            repeat(5) {
                try {
                    if (firstRowFocus.requestFocus()) {
                        didInitialFocus = true
                        return@LaunchedEffect
                    }
                } catch (_: IllegalStateException) {
                }
                delay(32)
            }
        }
    }
    // After the viewer closes, put focus (and scroll) back on the picture it was showing.
    val restoreFocus = remember { FocusRequester() }
    var focusTargetIndex by remember { mutableStateOf<Int?>(null) }
    var restoreTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(restoreTick) {
        if (restoreTick == 0) return@LaunchedEffect
        val target = focusTargetIndex ?: return@LaunchedEffect
        if (target >= visibleCount) {
            visibleCount = (target + PAGE_SIZE).coerceAtMost(items.size)
        }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
            listState.scrollToItem(target)
        }
        repeat(10) {
            try {
                if (restoreFocus.requestFocus()) return@LaunchedEffect
            } catch (_: IllegalStateException) {
            }
            delay(32)
        }
    }
    var hatArmed by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        // Skip while the viewer or a dialog owns input.
        fun overlayOpen() = viewerIndex != null || pendingDelete != null || confirmDeleteAll
        val onKey: (AndroidEvent.KeyEvent) -> Boolean = { e ->
            val ev = e.event
            if (overlayOpen() || ev.action != KeyEvent.ACTION_DOWN) {
                false
            } else when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { focusManager.moveFocus(FocusDirection.Up); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { focusManager.moveFocus(FocusDirection.Down); true }
                else -> false
            }
        }
        val onMotion: (AndroidEvent.MotionEvent) -> Boolean = { e ->
            val ev = e.event
            if (ev == null || overlayOpen()) {
                false
            } else {
                val hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
                if (kotlin.math.abs(hatY) < 0.5f) {
                    // Vertical axis only; leave horizontal HAT for the rest of the app.
                    hatArmed = true
                    false
                } else {
                    // Move once per press; consume so the OS doesn't also synthesize a DPAD key.
                    if (hatArmed) {
                        hatArmed = false
                        if (hatY <= -0.5f) focusManager.moveFocus(FocusDirection.Up)
                        else focusManager.moveFocus(FocusDirection.Down)
                    }
                    true
                }
            }
        }
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(onKey)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(onMotion)
        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(onKey)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(onMotion)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .displayCutoutPadding()
            .navigationBarsPadding()
            .focusRequester(rootFocus)
            .focusable()
            .focusGroup(),
    ) {
        // Header: back + title.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                BackButton(onClick = onBack)
            }
            Text(
                text = stringResource(R.string.screenshots_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) {
                IconButton(onClick = { confirmDeleteAll = true }) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.screenshots_delete_all),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.screenshots_empty),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // take(visibleCount) preserves indices into the full list.
                itemsIndexed(
                    items.take(visibleCount),
                    key = { _, item -> item.file.path },
                ) { index, item ->
                    val rowShape = RoundedCornerShape(8.dp)
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                when {
                                    index == focusTargetIndex -> Modifier.focusRequester(restoreFocus)
                                    index == 0 -> Modifier.focusRequester(firstRowFocus)
                                    else -> Modifier
                                },
                            )
                            .clip(rowShape)
                            .focusRing(interaction, rowShape)
                            .combinedClickable(
                                interactionSource = interaction,
                                indication = ripple(),
                                onClick = { viewerIndex = index },
                                onLongClick = { pendingDelete = item },
                            )
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(140.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp)),
                        ) {
                            CoilImage(
                                imageModel = { item.file },
                                imageOptions = ImageOptions(),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            text = dateFormat.format(Date(item.dateTakenMillis)),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    AnimatedVisibility(
        visible = viewerIndex != null,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        // Capture the index on open so the content survives the fade-out.
        val start = remember { viewerIndex ?: 0 }
        ScreenshotViewer(
            items = items,
            startIndex = start,
            onClose = { currentIndex ->
                // Deep-linked open pops back to the game; opened-from-list just closes the viewer
                // and restores focus/scroll to the picture the viewer ended on.
                if (initialViewerIndex >= 0) {
                    onBack()
                } else {
                    focusTargetIndex = currentIndex.coerceIn(0, items.size - 1)
                    restoreTick++
                    viewerIndex = null
                }
            },
            onDeleted = { refreshKey++ },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.screenshot_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { ScreenshotManager.delete(item) }
                        refreshKey++
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.screenshots_delete_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { ScreenshotManager.deleteAll(context, appId) }
                        refreshKey++
                        SnackbarManager.show(
                            context.resources.getQuantityString(R.plurals.screenshots_deleted_all, n, n),
                        )
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
