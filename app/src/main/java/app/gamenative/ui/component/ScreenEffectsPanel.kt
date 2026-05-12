package app.gamenative.ui.component

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.ScreenEffectsConfig
import app.gamenative.ui.util.adaptivePanelWidth
import app.gamenative.ui.util.applyScreenEffectsConfig
import app.gamenative.ui.util.loadScreenEffectsConfig
import app.gamenative.ui.util.persistScreenEffectsConfig
import com.winlator.container.Container
import com.winlator.renderer.GLRenderer
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val SCREEN_EFFECT_PERCENT_STEP = 5f
private const val SCREEN_EFFECT_GAMMA_STEP = 0.1f

private fun scalingModeLabelRes(mode: Int): Int = when (mode) {
    ScreenEffectsConfig.SCALING_MODE_NEAREST -> R.string.screen_effects_scaling_mode_nearest
    ScreenEffectsConfig.SCALING_MODE_LINEAR -> R.string.screen_effects_scaling_mode_linear
    ScreenEffectsConfig.SCALING_MODE_FILL -> R.string.screen_effects_scaling_mode_fill
    ScreenEffectsConfig.SCALING_MODE_STRETCH -> R.string.screen_effects_scaling_mode_stretch
    ScreenEffectsConfig.SCALING_MODE_FSR -> R.string.screen_effects_scaling_mode_fsr
    ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT -> R.string.screen_effects_scaling_mode_fsr_aspect
    else -> R.string.screen_effects_scaling_mode_none
}

