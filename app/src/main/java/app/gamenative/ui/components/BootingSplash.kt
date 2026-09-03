package app.gamenative.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily
import app.gamenative.PrefManager
import app.gamenative.data.BootQuizQuestion
import app.gamenative.data.localizedPrompt
import app.gamenative.data.localizedWinBody
import app.gamenative.data.localizedLoseBody
import app.gamenative.data.isPlayable
import com.posthog.PostHog
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.gamenative.R
import app.gamenative.data.BootAdItem
import app.gamenative.data.BootAdRepository
import app.gamenative.data.FeaturedCta
import app.gamenative.data.localizedBody
import app.gamenative.data.localizedLabel
import app.gamenative.data.localizedTitle
import app.gamenative.ui.screen.library.FeaturedCtaButton
import app.gamenative.ui.theme.BrandGradient
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.io.File
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun BootingSplash(
    visible: Boolean = true,
    text: String = "Initializing...",
    progress: Float = -1f, // -1 for indeterminate, 0-1 for determinate
    heroImageUrl: String = "",
    bootAd: BootAdItem? = null,
) {
    // Tips rotation (no animation cost, safe outside visibility check)
    val context = LocalContext.current
    val tips = remember(context) {
        listOf(
            context.getString(R.string.game_launch_tip_1),
            context.getString(R.string.game_launch_tip_2, context.getString(R.string.option_open_container)),
            context.getString(R.string.game_launch_tip_3),
            context.getString(R.string.game_launch_tip_4),
            context.getString(R.string.game_launch_tip_5, context.getString(R.string.option_test_graphics)),
            context.getString(R.string.game_launch_tip_6),
            context.getString(R.string.game_launch_tip_7),
            context.getString(R.string.game_launch_tip_8),
            context.getString(R.string.game_launch_tip_9, context.getString(R.string.option_open_container)),
            context.getString(R.string.game_launch_tip_10),
            context.getString(R.string.game_launch_tip_11),
            context.getString(R.string.game_launch_tip_12),
            context.getString(R.string.game_launch_tip_13),
            context.getString(R.string.game_launch_tip_14),
            context.getString(R.string.game_launch_tip_15),
            context.getString(R.string.game_launch_tip_16),
            context.getString(R.string.game_launch_tip_17),
            context.getString(R.string.game_launch_tip_18),
            context.getString(R.string.game_launch_tip_19),
            context.getString(R.string.game_launch_tip_20),
            context.getString(R.string.game_launch_tip_21),
            context.getString(R.string.game_launch_tip_22),
            context.getString(R.string.game_launch_tip_23),
            context.getString(R.string.game_launch_tip_24, context.getString(R.string.option_test_graphics)),
            context.getString(R.string.game_launch_tip_25),
        )
    }

    var tipIndex by remember { mutableStateOf(if (tips.isNotEmpty()) Random.nextInt(tips.size) else 0) }

    LaunchedEffect(visible, tips) {
        while (visible && tips.isNotEmpty()) {
            delay(8000)
            tipIndex = (tipIndex + 1) % tips.size
        }
    }

    // Sponsor backdrop rotates hero art + screenshots, tips-style
    val adImages = remember(bootAd?.campaignId) {
        bootAd?.let { ad -> (listOf(ad.imageUrl) + ad.screenshots).filter { it.isNotEmpty() } } ?: emptyList()
    }
    // Sponsor videos play from the pre-downloaded file; the house recommendation card
    // streams its store trailer (built only on WiFi).
    val adVideoUri = remember(bootAd?.campaignId) {
        bootAd?.takeIf { it.template == BootAdRepository.TEMPLATE_VIDEO_CARD }?.let { ad ->
            if (ad.sponsored) {
                BootAdRepository.cachedVideoFile(context, ad)?.let(Uri::fromFile)
            } else {
                ad.videoUrl.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            }
        }
    }
    var adImageIndex by remember(bootAd?.campaignId) { mutableStateOf(0) }

    LaunchedEffect(visible, adImages) {
        while (visible && adImages.size > 1) {
            delay(6000)
            adImageIndex = (adImageIndex + 1) % adImages.size
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 400)),
        exit = fadeOut(animationSpec = tween(durationMillis = 300)),
    ) {
        // Animations only run while visible (inside AnimatedVisibility scope)
        val infiniteTransition = rememberInfiniteTransition(label = "bootSplash")
        val scrimColor = MaterialTheme.colorScheme.scrim
        var heroImageFailed by remember(heroImageUrl) { mutableStateOf(false) }
        var adImageFailed by remember(bootAd?.campaignId) { mutableStateOf(false) }
        val activeAd = if (adImageFailed) null else bootAd

        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glowPulse",
        )

        val logoScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.02f,
            animationSpec = infiniteRepeatable(
                animation = tween(3000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "logoScale",
        )

        val shimmerPosition by infiniteTransition.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer",
        )

        val particlePhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(20000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "particlePhase",
        )

        val useHeroBackdrop = heroImageUrl.isNotEmpty() && !heroImageFailed

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                PluviaTheme.colors.surfacePanel,
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )

            AmbientParticles(phase = particlePhase)

            LaunchedEffect(visible, activeAd?.campaignId, adVideoUri) {
                timber.log.Timber.tag("BootAdTrace").i(
                    "splash: visible=%s activeAd=%s video=%s", visible, activeAd?.campaignId, adVideoUri != null,
                )
            }
            if (activeAd != null && (adVideoUri != null || activeAd.imageUrl.isNotEmpty())) {
                if (adVideoUri != null) {
                    BootAdVideo(
                        uri = adVideoUri,
                        fallbackImageUrl = activeAd.imageUrl,
                    )
                } else Crossfade(
                    targetState = adImageIndex,
                    animationSpec = tween(durationMillis = 800, easing = EaseInOutCubic),
                    label = "adImageCrossfade",
                ) { idx ->
                    val url = adImages.getOrElse(idx) { activeAd.imageUrl }
                    CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { url },
                        imageOptions = ImageOptions(
                            contentScale = ContentScale.Crop,
                            contentDescription = null,
                        ),
                        loading = {},
                        failure = {
                            // Only the primary art disables the card; a bad screenshot just skips
                            if (url == activeAd.imageUrl) {
                                adImageFailed = true
                            }
                        },
                        previewPlaceholder = painterResource(R.drawable.ic_logo_color),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to scrimColor.copy(alpha = 0.25f),
                                    0.45f to scrimColor.copy(alpha = 0.15f),
                                    1.0f to scrimColor.copy(alpha = 0.85f),
                                ),
                            ),
                        ),
                )
            } else if (useHeroBackdrop) {
                val desaturate = remember {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                }

                CoilImage(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.06f
                            scaleY = 1.06f
                        }
                        .alpha(0.38f)
                        .blur(7.dp),
                    imageModel = { heroImageUrl },
                    imageOptions = ImageOptions(
                        contentScale = ContentScale.Crop,
                        contentDescription = null,
                        colorFilter = desaturate,
                    ),
                    loading = {},
                    failure = {
                        heroImageFailed = true
                    },
                    previewPlaceholder = painterResource(R.drawable.ic_logo_color),
                )

                // Single soft legibility scrim: light at the top, building toward the
                // bottom where the status/tips text sits. No heavy top/bottom bands.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to scrimColor.copy(alpha = 0.48f),
                                    0.4f to scrimColor.copy(alpha = 0.48f),
                                    1.0f to scrimColor.copy(alpha = 0.62f),
                                ),
                            ),
                        ),
                )
            }

            // Main content
            if (activeAd != null) {
                BootAdContent(
                    ad = activeAd,
                    statusText = text,
                    progress = progress,
                    shimmerPosition = shimmerPosition,
                    scrimColor = scrimColor,
                )
            } else Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Spacer(modifier = Modifier.weight(0.4f))

                // Logo with glow effect
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.scale(logoScale),
                ) {
                    // Glow layer (blurred behind)
                    Text(
                        text = "GameNative",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 36.sp,
                            letterSpacing = 2.sp,
                        ),
                        color = PluviaTheme.colors.accentCyan.copy(alpha = glowAlpha * 0.6f),
                        modifier = Modifier
                            .blur(20.dp)
                            .padding(20.dp)
                            .alpha(glowAlpha),
                    )

                    // Main logo text
                    Text(
                        text = "GameNative",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 36.sp,
                            letterSpacing = 2.sp,
                            shadow = Shadow(
                                color = PluviaTheme.colors.accentCyan.copy(alpha = 0.5f),
                                offset = Offset(0f, 0f),
                                blurRadius = 20f,
                            ),
                            brush = Brush.horizontalGradient(
                                colors = BrandGradient,
                            ),
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                ProgressBar(
                    progress = progress,
                    shimmerPosition = shimmerPosition,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(4.dp),
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Status text
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        shadow = Shadow(
                            color = scrimColor.copy(alpha = if (useHeroBackdrop) 0.9f else 0f),
                            offset = Offset(0f, 1f),
                            blurRadius = 6f,
                        ),
                    ),
                    color = if (useHeroBackdrop) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                    } else {
                        Color.White.copy(alpha = 0.7f)
                    },
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(15.dp))
                Spacer(modifier = Modifier.weight(0.3f))

                // Tips section
                if (tips.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Crossfade(
                            targetState = tipIndex,
                            animationSpec = tween(durationMillis = 800, easing = EaseInOutCubic),
                            label = "tipCrossfade",
                        ) { idx ->
                            Text(
                                text = tips[idx],
                                style = MaterialTheme.typography.bodySmall.copy(
                                    lineHeight = 20.sp,
                                    shadow = Shadow(
                                        color = scrimColor.copy(alpha = if (useHeroBackdrop) 0.9f else 0f),
                                        offset = Offset(0f, 1f),
                                        blurRadius = 6f,
                                    ),
                                ),
                                color = if (useHeroBackdrop) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun BootAdVideo(
    uri: Uri,
    fallbackImageUrl: String,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showFallback by remember(uri) { mutableStateOf(true) }
    var soundOn by remember(uri) { mutableStateOf(false) }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.Builder()
                .setUri(uri)
                .apply { if (uri.toString().contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
            setMediaItem(item)
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
            timber.log.Timber.tag("BootAdTrace").i("video disposed")
            exoPlayer.removeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                // Inflated for app:surface_type="texture_view": a SurfaceView here loses the
                // window's punch-through to the XServer's SurfaceView and renders black.
                (android.view.LayoutInflater.from(ctx).inflate(R.layout.boot_ad_player, null) as PlayerView).apply {
                    player = exoPlayer
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
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }

        IconButton(
            onClick = {
                soundOn = !soundOn
                exoPlayer.volume = if (soundOn) 1f else 0f
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        ) {
            Icon(
                imageVector = if (soundOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = if (soundOn) "Mute" else "Unmute",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun BootAdContent(
    ad: BootAdItem,
    statusText: String,
    progress: Float,
    shimmerPosition: Float,
    scrimColor: Color,
) {
    val context = LocalContext.current
    val textShadow = Shadow(
        color = scrimColor.copy(alpha = 0.9f),
        offset = Offset(0f, 1f),
        blurRadius = 6f,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(if (ad.sponsored) R.string.featured_badge else R.string.boot_rec_badge),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(scrimColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 520.dp)
                .padding(horizontal = 32.dp)
                .padding(bottom = 24.dp),
        ) {
            val cta = remember(ad) {
                ad.action?.let {
                    FeaturedCta(
                        label = it.localizedLabel(context),
                        url = it.url,
                        primary = true,
                        type = it.type.uppercase(),
                        appId = it.appId ?: ad.appId,
                    )
                }
            }
            val quizQuestion = remember(ad.campaignId) {
                if (ad.template == BootAdRepository.TEMPLATE_QUIZ_CARD) {
                    ad.questions.filter { it.isPlayable() }.randomOrNull()
                } else {
                    null
                }
            }

            if (quizQuestion != null) {
                BootQuizCard(
                    ad = ad,
                    question = quizQuestion,
                    cta = cta,
                    scrimColor = scrimColor,
                    textShadow = textShadow,
                )
                Spacer(modifier = Modifier.height(18.dp))
            } else {
                val title = ad.localizedTitle(context)
                if (title.isNotEmpty()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            shadow = textShadow,
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                val body = ad.localizedBody(context)
                if (body.isNotEmpty()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = textShadow),
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                if (cta != null) {
                    FeaturedCtaButton(
                        action = cta,
                        campaignId = ad.campaignId,
                        recSource = if (ad.sponsored) "boot_ad" else "boot_rec",
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }

            ProgressBar(
                progress = progress,
                shimmerPosition = shimmerPosition,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(4.dp),
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall.copy(shadow = textShadow),
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BootQuizCard(
    ad: BootAdItem,
    question: BootQuizQuestion,
    cta: FeaturedCta?,
    scrimColor: Color,
    textShadow: Shadow,
) {
    val context = LocalContext.current
    var selected by remember(question) { mutableStateOf<Int?>(null) }
    var timedOut by remember(question) { mutableStateOf(false) }
    val totalMs = (question.timerSeconds.coerceIn(3, 60)) * 1000L
    var remainingMs by remember(question) { mutableStateOf(totalMs) }
    val resolved = selected != null || timedOut
    val won = selected == question.correctIndex

    LaunchedEffect(question, resolved) {
        if (resolved) return@LaunchedEffect
        val start = SystemClock.elapsedRealtime()
        while (true) {
            delay(50)
            val left = totalMs - (SystemClock.elapsedRealtime() - start)
            if (selected != null) return@LaunchedEffect
            if (left <= 0) {
                remainingMs = 0
                timedOut = true
                return@LaunchedEffect
            }
            remainingMs = left
        }
    }

    LaunchedEffect(resolved) {
        if (resolved && PrefManager.usageAnalyticsEnabled) {
            PostHog.capture(
                event = "boot_quiz_answered",
                properties = mapOf(
                    "campaign_id" to ad.campaignId,
                    "correct" to won,
                    "timed_out" to timedOut,
                    "answer_index" to (selected ?: -1),
                    "time_left_ms" to remainingMs,
                ),
            )
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(scrimColor.copy(alpha = 0.55f))
            .padding(16.dp)
            .fillMaxWidth(),
    ) {
        val prompt = question.localizedPrompt(context)
        if (prompt.isNotEmpty()) {
            Text(
                text = prompt,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    shadow = textShadow,
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
        if (question.code.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = question.code,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = Color(0xFFB9F6CA),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!resolved) {
            LinearProgressIndicator(
                progress = { remainingMs.toFloat() / totalMs },
                color = if (remainingMs < totalMs / 4) Color(0xFFFF7043) else Color(0xFF80D8FF),
                trackColor = Color.White.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                question.options.forEachIndexed { index, option ->
                    OutlinedButton(
                        onClick = { if (!resolved) selected = index },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(text = option, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                question.options.forEachIndexed { index, option ->
                    val chipColor = when {
                        index == question.correctIndex -> Color(0xFF66BB6A)
                        index == selected -> Color(0xFFEF5350)
                        else -> Color.White.copy(alpha = 0.25f)
                    }
                    Text(
                        text = option,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(chipColor.copy(alpha = 0.45f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val fallback = if (won) {
                stringResource(R.string.boot_quiz_win_default)
            } else {
                stringResource(R.string.boot_quiz_lose_default, question.options[question.correctIndex])
            }
            val resultCopy = (if (won) question.localizedWinBody(context) else question.localizedLoseBody(context))
                .ifEmpty { fallback }
            Text(
                text = resultCopy,
                style = MaterialTheme.typography.bodyMedium.copy(shadow = textShadow),
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
            )
            if (cta != null) {
                Spacer(modifier = Modifier.height(12.dp))
                FeaturedCtaButton(
                    action = cta,
                    campaignId = ad.campaignId,
                    recSource = "boot_ad_quiz",
                )
            }
        }
    }
}

@Composable
private fun AmbientParticles(
    phase: Float,
    modifier: Modifier = Modifier,
) {
    val particleColor = PluviaTheme.colors.accentCyan

    val particles = remember {
        List(12) {
            ParticleData(
                baseX = Random.nextFloat(),
                baseY = Random.nextFloat(),
                size = Random.nextFloat() * 3f + 1f,
                speed = Random.nextFloat() * 0.5f + 0.5f,
                phaseOffset = Random.nextFloat() * 360f,
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        particles.forEach { particle ->
            val animatedPhase = (phase + particle.phaseOffset) * particle.speed
            val radians = Math.toRadians(animatedPhase.toDouble())

            val offsetX = (sin(radians) * 30).toFloat()
            val offsetY = (sin(radians * 0.7) * 20).toFloat()

            val x = particle.baseX * size.width + offsetX
            val y = particle.baseY * size.height + offsetY
            val alpha = (0.15f + 0.15f * sin(radians * 2).toFloat()).coerceIn(0f, 0.3f)

            drawCircle(
                color = particleColor.copy(alpha = alpha),
                radius = particle.size.dp.toPx(),
                center = Offset(x, y),
            )
        }
    }
}

private data class ParticleData(
    val baseX: Float,
    val baseY: Float,
    val size: Float,
    val speed: Float,
    val phaseOffset: Float,
)

@Composable
private fun ProgressBar(
    progress: Float,
    shimmerPosition: Float,
    modifier: Modifier = Modifier,
) {
    val isIndeterminate = progress < 0f
    val actualProgress = if (isIndeterminate) 1f else progress.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(PluviaTheme.colors.borderDefault.copy(alpha = 0.3f)),
    ) {
        // Progress fill with gradient
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(actualProgress)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = BrandGradient,
                    ),
                ),
        )

        // Shimmer overlay
        if (isIndeterminate || progress > 0f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(2.dp)),
            ) {
                val shimmerWidth = size.width * 0.3f
                val shimmerStart = (shimmerPosition * size.width) - shimmerWidth
                val shimmerEnd = shimmerStart + shimmerWidth

                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.4f),
                            Color.Transparent,
                        ),
                        startX = shimmerStart,
                        endX = shimmerEnd,
                    ),
                )
            }
        }
    }
}


@Preview(name = "BootingSplash - Indeterminate")
@Composable
fun BootingSplashPreview() {
    PluviaTheme {
        BootingSplash(visible = true)
    }
}

@Preview(name = "BootingSplash - 50% Progress")
@Composable
fun BootingSplashProgressPreview() {
    PluviaTheme {
        BootingSplash(
            visible = true,
            text = "Loading game files...",
            progress = 0.5f,
        )
    }
}

@Preview(name = "BootingSplash - Sponsor card", device = "spec:width=1920px,height=1080px,dpi=440")
@Composable
fun BootingSplashAdPreview() {
    PluviaTheme {
        BootingSplash(
            visible = true,
            text = "Booting...",
            bootAd = BootAdItem(
                campaignId = "preview",
                imageUrl = "https://example.com/hero.jpg",
                title = mapOf("en" to "Whisk"),
                body = mapOf("en" to "A two-player platformer about shared movement."),
                action = app.gamenative.data.FeaturedAction(
                    type = "WISHLIST",
                    url = "https://example.com",
                    store = "Steam",
                ),
            ),
        )
    }
}

@Preview(name = "BootingSplash - Dark", device = "spec:width=1920px,height=1080px,dpi=440")
@Composable
fun BootingSplashLandscapePreview() {
    PluviaTheme {
        BootingSplash(
            visible = true,
            text = "Preparing container...",
            progress = -1f,
        )
    }
}
