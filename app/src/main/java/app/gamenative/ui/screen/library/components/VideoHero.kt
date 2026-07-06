package app.gamenative.ui.screen.library.components

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
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
) {
    val youTubeId = remember(videoUrl) { videoUrl?.let(::extractYouTubeId) }

    when {
        active && youTubeId != null -> YouTubeHero(youTubeId, fallbackImageUrl, contentDescription, modifier)
        active && videoUrl != null -> ExoVideoHero(videoUrl, fallbackImageUrl, contentDescription, modifier)
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showFallback by remember(videoUrl) { mutableStateOf(true) }

    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                showFallback = false
            }
        }
        exoPlayer.addListener(listener)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> exoPlayer.play()
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
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        CoilImage(
            imageModel = { fallbackImageUrl },
            imageOptions = ImageOptions(
                contentDescription = null,
                contentScale = ContentScale.Crop,
            ),
            modifier = Modifier
                .fillMaxSize()
                .blur(20.dp),
        )

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (showFallback) {
            CoilImage(
                imageModel = { fallbackImageUrl },
                imageOptions = ImageOptions(
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun YouTubeHero(
    videoId: String,
    fallbackImageUrl: String,
    contentDescription: String,
    modifier: Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var showFallback by remember(videoId) { mutableStateOf(true) }

    val webView = remember(videoId) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    showFallback = false
                }
            }
            loadDataWithBaseURL(
                "https://www.youtube-nocookie.com",
                youTubeEmbedHtml(videoId),
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

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CoilImage(
            imageModel = { fallbackImageUrl },
            imageOptions = ImageOptions(
                contentDescription = null,
                contentScale = ContentScale.Crop,
            ),
            modifier = Modifier
                .fillMaxSize()
                .blur(20.dp),
        )

        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

        if (showFallback) {
            CoilImage(
                imageModel = { fallbackImageUrl },
                imageOptions = ImageOptions(
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun youTubeEmbedHtml(videoId: String): String =
    """
    <html><head><style>
      html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}
      .wrap{position:absolute;top:0;left:0;right:0;bottom:0}
      iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0;pointer-events:none}
      .block{position:absolute;top:0;left:0;width:100%;height:100%;z-index:2;background:transparent}
    </style></head>
    <body><div class="wrap">
      <iframe
        src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&mute=1&controls=0&loop=1&playlist=$videoId&modestbranding=1&playsinline=1&rel=0&fs=0&disablekb=1&iv_load_policy=3"
        allow="autoplay; encrypted-media" frameborder="0"></iframe>
      <div class="block"></div>
    </div></body></html>
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
