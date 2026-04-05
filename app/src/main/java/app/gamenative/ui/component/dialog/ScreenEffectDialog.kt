package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsSwitch
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.effects.ColorEffect
import com.winlator.renderer.effects.CRTEffect
import com.winlator.renderer.effects.Effect
import com.winlator.renderer.effects.FXAAEffect
import com.winlator.renderer.effects.VividEffect
import com.winlator.renderer.effects.NTSCCombinedEffect
import com.winlator.renderer.effects.ToonEffect
import kotlin.math.abs

@Composable
fun ScreenEffectDialog(
    renderer: GLRenderer,
    onDismiss: () -> Unit,
) {
    val composer = renderer.effectComposer
    val initialColorEffect = composer.getEffect(ColorEffect::class.java)

    var brightness by remember(renderer) {
        mutableFloatStateOf((initialColorEffect?.brightness ?: 0f) * 100f)
    }
    var contrast by remember(renderer) {
        mutableFloatStateOf((initialColorEffect?.contrast ?: 0f) * 100f)
    }
    var gamma by remember(renderer) {
        mutableFloatStateOf(initialColorEffect?.gamma ?: 1.0f)
    }
    var enableToon by remember(renderer) {
        mutableStateOf(composer.getEffect(ToonEffect::class.java) != null)
    }
    var enableFXAA by remember(renderer) {
        mutableStateOf(composer.getEffect(FXAAEffect::class.java) != null)
    }
    var enableVivid by remember(renderer) {
        mutableStateOf(composer.getEffect(VividEffect::class.java) != null)
    }
    var enableCRT by remember(renderer) {
        mutableStateOf(composer.getEffect(CRTEffect::class.java) != null)
    }
    var enableNTSC by remember(renderer) {
        mutableStateOf(composer.getEffect(NTSCCombinedEffect::class.java) != null)
    }

    LaunchedEffect(brightness, contrast, gamma, enableToon, enableFXAA, enableVivid, enableCRT, enableNTSC) {
        val effects = mutableListOf<Effect>()

        if (abs(brightness) > 0.001f || abs(contrast) > 0.001f || abs(gamma - 1.0f) > 0.001f) {
            val colorEffect = ColorEffect()
            colorEffect.brightness = brightness / 100f
            colorEffect.contrast = contrast / 100f
            colorEffect.gamma = gamma
            effects += colorEffect
        }

        if (enableToon) {
            effects += composer.getEffect(ToonEffect::class.java) ?: ToonEffect()
        }
        if (enableFXAA) {
            effects += composer.getEffect(FXAAEffect::class.java) ?: FXAAEffect()
        }
        if (enableVivid) {
            effects += composer.getEffect(VividEffect::class.java) ?: VividEffect()
        }
        if (enableCRT) {
            effects += composer.getEffect(CRTEffect::class.java) ?: CRTEffect()
        }
        if (enableNTSC) {
            effects += composer.getEffect(NTSCCombinedEffect::class.java) ?: NTSCCombinedEffect()
        }

        composer.setEffects(effects)
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            shadowElevation = 24.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = PluviaTheme.colors.accentPink,
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.screen_effects),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = stringResource(R.string.screen_effects_live_preview),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.screen_effects_close),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.screen_effects_color_adjustments),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        ScreenEffectSlider(
                            label = stringResource(R.string.screen_effects_brightness),
                            valueText = formatPercent(brightness),
                            value = brightness,
                            valueRange = -100f..100f,
                            onValueChange = { brightness = it },
                        )
                        ScreenEffectSlider(
                            label = stringResource(R.string.screen_effects_contrast),
                            valueText = formatPercent(contrast),
                            value = contrast,
                            valueRange = -100f..100f,
                            onValueChange = { contrast = it },
                        )
                        ScreenEffectSlider(
                            label = stringResource(R.string.screen_effects_gamma),
                            valueText = String.format("%.2fx", gamma),
                            value = gamma,
                            valueRange = 0.5f..2.5f,
                            onValueChange = { gamma = it },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.screen_effects_shader_toggles),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        SettingsSwitch(
                            colors = settingsTileColorsAlt(),
                            title = { Text(stringResource(R.string.screen_effects_toon)) },
                            subtitle = { Text(stringResource(R.string.screen_effects_toon_description)) },
                            state = enableToon,
                            onCheckedChange = { enableToon = it },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitch(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.screen_effects_fxaa)) },
                            subtitle = { Text(stringResource(R.string.screen_effects_fxaa_description)) },
                            state = enableFXAA,
                            onCheckedChange = { enableFXAA = it },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitch(
                            colors = settingsTileColorsAlt(),
                            title = { Text(stringResource(R.string.screen_effects_vivid)) },
                            subtitle = { Text(stringResource(R.string.screen_effects_vivid_description)) },
                            state = enableVivid,
                            onCheckedChange = { enableVivid = it },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitch(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.screen_effects_crt)) },
                            subtitle = { Text(stringResource(R.string.screen_effects_crt_description)) },
                            state = enableCRT,
                            onCheckedChange = { enableCRT = it },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitch(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.screen_effects_ntsc)) },
                            subtitle = { Text(stringResource(R.string.screen_effects_ntsc_description)) },
                            state = enableNTSC,
                            onCheckedChange = { enableNTSC = it },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = ::resetEffects) {
                        Text(stringResource(R.string.screen_effects_reset))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.screen_effects_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenEffectSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(text = valueText, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
        )
    }
}

private fun formatPercent(value: Float): String {
    val rounded = value.toInt()
    return if (rounded > 0) "+${rounded}%" else "${rounded}%"
}
