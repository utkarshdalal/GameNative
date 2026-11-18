package app.gamenative.ui.component.quickMenus

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.display.GameNativeDrm
import app.gamenative.display.GamescopeCommandQueue
import app.gamenative.display.GamescopeFeature
import app.gamenative.display.GamescopeLook
import app.gamenative.display.GamescopeScalingFilter
import app.gamenative.display.GamescopeScalingMode
import app.gamenative.display.GamescopeSettings
import app.gamenative.ui.component.QuickMenuAdjustmentRow
import app.gamenative.ui.component.QuickMenuChoiceChip
import app.gamenative.ui.component.QuickMenuSectionHeader
import app.gamenative.ui.component.QuickMenuTickSlider
import app.gamenative.ui.component.QuickMenuToggleRow
import app.gamenative.ui.component.SelectorMenuItem
import app.gamenative.ui.component.ScreenEffectToggleRow
import app.gamenative.ui.component.SelectorRow
import app.gamenative.ui.theme.PluviaTheme
import com.winlator.container.Container
import kotlinx.coroutines.delay
import java.io.File

/** Bitmask of feature_support entries from "features=0x<hex>;..."; 0 when gamescope has not answered. */
private fun gamescopeFeatureMask(displayInfo: String): Int {
    val entry = displayInfo.split(';').firstOrNull { it.startsWith("features=") } ?: return 0
    return entry.substringAfter('=').removePrefix("0x").toIntOrNull(16) ?: 0
}

/** Persist one gamescope tab selection so it is shown again the next time the tab opens. */
private fun persistGamescopeExtra(container: Container?, key: String, value: String) {
    if (container == null) return
    container.putExtra(key, value)
    container.saveData()
}

/** One effect the manifest offers: the path gamescope resolves, and the name to show for it. */
private data class ReshadeEffect(val path: String, val name: String)

/** The effects `all_shaders.json` lists, in manifest order; empty until the launcher stages it. */
private fun reshadeEffects(context: android.content.Context): List<ReshadeEffect> {
    val manifest = File(context.filesDir, "reshade/all_shaders.json")
    if (!manifest.isFile) return emptyList()
    return try {
        val entries = org.json.JSONArray(manifest.readText())
        (0 until entries.length()).mapNotNull { i ->
            val entry = entries.optJSONObject(i) ?: return@mapNotNull null
            val path = entry.optString("path")
            val name = entry.optString("name", path)
            if (path.isEmpty()) null else ReshadeEffect(path, name)
        }
    }
    catch (e: org.json.JSONException) {
        emptyList()
    }
}

/** The five filters SteamOS itself offers, in SteamOS's own order. */
private val STEAMOS_SCALING_FILTERS = listOf(
    GamescopeScalingFilter.LINEAR,
    GamescopeScalingFilter.NEAREST,
    GamescopeScalingFilter.PIXEL,
    GamescopeScalingFilter.FSR,
    GamescopeScalingFilter.NIS,
)

