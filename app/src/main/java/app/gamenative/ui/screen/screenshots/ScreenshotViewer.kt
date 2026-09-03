package app.gamenative.ui.screen.screenshots

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ScreenshotItem
import app.gamenative.utils.ScreenshotManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import androidx.compose.ui.viewinterop.AndroidView
import android.view.KeyEvent
import app.gamenative.ui.component.GamepadInputView
import timber.log.Timber
import java.text.DateFormat
import java.util.Date

// Continuous per-second rates so zoom/pan stay smooth while a button is held.
private const val MAX_SCALE = 25f
private const val DEFAULT_ZOOM = 3f
private const val ZOOM_RATE = 2.5f // scale growth per second while held
private const val PAN_SPEED = 700f // pixels/second at 1x
// Unzoomed left-stick page navigation: first step fires immediately, then repeats while held.
private const val NAV_FIRST_DELAY_NANOS = 400_000_000L
private const val NAV_REPEAT_NANOS = 220_000_000L

// Scrim behind the title and toolbar overlays.
private val ScreenshotScrim = Color(0x88000000)

@Composable
fun ScreenshotViewer(
    items: List<ScreenshotItem>,
    startIndex: Int,
    onClose: (currentIndex: Int) -> Unit,
    onDeleted: (ScreenshotItem) -> Unit,
) {
    if (items.isEmpty()) {
        onClose(startIndex)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    val exportToDownloads: (ScreenshotItem) -> Unit = { item ->
        scope.launch {
            val res = withContext(Dispatchers.IO) { ScreenshotManager.exportToDownloads(context, item) }
            val msg = if (res.isSuccess) {
                R.string.screenshot_downloaded
            } else {
                Timber.w(res.exceptionOrNull(), "Export to Downloads failed")
                R.string.screenshot_download_failed
            }
            SnackbarManager.show(context.getString(msg))
        }
    }
    // Pre-Q Downloads writes need WRITE_EXTERNAL_STORAGE; held until the permission prompt resolves.
    var pendingExport by remember { mutableStateOf<ScreenshotItem?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val item = pendingExport
        pendingExport = null
        when {
            item == null -> Unit
            result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true -> exportToDownloads(item)
            else -> SnackbarManager.show(context.getString(R.string.screenshot_download_failed))
        }
    }
    val requestExport: (ScreenshotItem) -> Unit = { item ->
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingExport = item
            storagePermissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else {
            exportToDownloads(item)
        }
    }
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.size - 1),
    ) { items.size }

    // Zoom/pan state, hoisted so controller input can drive it. Reset on page change.
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // Laid-out viewport size, used to clamp panning to the image bounds.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    // Consume system back so it closes the viewer, not the whole gallery screen.
    BackHandler { onClose(pagerState.currentPage) }

    fun clampOffsets() {
        // graphicsLayer scales about center, so max on-screen pan is (scale - 1) * size / 2 per axis.
        val maxX = (scale - 1f) * viewportSize.width / 2f
        val maxY = (scale - 1f) * viewportSize.height / 2f
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }
    fun goTo(page: Int) {
        val target = page.coerceIn(0, items.size - 1)
        if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
    }

    // Held-button and analog-stick state that drives the frame-timed zoom/pan below.
    var zoomInHeld by remember { mutableStateOf(false) }
    var zoomOutHeld by remember { mutableStateOf(false) }
    var panLeftHeld by remember { mutableStateOf(false) }
    var panRightHeld by remember { mutableStateOf(false) }
    var panUpHeld by remember { mutableStateOf(false) }
    var panDownHeld by remember { mutableStateOf(false) }
    var stickX by remember { mutableFloatStateOf(0f) } // left stick: pan
    var stickY by remember { mutableFloatStateOf(0f) }
    var stickZoom by remember { mutableFloatStateOf(0f) } // right stick vertical: zoom
    val anyActive by remember {
        derivedStateOf {
            zoomInHeld || zoomOutHeld || panLeftHeld || panRightHeld || panUpHeld || panDownHeld ||
                stickX != 0f || stickY != 0f || stickZoom != 0f
        }
    }

    fun resetInputState() {
        zoomInHeld = false
        zoomOutHeld = false
        panLeftHeld = false
        panRightHeld = false
        panUpHeld = false
        panDownHeld = false
        stickX = 0f
        stickY = 0f
        stickZoom = 0f
    }

    // Launching another Activity (share/open-with/permission prompt) can swallow the button UP, so
    // reset held flags on pause and re-focus the input view on resume.
    val gamepadViewRef = remember { mutableStateOf<GamepadInputView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> resetInputState()
                // Re-grab focus so the gamepad works on return without a screen tap.
                Lifecycle.Event.ON_RESUME -> gamepadViewRef.value?.requestFocus()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun handleKey(keyCode: Int, down: Boolean): Boolean {
        val zoomed = scale > 1f
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (down) goTo(pagerState.currentPage - 1); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { if (down) goTo(pagerState.currentPage + 1); true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R2 -> { zoomInHeld = down; true }
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_L2 -> { zoomOutHeld = down; true }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (down) {
                    scale = if (zoomed) 1f else DEFAULT_ZOOM
                    if (scale <= 1f) { offsetX = 0f; offsetY = 0f } else clampOffsets()
                }
                true
            }
            // D-pad left/right pans while zoomed, navigates pictures when unzoomed; up/down pans.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!down) panLeftHeld = false
                else if (scale > 1f) panLeftHeld = true
                else goTo(pagerState.currentPage - 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!down) panRightHeld = false
                else if (scale > 1f) panRightHeld = true
                else goTo(pagerState.currentPage + 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { panUpHeld = down; true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { panDownHeld = down; true }
            KeyEvent.KEYCODE_BUTTON_B -> { if (down) onClose(pagerState.currentPage); true }
            else -> false
        }
    }

    LaunchedEffect(anyActive) {
        if (!anyActive) return@LaunchedEffect
        var last = 0L
        // Unzoomed left-stick navigation: timestamp of the last page step; 0 means the stick is
        // centered so the next push steps immediately.
        var lastNavNanos = 0L
        var navRepeating = false
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                // Zoom: buttons (held) + right stick (up = in), multiplicative.
                // Scale the pan offset by the same factor to keep the screen center anchored.
                var zoomFactor = 1f
                if (zoomInHeld) zoomFactor *= 1f + ZOOM_RATE * dt
                if (zoomOutHeld) zoomFactor /= 1f + ZOOM_RATE * dt
                if (stickZoom != 0f) zoomFactor *= 1f + ZOOM_RATE * dt * -stickZoom
                if (zoomFactor != 1f) {
                    val newScale = (scale * zoomFactor).coerceIn(1f, MAX_SCALE)
                    val applied = newScale / scale // realized factor after clamping
                    scale = newScale
                    offsetX *= applied
                    offsetY *= applied
                }
                // Pan: d-pad (held) + left stick.
                val d = PAN_SPEED * dt * scale
                if (panLeftHeld) offsetX += d
                if (panRightHeld) offsetX -= d
                if (panUpHeld) offsetY += d
                if (panDownHeld) offsetY -= d
                if (stickX != 0f) offsetX -= stickX * d
                if (stickY != 0f) offsetY -= stickY * d
                if (scale <= 1f) {
                    offsetX = 0f
                    offsetY = 0f
                    // Unzoomed: holding the left stick left/right steps through pictures, repeating.
                    if (stickX > 0.5f || stickX < -0.5f) {
                        val interval = if (navRepeating) NAV_REPEAT_NANOS else NAV_FIRST_DELAY_NANOS
                        if (lastNavNanos == 0L || now - lastNavNanos >= interval) {
                            goTo(pagerState.currentPage + if (stickX > 0f) 1 else -1)
                            if (lastNavNanos != 0L) navRepeating = true
                            lastNavNanos = now
                        }
                    } else {
                        lastNavNanos = 0L
                        navRepeating = false
                    }
                } else {
                    clampOffsets()
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .background(Color.Black),
    ) {
        // Invisible focusable view for gamepad buttons + sticks (Compose can't read sticks).
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx ->
                GamepadInputView(ctx).apply {
                    onKey = { keyCode, down -> handleKey(keyCode, down) }
                    onSticks = { lx, ly, ry -> stickX = lx; stickY = ly; stickZoom = ry }
                    gamepadViewRef.value = this
                    post { requestFocus() }
                }
            },
        )
        // Pager swipes only at min zoom; while zoomed the image owns the drag for panning.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scale <= 1f,
        ) { page ->
            // getOrNull: a page index may be stale after a delete shrinks the list.
            val pageFile = items.getOrNull(page)?.file ?: return@HorizontalPager
            CoilImage(
                imageModel = { pageFile },
                imageOptions = ImageOptions(contentScale = ContentScale.Fit),
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Claim only pinch (any time) or pan (while zoomed); leave single-finger
                        // swipes to the pager.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pointerCount = event.changes.count { it.pressed }
                                if (pointerCount > 1 || scale > 1f) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid()
                                    val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                    val z = newScale / scale // realized zoom after clamping
                                    scale = newScale
                                    if (scale > 1f) {
                                        // Zoom toward the pinch centroid (graphicsLayer scales about
                                        // the center), then apply the pan.
                                        val cx = viewportSize.width / 2f
                                        val cy = viewportSize.height / 2f
                                        val fx = if (centroid.x.isNaN()) cx else centroid.x
                                        val fy = if (centroid.y.isNaN()) cy else centroid.y
                                        val maxX = (scale - 1f) * viewportSize.width / 2f
                                        val maxY = (scale - 1f) * viewportSize.height / 2f
                                        offsetX = (z * offsetX + (1 - z) * (fx - cx) + z * pan.x).coerceIn(-maxX, maxX)
                                        offsetY = (z * offsetY + (1 - z) * (fy - cy) + z * pan.y).coerceIn(-maxY, maxY)
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )
        }

        IconButton(
            onClick = { onClose(pagerState.currentPage) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .displayCutoutPadding(),
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.screenshots_back), tint = Color.White)
        }

        // Title: capture date over file name, centered. Fades out while zoomed in.
        items.getOrNull(pagerState.currentPage)?.let { item ->
            AnimatedVisibility(
                visible = scale <= 1f,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .displayCutoutPadding()
                        .padding(vertical = 12.dp)
                        .background(ScreenshotScrim, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = dateFormat.format(Date(item.dateTakenMillis)),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                    )
                    Text(
                        text = item.file.name,
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }

        // The bottom toolbar fades out while zoomed in, mirroring the info overlay.
        AnimatedVisibility(
            visible = scale <= 1f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScreenshotScrim)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
            val current = items.getOrNull(pagerState.currentPage)
            IconButton(onClick = {
                val item = current ?: return@IconButton
                scope.launch { openWithGalleryWithFallback(context, item) }
            }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.screenshot_open_with), tint = Color.White)
            }
            IconButton(onClick = {
                val item = current ?: return@IconButton
                requestExport(item)
            }) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.screenshot_download), tint = Color.White)
            }
            IconButton(onClick = {
                val item = current ?: return@IconButton
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) { ScreenshotManager.delete(item) }
                    if (deleted) {
                        SnackbarManager.show(context.getString(R.string.screenshot_deleted))
                        // Keep the pager in range; items is the pre-delete snapshot (new last index = size - 2).
                        if (items.size <= 1) {
                            onClose(0)
                        } else if (pagerState.currentPage >= items.size - 1) {
                            pagerState.scrollToPage((items.size - 2).coerceAtLeast(0))
                        }
                        onDeleted(item)
                    }
                }
            }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.screenshot_delete), tint = Color.White)
            }
            IconButton(onClick = {
                val item = current ?: return@IconButton
                scope.launch { shareWithFallback(context, item) }
            }) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.screenshot_share), tint = Color.White)
            }
            }
        }
    }
}

