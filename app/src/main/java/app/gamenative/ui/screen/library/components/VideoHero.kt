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
      #player,#player iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}
      .block{position:absolute;top:0;left:0;width:100%;height:100%;z-index:2}
      #toggle{position:absolute;left:12px;bottom:12px;width:44px;height:44px;z-index:3;
        display:flex;align-items:center;justify-content:center;border-radius:50%;
        background:rgba(0,0,0,0.55);-webkit-tap-highlight-color:transparent}
      #toggle svg{width:22px;height:22px;fill:#fff}
    </style></head>
    <body>
      <div id="player"></div>
      <div class="block"></div>
      <div id="toggle" onclick="toggleVideo()"></div>
      <script>
        var ytPlayer;
        var PAUSE='<svg viewBox="0 0 24 24"><rect x="6" y="5" width="4" height="14"/><rect x="14" y="5" width="4" height="14"/></svg>';
        var PLAY='<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>';
        function setIcon(){
          if(!ytPlayer||!ytPlayer.getPlayerState) return;
          document.getElementById('toggle').innerHTML = (ytPlayer.getPlayerState()===1)?PAUSE:PLAY;
        }
        function toggleVideo(){
          if(!ytPlayer) return;
          if(ytPlayer.getPlayerState()===1) ytPlayer.pauseVideo(); else ytPlayer.playVideo();
        }
        var tag = document.createElement('script');
        tag.src = "https://www.youtube.com/iframe_api";
        document.head.appendChild(tag);
        function onYouTubeIframeAPIReady() {
          ytPlayer = new YT.Player('player', {
            videoId: '$videoId',
            playerVars: {
              autoplay: 1, mute: 1, controls: 0, loop: 1, playlist: '$videoId',
              modestbranding: 1, rel: 0, iv_load_policy: 3, fs: 0, disablekb: 1, playsinline: 1
            },
            events: {
              onReady: function (e) { e.target.mute(); e.target.playVideo(); setIcon(); },
              onStateChange: setIcon
            }
          });
        }
      </script>
    </body></html>
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
