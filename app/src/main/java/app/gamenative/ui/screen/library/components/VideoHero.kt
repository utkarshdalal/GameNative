package app.gamenative.ui.screen.library.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@Composable
internal fun VideoHero(
    videoUrl: String?,
    fallbackImageUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    muted: Boolean = true,
    autoplay: Boolean = true,
) {
    val youTubeId = remember(videoUrl) { videoUrl?.let(::extractYouTubeId) }
    var manuallyStarted by remember(videoUrl) { mutableStateOf(false) }

    when {
        youTubeId != null -> YouTubeHero(
            videoId = youTubeId,
            fallbackImageUrl = fallbackImageUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            active = active,
            muted = muted,
            autoplay = autoplay,
        )
        active && videoUrl != null && (autoplay || manuallyStarted) -> ExoVideoHero(
            videoUrl = videoUrl,
            fallbackImageUrl = fallbackImageUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            muted = muted,
        )
        active && videoUrl != null -> VideoPlaceholder(
            contentDescription = contentDescription,
            modifier = modifier,
            onPlay = { manuallyStarted = true },
        )
        videoUrl != null -> VideoPlaceholder(contentDescription, modifier)
        else -> CoilImage(
            imageModel = { fallbackImageUrl },
            imageOptions = ImageOptions(
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
            ),
            modifier = modifier,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoVideoHero(
    videoUrl: String,
    fallbackImageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    muted: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showFallback by remember(videoUrl) { mutableStateOf(true) }
    var playbackFailed by remember(videoUrl) { mutableStateOf(false) }

    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.Builder()
                .setUri(videoUrl)
                .apply { if (videoUrl.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
            setMediaItem(item)
            repeatMode = Player.REPEAT_MODE_ALL
            volume = if (muted) 0f else 1f
            playWhenReady = true
            prepare()
        }
    }

    LaunchedEffect(exoPlayer, muted) {
        exoPlayer.volume = if (muted) 0f else 1f
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        var resumeAfterLifecyclePause = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                showFallback = false
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
                showFallback = false
            }
        }
        exoPlayer.addListener(listener)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resumeAfterLifecyclePause = exoPlayer.playWhenReady
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_RESUME -> if (resumeAfterLifecyclePause) exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            exoPlayer.removeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (playbackFailed && fallbackImageUrl.isNotBlank()) {
            CoilImage(
                imageModel = { fallbackImageUrl },
                imageOptions = ImageOptions(
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .blur(20.dp),
            )
        }

        if (!playbackFailed) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showFallback) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeHero(
    videoId: String,
    fallbackImageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    active: Boolean,
    muted: Boolean,
    autoplay: Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var started by remember(videoId) { mutableStateOf(false) }
    var manuallyStarted by remember(videoId) { mutableStateOf(false) }
    var attempt by remember(videoId) { mutableIntStateOf(0) }

    LaunchedEffect(active, autoplay) {
        when {
            !active -> {
                started = false
                manuallyStarted = false
            }
            autoplay -> started = true
            !manuallyStarted -> started = false
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CoilImage(
            imageModel = { "https://img.youtube.com/vi/$videoId/hqdefault.jpg" },
            imageOptions = ImageOptions(
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
            ),
            failure = {
                CoilImage(
                    imageModel = { fallbackImageUrl },
                    imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                    modifier = Modifier.fillMaxSize(),
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (started && active) {
            val webView = remember(videoId, attempt, muted) {
                WebView(context).apply {
                    // YouTube embeds require JavaScript. videoId is constrained to 11 safe
                    // characters by extractYouTubeId(), and the surrounding HTML is static.
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?,
                        ): Boolean {
                            view?.destroy()
                            started = false
                            manuallyStarted = false
                            return true
                        }
                    }
                    loadDataWithBaseURL(
                        "https://www.gamenative.app",
                        youTubeEmbedHtml(videoId, muted),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            }

            DisposableEffect(webView, lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> webView.onPause()
                        Lifecycle.Event.ON_RESUME -> webView.onResume()
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    webView.destroy()
                }
            }

            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        } else if (active) {
            IconButton(
                onClick = {
                    attempt += 1
                    manuallyStarted = true
                    started = true
                },
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            )
        }
    }
}

@Composable
private fun VideoPlaceholder(
    contentDescription: String,
    modifier: Modifier,
    onPlay: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (onPlay != null) {
            IconButton(
                onClick = onPlay,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

private fun youTubeEmbedHtml(videoId: String, muted: Boolean): String =
    """
    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
    <style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head>
    <body><iframe src="https://www.youtube.com/embed/$videoId?autoplay=1&mute=${if (muted) 1 else 0}&controls=1&playsinline=1&rel=0"
        allow="autoplay; fullscreen" allowfullscreen></iframe></body></html>
    """.trimIndent()

private fun extractYouTubeId(url: String): String? {
    val patterns = listOf(
        Regex("""youtube(?:-nocookie)?\.com/embed/([\w-]{11})"""),
        Regex("""youtu\.be/([\w-]{11})"""),
        Regex("""[?&]v=([\w-]{11})"""),
    )
    for (pattern in patterns) {
        pattern.find(url)?.let { return it.groupValues[1] }
    }
    return null
}