@Composable
fun GamescopeQuickMenuTab(
    container: Container?,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    // Gamescope answers feature_support / active_display_info after the injector
    // connects, so poll the cached snapshot while this tab is open.
    var displayInfo by remember { mutableStateOf(GameNativeDrm.nativeDisplayInfo()) }
    LaunchedEffect(Unit) {
        while (true) {
            displayInfo = GameNativeDrm.nativeDisplayInfo()
            delay(1000)
        }
    }
    val featureMask = gamescopeFeatureMask(displayInfo)

    // Gamescope reports no current value for these settings, so the tab shows the value last stored
    // for the container.
    var filter by remember(container?.id) {
        mutableStateOf(
            GamescopeScalingFilter.fromId(container?.getExtra(GamescopeSettings.EXTRA_FILTER, ""))
                ?: GamescopeScalingFilter.LINEAR,
        )
    }
    var sharpness by remember(container?.id) {
        mutableIntStateOf(
            container?.getExtra(GamescopeSettings.EXTRA_SHARPNESS, GamescopeSettings.SHARPNESS_DEFAULT.toString())
                ?.toIntOrNull()?.coerceIn(0, GamescopeSettings.SHARPNESS_STEPS) ?: GamescopeSettings.SHARPNESS_DEFAULT,
        )
    }
    var look by remember(container?.id) {
        mutableStateOf(
            container?.getExtra(GamescopeSettings.EXTRA_LOOK, "").orEmpty()
                .takeIf { it.isNotEmpty() }?.let(GamescopeLook::fromId),
        )
    }
    var scalingMode by remember(container?.id) {
        mutableStateOf(
            GamescopeScalingMode.fromId(container?.getExtra(GamescopeSettings.EXTRA_SCALER, ""))
                ?: GamescopeScalingMode.DEFAULT,
        )
    }
    var lookExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val reshadeAvailable = remember { reshadeEffects(context) }
    var reshade by remember(container?.id) {
        mutableStateOf(container?.getExtra(GamescopeSettings.EXTRA_RESHADE, "").orEmpty())
    }

    fun applyFilter(entry: GamescopeScalingFilter) {
        filter = entry
        persistGamescopeExtra(container, GamescopeSettings.EXTRA_FILTER, entry.id)
        GamescopeCommandQueue.setFilter(entry)
    }

    /** Persist the launch-time `--scaler` value and apply the new mode live. */
    fun applyScalingMode(entry: GamescopeScalingMode) {
        scalingMode = entry
        persistGamescopeExtra(container, GamescopeSettings.EXTRA_SCALER, entry.id)
        GamescopeCommandQueue.setScaler(entry)
    }

    /** [ui] is the row's own value (0..SHARPNESS_STEPS, higher = sharper), stored as-is. */
    fun applySharpness(ui: Int) {
        val value = ui.coerceIn(0, GamescopeSettings.SHARPNESS_STEPS)
        sharpness = value
        persistGamescopeExtra(container, GamescopeSettings.EXTRA_SHARPNESS, value.toString())
        GamescopeCommandQueue.setSharpness(value)
    }

    /** An empty path turns ReShade off; otherwise the effect is loaded and enabled. */
    fun applyReshade(path: String) {
        reshade = path
        persistGamescopeExtra(container, GamescopeSettings.EXTRA_RESHADE, path)
        if (path.isEmpty()) GameNativeDrm.nativeReshadeDisable()
        else GameNativeDrm.nativeReshadeSetEffect(path)
    }

    fun resetToDefault() {
        applyFilter(GamescopeScalingFilter.LINEAR)
        applySharpness(GamescopeSettings.SHARPNESS_DEFAULT)
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Scaling Mode ────────────────────────────────────────────────── gamescope's scaler.
        QuickMenuTickSlider(
            title = stringResource(R.string.gamescope_scaling_mode),
            labels = GamescopeScalingMode.entries.map { stringResource(it.labelRes) },
            selectedIndex = GamescopeScalingMode.entries.indexOf(scalingMode).coerceAtLeast(0),
            accentColor = accentColor,
            onSelect = { applyScalingMode(GamescopeScalingMode.entries[it]) },
            focusRequester = focusRequester,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Scaling Filter ────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        // SteamOS parity is about presentation, not the feature set: the five SteamOS filters keep their
        // order so those rows read identically, and our extra one is appended after them so it is visibly
        // an addition.
        val filters = STEAMOS_SCALING_FILTERS + listOfNotNull(
            GamescopeScalingFilter.SGSR.takeIf {
                GamescopeFeature.advertised(featureMask, GamescopeFeature.SGSR_FILTER)
            },
        )
        QuickMenuTickSlider(
            title = stringResource(R.string.gamescope_scaling_filter),
            labels = filters.map { stringResource(it.labelRes) },
            selectedIndex = filters.indexOf(filter).coerceAtLeast(0),
            accentColor = accentColor,
            onSelect = { applyFilter(filters[it]) },
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Sharpness (only the filters that read it) ─────────────────────
        if (filter.usesSharpness) {
            Spacer(modifier = Modifier.height(8.dp))
            QuickMenuAdjustmentRow(
                title = stringResource(R.string.gamescope_scaling_sharpness),
                valueText = sharpness.toString(),
                progress = sharpness / GamescopeSettings.SHARPNESS_STEPS.toFloat(),
                onDecrease = { applySharpness(sharpness - 1) },
                onIncrease = { applySharpness(sharpness + 1) },
                accentColor = accentColor,
            )
        }

        // ── Reset to Default ──────────────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        QuickMenuChoiceChip(
            text = stringResource(R.string.gamescope_reset_default),
            selected = false,
            accentColor = accentColor,
            onClick = { resetToDefault() },
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .fillMaxWidth(),
        )

        // ── Color Effects (gated on the advertised look feature) ──────────
        if (GamescopeFeature.advertised(featureMask, GamescopeFeature.LOOK)) {
            Spacer(modifier = Modifier.height(8.dp))
            QuickMenuSectionHeader(title = stringResource(R.string.gamescope_look))
            // A container that never touched the setting stores nothing, which
            // displays as OFF; selecting OFF stores "" and reads back to null.
            val currentLook = look ?: GamescopeLook.OFF
            SelectorRow(
                valueText = stringResource(currentLook.labelRes),
                accentColor = accentColor,
                expanded = lookExpanded,
                onExpandedChange = { lookExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) { menuFocusRequester ->
                GamescopeLook.entries.forEach { entry ->
                    SelectorMenuItem(
                        accentColor = accentColor,
                        focusRequester = if (entry == currentLook) menuFocusRequester else null,
                        onClick = {
                            lookExpanded = false
                            look = entry
                            persistGamescopeExtra(container, GamescopeSettings.EXTRA_LOOK, entry.id)
                            GamescopeCommandQueue.setLook(entry)
                        },
                        text = {
                            Text(
                                text = stringResource(entry.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                }
            }
        }

        // ── ReShade (effects bundled with the app) ────────────────────────
        // gamescope runs one effect at a time, so the rows behave as a radio group: turning one on
        // turns the others off, and turning the active one off leaves ReShade disabled.
        if (reshadeAvailable.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            QuickMenuSectionHeader(title = stringResource(R.string.gamescope_reshade))
            reshadeAvailable.forEach { effect ->
                ScreenEffectToggleRow(
                    title = effect.name,
                    enabled = effect.path == reshade,
                    onToggle = { applyReshade(if (effect.path == reshade) "" else effect.path) },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}
