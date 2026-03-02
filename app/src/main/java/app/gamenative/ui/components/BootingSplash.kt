package app.gamenative.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.ui.theme.PluviaTheme
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun BootingSplash(
    visible: Boolean = true,
    text: String = "Initializing...",
    progress: Float = -1f, // -1 for indeterminate, 0-1 for determinate
) {
    // Tips rotation (no animation cost, safe outside visibility check)
    val tips = remember {
        listOf(
            "Booting may take a few minutes on first launch",
            "Tip: You can view the game files by pressing \"Open Container\" in the game settings.",
            "Tip: You can go to the main settings menu and download custom drivers for your device to be used on Bionic.",
            "Tip: If you are getting a DirectX error, make sure you are using DXVK 1.10.3-async and leegao-wrapper on Bionic.",
            "Tip: Try the Direct3D test in the Start Menu after clicking Open Container to check if your device is working correctly.",
            "Tip: Use DXVK for DirectX 8/9/10/11 games, VKD3D for DirectX 12 games and VirGL + WineD3D for OpenGL games.",
            "Tip: Use Turnip on glibc or bionic to play DirectX 12 games. DirectX 12 support for devices that don't support Turnip is currently limited.",
            "Tip: Try the Adreno or Snapdragon 8 Elite drivers on glibc if you are on a compatible device.",
            "Tip: If you are getting a black screen when launching a game, try Open Container and launching the game from A: drive.",
            "Tip: You can add different locations for Custom Games in the settings.",
            "Tip: Turn off \"Show FPS\" to get rid of the mesa overlay.",
            "Tip: Install packages in A:\\_CommonRedist if your game doesn't launch.",
            "Tip: You can enable or disable the onscreen controller with your device's back key.",
            "Tip: You can bring up the keyboard with your device's back key.",
            "Tip: You can tap with two fingers inside the container to right click.",
            "Tip: If you are using the onscreen controller, you can disable the mouse to prevent accidental touches.",
            "Tip: Report issues on Discord so we can fix them.",
            "Tip: Use the Vortek driver in glibc or wrapper-leegao in Bionic if you are on a non-Adreno GPU.",
            "Tip: Lower resolution and use box64 in performance mode to boost FPS.",
            "Tip: If the game is crashing after loading, increase the video memory.",
            "Tip: If you are seeing visual glitches, disable DRI3.",
            "Tip: You can enable touchscreen mode.",
            "Tip: If you have a Mali GPU, please use System Drivers.",
            "Tip: Getting a blank screen? Try using the Test Graphics option in the menu to check if your drivers are working correctly.",
        )
    }

    var tipIndex by remember { mutableStateOf(if (tips.isNotEmpty()) Random.nextInt(tips.size) else 0) }

    LaunchedEffect(visible, tips) {
        while (visible && tips.isNotEmpty()) {
            delay(8000)
            tipIndex = (tipIndex + 1) % tips.size
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 400)),
        exit = fadeOut(animationSpec = tween(durationMillis = 300)),
    ) {
        // Animations only run while visible (inside AnimatedVisibility scope)
        val infiniteTransition = rememberInfiniteTransition(label = "bootSplash")

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
            contentAlignment = Alignment.Center,
        ) {
            AmbientParticles(phase = particlePhase)

            // Main content
            Column(
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
                                colors = listOf(
                                    PluviaTheme.colors.accentCyan,
                                    PluviaTheme.colors.accentPurple,
                                    PluviaTheme.colors.accentPink,
                                ),
                            ),
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

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
                    ),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )

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
                                ),
                                color = PluviaTheme.colors.borderDefault,
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
                        colors = listOf(
                            PluviaTheme.colors.accentCyan,
                            PluviaTheme.colors.accentPurple,
                            PluviaTheme.colors.accentPink,
                        ),
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

            // Pulsing alpha based on phase
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