@Composable
fun ScreenEffectsTabContent(
    renderer: GLRenderer,
    modifier: Modifier = Modifier,
    container: Container? = null,
    firstItemFocusRequester: FocusRequester? = null,
    scrollState: ScrollState = rememberScrollState(),
) {
    val initialConfig = remember(renderer, container) { loadScreenEffectsConfig(container) }

    var brightness by remember(renderer, container) {
        mutableFloatStateOf(initialConfig.brightness)
    }
    var contrast by remember(renderer, container) {
        mutableFloatStateOf(initialConfig.contrast)
    }
    var gamma by remember(renderer, container) {
        mutableFloatStateOf(initialConfig.gamma)
    }
    var scalingMode by remember(renderer, container) {
        mutableIntStateOf(initialConfig.scalingMode)
    }
    var fsrSharpnessLevel by remember(renderer, container) {
        mutableIntStateOf(initialConfig.fsrSharpnessLevel)
    }
    var enableToon by remember(renderer, container) {
        mutableStateOf(initialConfig.enableToon)
    }
    var enableFXAA by remember(renderer, container) {
        mutableStateOf(initialConfig.enableFXAA)
    }
    var enableVivid by remember(renderer, container) {
        mutableStateOf(initialConfig.enableVivid)
    }
    var enableCRT by remember(renderer, container) {
        mutableStateOf(initialConfig.enableCRT)
    }
    var enableNTSC by remember(renderer, container) {
        mutableStateOf(initialConfig.enableNTSC)
    }

    LaunchedEffect(
        brightness,
        contrast,
        gamma,
        scalingMode,
        fsrSharpnessLevel,
        enableToon,
        enableFXAA,
        enableVivid,
        enableCRT,
        enableNTSC,
    ) {
        val config = ScreenEffectsConfig(
            brightness = brightness,
            contrast = contrast,
            gamma = gamma,
            scalingMode = scalingMode,
            fsrSharpnessLevel = fsrSharpnessLevel,
            enableToon = enableToon,
            enableFXAA = enableFXAA,
            enableVivid = enableVivid,
            enableCRT = enableCRT,
            enableNTSC = enableNTSC,
        )
        // Apply immediately for live preview
        applyScreenEffectsConfig(renderer, config)
        // Debounce persist to disk
        delay(300)
        persistScreenEffectsConfig(container, config)
        container?.saveData()
    }

    fun resetEffects() {
        brightness = 0f
        contrast = 0f
        gamma = 1.0f
        scalingMode = ScreenEffectsConfig.SCALING_MODE_NONE
        fsrSharpnessLevel = ScreenEffectsConfig.FSR_DEFAULT_LEVEL
        enableToon = false
        enableFXAA = false
        enableVivid = false
        enableCRT = false
        enableNTSC = false
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup()
            .padding(vertical = 12.dp),
    ) {
        OptionSectionHeader(text = stringResource(R.string.screen_effects_scaling))

        ScreenEffectAdjustmentRow(
            title = stringResource(R.string.screen_effects_scaling_mode),
            valueText = stringResource(scalingModeLabelRes(scalingMode)),
            progress = normalizedProgress(
                scalingMode.toFloat(),
                ScreenEffectsConfig.SCALING_MODE_NONE.toFloat(),
                ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT.toFloat(),
            ),
            onDecrease = {
                scalingMode = (scalingMode - 1).coerceAtLeast(ScreenEffectsConfig.SCALING_MODE_NONE)
            },
            onIncrease = {
                scalingMode = (scalingMode + 1).coerceAtMost(ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT)
            },
            focusRequester = firstItemFocusRequester,
        )
        if (scalingMode == ScreenEffectsConfig.SCALING_MODE_FSR || scalingMode == ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT) {
            ScreenEffectAdjustmentRow(
                title = stringResource(R.string.screen_effects_fsr_sharpness),
                valueText = stringResource(R.string.screen_effects_fsr_sharpness_value, fsrSharpnessLevel),
                progress = normalizedProgress(
                    fsrSharpnessLevel.toFloat(),
                    ScreenEffectsConfig.FSR_MIN_LEVEL.toFloat(),
                    ScreenEffectsConfig.FSR_MAX_LEVEL.toFloat(),
                ),
                onDecrease = {
                    fsrSharpnessLevel = (fsrSharpnessLevel - 1).coerceAtLeast(ScreenEffectsConfig.FSR_MIN_LEVEL)
                },
                onIncrease = {
                    fsrSharpnessLevel = (fsrSharpnessLevel + 1).coerceAtMost(ScreenEffectsConfig.FSR_MAX_LEVEL)
                },
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        OptionSectionHeader(text = stringResource(R.string.screen_effects_color_adjustments))

        ScreenEffectAdjustmentRow(
            title = stringResource(R.string.screen_effects_brightness),
            valueText = formatPercent(brightness),
            progress = normalizedProgress(brightness, -100f, 100f),
            onDecrease = {
                brightness = (brightness - SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
            },
            onIncrease = {
                brightness = (brightness + SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
            },
        )
        ScreenEffectAdjustmentRow(
            title = stringResource(R.string.screen_effects_contrast),
            valueText = formatPercent(contrast),
            progress = normalizedProgress(contrast, -100f, 100f),
            onDecrease = {
                contrast = (contrast - SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
            },
            onIncrease = {
                contrast = (contrast + SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
            },
        )
        ScreenEffectAdjustmentRow(
            title = stringResource(R.string.screen_effects_gamma),
            valueText = String.format("%.2fx", gamma),
            progress = normalizedProgress(gamma, 0.5f, 2.5f),
            onDecrease = {
                gamma = (gamma - SCREEN_EFFECT_GAMMA_STEP).coerceIn(0.5f, 2.5f)
            },
            onIncrease = {
                gamma = (gamma + SCREEN_EFFECT_GAMMA_STEP).coerceIn(0.5f, 2.5f)
            },
        )

        Spacer(modifier = Modifier.height(20.dp))

        OptionSectionHeader(text = stringResource(R.string.screen_effects_shader_toggles))

        ScreenEffectToggleRow(
            title = stringResource(R.string.screen_effects_toon),
            subtitle = stringResource(R.string.screen_effects_toon_description),
            enabled = enableToon,
            onToggle = { enableToon = !enableToon },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.screen_effects_fxaa),
            subtitle = stringResource(R.string.screen_effects_fxaa_description),
            enabled = enableFXAA,
            onToggle = { enableFXAA = !enableFXAA },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.screen_effects_vivid),
            subtitle = stringResource(R.string.screen_effects_vivid_description),
            enabled = enableVivid,
            onToggle = { enableVivid = !enableVivid },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.screen_effects_crt),
            subtitle = stringResource(R.string.screen_effects_crt_description),
            enabled = enableCRT,
            onToggle = { enableCRT = !enableCRT },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.screen_effects_ntsc),
            subtitle = stringResource(R.string.screen_effects_ntsc_description),
            enabled = enableNTSC,
            onToggle = { enableNTSC = !enableNTSC },
        )

        Spacer(modifier = Modifier.height(20.dp))

        ScreenEffectActionRow(
            title = stringResource(R.string.screen_effects_reset),
            icon = Icons.Default.RestartAlt,
            accentColor = PluviaTheme.colors.accentPurple,
            onClick = ::resetEffects,
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ScreenEffectsPanel(
    isVisible: Boolean,
    renderer: GLRenderer,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    container: Container? = null,
) {
    val initialConfig = remember(renderer, container) { loadScreenEffectsConfig(container) }
    val firstItemFocusRequester = remember { FocusRequester() }

    var brightness by remember(renderer, container) {
        mutableFloatStateOf(initialConfig.brightness)
    }
    var contrast by remember(renderer, container) {
        mutableFloatStateOf(initialConfig.contrast)
    }
    var gamma by remember(renderer, container) {
        mutableFloatStateOf(initialConfig.gamma)
    }
    var enableToon by remember(renderer, container) {
        mutableStateOf(initialConfig.enableToon)
    }
    var enableFXAA by remember(renderer, container) {
        mutableStateOf(initialConfig.enableFXAA)
    }
    var enableVivid by remember(renderer, container) {
        mutableStateOf(initialConfig.enableVivid)
    }
    var enableCRT by remember(renderer, container) {
        mutableStateOf(initialConfig.enableCRT)
    }
    var enableNTSC by remember(renderer, container) {
        mutableStateOf(initialConfig.enableNTSC)
    }

    LaunchedEffect(brightness, contrast, gamma, enableToon, enableFXAA, enableVivid, enableCRT, enableNTSC) {
        val config = ScreenEffectsConfig(
            brightness = brightness,
            contrast = contrast,
            gamma = gamma,
            enableToon = enableToon,
            enableFXAA = enableFXAA,
            enableVivid = enableVivid,
            enableCRT = enableCRT,
            enableNTSC = enableNTSC,
        )
        applyScreenEffectsConfig(renderer, config)
        delay(300)
        persistScreenEffectsConfig(container, config)
        container?.saveData()
    }

    fun resetEffects() {
        brightness = 0f
        contrast = 0f
        gamma = 1.0f
        enableToon = false
        enableFXAA = false
        enableVivid = false
        enableCRT = false
        enableNTSC = false
    }

    BackHandler(enabled = isVisible, onBack = onDismiss)

    LaunchedEffect(isVisible) {
        if (isVisible) {
            repeat(3) {
                try {
                    firstItemFocusRequester.requestFocus()
                    return@LaunchedEffect
                } catch (_: Exception) {
                    kotlinx.coroutines.delay(80)
                }
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ) + fadeIn(),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = spring(stiffness = Spring.StiffnessHigh),
            ) + fadeOut(),
            modifier = Modifier.fillMaxHeight(),
        ) {
            Surface(
                modifier = Modifier
                    .width(adaptivePanelWidth(420.dp))
                    .fillMaxHeight(),
            shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 24.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            ),
                        ),
                    )
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.screen_effects),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.screen_effects_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .focusGroup()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_BUTTON_B,
                                    KeyEvent.KEYCODE_BACK -> {
                                        onDismiss()
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                        .padding(vertical = 12.dp),
                ) {
                    OptionSectionHeader(text = stringResource(R.string.screen_effects_color_adjustments))

                    ScreenEffectAdjustmentRow(
                        title = stringResource(R.string.screen_effects_brightness),
                        valueText = formatPercent(brightness),
                        progress = normalizedProgress(brightness, -100f, 100f),
                        onDecrease = {
                            brightness = (brightness - SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
                        },
                        onIncrease = {
                            brightness = (brightness + SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
                        },
                        focusRequester = firstItemFocusRequester,
                    )
                    ScreenEffectAdjustmentRow(
                        title = stringResource(R.string.screen_effects_contrast),
                        valueText = formatPercent(contrast),
                        progress = normalizedProgress(contrast, -100f, 100f),
                        onDecrease = {
                            contrast = (contrast - SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
                        },
                        onIncrease = {
                            contrast = (contrast + SCREEN_EFFECT_PERCENT_STEP).coerceIn(-100f, 100f)
                        },
                    )
                    ScreenEffectAdjustmentRow(
                        title = stringResource(R.string.screen_effects_gamma),
                        valueText = String.format("%.2fx", gamma),
                        progress = normalizedProgress(gamma, 0.5f, 2.5f),
                        onDecrease = {
                            gamma = (gamma - SCREEN_EFFECT_GAMMA_STEP).coerceIn(0.5f, 2.5f)
                        },
                        onIncrease = {
                            gamma = (gamma + SCREEN_EFFECT_GAMMA_STEP).coerceIn(0.5f, 2.5f)
                        },
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    OptionSectionHeader(text = stringResource(R.string.screen_effects_shader_toggles))

                    ScreenEffectToggleRow(
                        title = stringResource(R.string.screen_effects_toon),
                        subtitle = stringResource(R.string.screen_effects_toon_description),
                        enabled = enableToon,
                        onToggle = { enableToon = !enableToon },
                    )
                    ScreenEffectToggleRow(
                        title = stringResource(R.string.screen_effects_fxaa),
                        subtitle = stringResource(R.string.screen_effects_fxaa_description),
                        enabled = enableFXAA,
                        onToggle = { enableFXAA = !enableFXAA },
                    )
                    ScreenEffectToggleRow(
                        title = stringResource(R.string.screen_effects_vivid),
                        subtitle = stringResource(R.string.screen_effects_vivid_description),
                        enabled = enableVivid,
                        onToggle = { enableVivid = !enableVivid },
                    )
                    ScreenEffectToggleRow(
                        title = stringResource(R.string.screen_effects_crt),
                        subtitle = stringResource(R.string.screen_effects_crt_description),
                        enabled = enableCRT,
                        onToggle = { enableCRT = !enableCRT },
                    )
                    ScreenEffectToggleRow(
                        title = stringResource(R.string.screen_effects_ntsc),
                        subtitle = stringResource(R.string.screen_effects_ntsc_description),
                        enabled = enableNTSC,
                        onToggle = { enableNTSC = !enableNTSC },
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    ScreenEffectActionRow(
                        title = stringResource(R.string.screen_effects_reset),
                        icon = Icons.Default.RestartAlt,
                        accentColor = PluviaTheme.colors.accentPurple,
                        onClick = ::resetEffects,
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
}

@Composable
private fun ScreenEffectAdjustmentRow(
    title: String,
    valueText: String,
    progress: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple
    val shape = RoundedCornerShape(14.dp)
    var isAdjustmentLocked by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused && !isAdjustmentLocked) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                if (!it.isFocused) {
                    isAdjustmentLocked = false
                }
            }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when {
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A -> {
                            isAdjustmentLocked = !isAdjustmentLocked
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_B -> {
                            isAdjustmentLocked = false
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onDecrease()
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onIncrease()
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isAdjustmentLocked) {
                    Text(
                        text = "●",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenEffectAdjustmentButton(
                text = "-",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onDecrease,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDecrease,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onIncrease,
                            ),
                    )
                }
            }

            ScreenEffectAdjustmentButton(
                text = "+",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onIncrease,
            )
        }
    }
}

@Composable
private fun ScreenEffectAdjustmentButton(
    text: String,
    rowIsFocused: Boolean,
    isAdjustmentLocked: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isAdjustmentLocked) {
                    accentColor.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (rowIsFocused) 0.32f else 0.45f)
                },
            )
            .border(
                width = if (isAdjustmentLocked) 2.dp else 1.dp,
                color = if (isAdjustmentLocked) {
                    accentColor.copy(alpha = 0.9f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isAdjustmentLocked) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScreenEffectToggleRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple

    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(contentAlignment = Alignment.CenterEnd) {
            ScreenEffectSwitch(enabled = enabled, accentColor = accentColor)
        }
    }
}

@Composable
private fun ScreenEffectActionRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = if (isFocused) 0.24f else 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(22.dp),
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun ScreenEffectSwitch(
    enabled: Boolean,
    accentColor: Color,
) {
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (enabled) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            )
            .border(
                width = 1.dp,
                color = if (enabled) accentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                .background(Color.White, CircleShape),
        )
    }
}

private fun normalizedProgress(
    value: Float,
    min: Float,
    max: Float,
): Float = ((value - min) / (max - min)).coerceIn(0f, 1f)

private fun formatPercent(value: Float): String {
    val rounded = value.toInt()
    return if (rounded > 0) "+${rounded}%" else "${rounded}%"
}