/** Share the screenshot; if the file lives outside app dirs (FileProvider throws), retry via a cache copy. */
private suspend fun shareWithFallback(context: android.content.Context, item: ScreenshotItem) {
    runCatching { ScreenshotManager.shareScreenshot(context, item) }
        .onFailure {
            Timber.w(it, "Share failed; retrying via cache copy")
            runCatching {
                // cacheCopyFor copies the whole file; keep it off the main thread.
                val copy = withContext(Dispatchers.IO) { ScreenshotManager.cacheCopyFor(context, item) }
                ScreenshotManager.shareScreenshot(context, copy)
            }.onFailure { e -> Timber.e(e, "Share cache fallback failed") }
        }
}

/** Open with an external viewer; if the file lives outside app dirs (FileProvider throws), retry via a cache copy. */
private suspend fun openWithGalleryWithFallback(context: android.content.Context, item: ScreenshotItem) {
    runCatching { ScreenshotManager.openWithGallery(context, item) }
        .onFailure {
            Timber.w(it, "Open-with failed; retrying via cache copy")
            runCatching {
                // cacheCopyFor copies the whole file; keep it off the main thread.
                val copy = withContext(Dispatchers.IO) { ScreenshotManager.cacheCopyFor(context, item) }
                ScreenshotManager.openWithGallery(context, copy)
            }.onFailure { e -> Timber.e(e, "Open-with cache fallback failed") }
        }
}
