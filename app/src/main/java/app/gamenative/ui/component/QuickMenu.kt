package app.gamenative.ui.component

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.powercontrol.PowerManager
import app.gamenative.ui.component.quickMenus.PowerControlQuickMenuTab
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudSize
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.adaptivePanelWidth
import app.gamenative.utils.MathUtils.normalizedProgress
import timber.log.Timber
import com.winlator.container.Container
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.VulkanRenderer
import com.winlator.winhandler.ProcessInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

object QuickMenuAction {
    const val KEYBOARD = 1
    const val INPUT_CONTROLS = 2
    const val EXIT_GAME = 3
    const val EDIT_CONTROLS = 4
    const val EDIT_PHYSICAL_CONTROLLER = 5
    const val PERFORMANCE_HUD = 6
    const val TOUCHSCREEN_MODE = 7
    const val DISABLE_MOUSE = 8
    const val SHOOTER_MODE = 9
}

private object QuickMenuTab {
    const val HUD = 0
    const val LSFG = 1
    const val EFFECTS = 2
    const val CONTROLLER = 3
    const val TOOLS = 4
    const val BFG = 5
    const val INVITE = 6
    const val POWER = 7
}

data class QuickMenuItem(
    val id: Int,
    val icon: ImageVector,
    val labelResId: Int,
    val accentColor: Color = Color.Unspecified,
    val enabled: Boolean = true,
)

private enum class PerformanceHudPreset(val labelResId: Int) {
    FPS_ONLY(R.string.performance_hud_preset_fps_only),
    ESSENTIAL(R.string.performance_hud_preset_essential),
    BATTERY(R.string.performance_hud_preset_battery),
    FULL(R.string.performance_hud_preset_full),
}

private fun applyPerformanceHudPreset(
    currentConfig: PerformanceHudConfig,
    preset: PerformanceHudPreset,
): PerformanceHudConfig {
    return when (preset) {
        PerformanceHudPreset.FPS_ONLY -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = false,
            showGpuUsage = false,
            showRamUsage = false,
            showBatteryLevel = false,
            showPowerDraw = false,
            showBatteryRuntime = false,
            showBatteryTemperature = false,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = false,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.ESSENTIAL -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = false,
            showPowerDraw = false,
            showBatteryRuntime = false,
            showBatteryTemperature = false,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = false,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.BATTERY -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = true,
            showPowerDraw = false,
            showBatteryRuntime = true,
            showBatteryTemperature = true,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = true,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.FULL -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = true,
            showPowerDraw = true,
            showBatteryRuntime = true,
            showBatteryTemperature = true,
            showClockTime = true,
            showCpuTemperature = true,
            showGpuTemperature = true,
            showFrameRateGraph = true,
            showCpuUsageGraph = true,
            showGpuUsageGraph = true,
        )
    }
}

private fun matchesPerformanceHudPreset(
    currentConfig: PerformanceHudConfig,
    preset: PerformanceHudPreset,
): Boolean {
    val presetConfig = applyPerformanceHudPreset(currentConfig, preset)
    return currentConfig.showFrameRate == presetConfig.showFrameRate &&
        currentConfig.showCpuUsage == presetConfig.showCpuUsage &&
        currentConfig.showGpuUsage == presetConfig.showGpuUsage &&
        currentConfig.showRamUsage == presetConfig.showRamUsage &&
        currentConfig.showBatteryLevel == presetConfig.showBatteryLevel &&
        currentConfig.showPowerDraw == presetConfig.showPowerDraw &&
        currentConfig.showBatteryRuntime == presetConfig.showBatteryRuntime &&
        currentConfig.showBatteryTemperature == presetConfig.showBatteryTemperature &&
        currentConfig.showClockTime == presetConfig.showClockTime &&
        currentConfig.showCpuTemperature == presetConfig.showCpuTemperature &&
        currentConfig.showGpuTemperature == presetConfig.showGpuTemperature &&
        currentConfig.showFrameRateGraph == presetConfig.showFrameRateGraph &&
        currentConfig.showCpuUsageGraph == presetConfig.showCpuUsageGraph &&
        currentConfig.showGpuUsageGraph == presetConfig.showGpuUsageGraph
}

// fpsLimiterSteps / fpsLimiterCurrentIndex / fpsLimiterProgress /
// nextFpsLimiterValue / previousFpsLimiterValue live in FpsLimiterUtils.kt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun QuickMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onHomeFromOverlay: () -> Unit = onDismiss,
    onItemSelected: (Int) -> Boolean,
    renderer: VulkanRenderer? = null,
    glRenderer: GLRenderer? = null,
    container: Container? = null,
    wineProcesses: List<ProcessInfo> = emptyList(),
    isWineProcessesLoading: Boolean = false,
    onToolsVisibilityChanged: (Boolean) -> Unit = {},
    onEndWineProcess: (ProcessInfo) -> Unit = {},
    isPerformanceHudEnabled: Boolean = false,
    performanceHudConfig: PerformanceHudConfig = PerformanceHudConfig(),
    fpsLimiterEnabled: Boolean = true,
    fpsLimiterTarget: Int = 60,
    fpsLimiterMax: Int = 60,
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit = {},
    onFpsLimiterEnabledChanged: (Boolean) -> Unit = {},
    onFpsLimiterChanged: (Int) -> Unit = {},
    hasPhysicalController: Boolean = false,
    isTouchscreenModeActive: Boolean = false,
    onTouchGestureSettingsClick: () -> Unit = {},
    isShooterModeActive: Boolean = false,
    onShooterModeSettingsClick: () -> Unit = {},
    activeToggleIds: Set<Int> = emptySet(),
    // LSFG hot-reload state (tab only visible when isLsfgAvailable)
    isLsfgAvailable: Boolean = false,
    lsfgMultiplier: Int = 2,
    lsfgFlowScale: Float = 0.80f,
    lsfgPerformanceMode: Boolean = true,
    onLsfgMultiplierChanged: (Int) -> Unit = {},
    onLsfgFlowScaleChanged: (Float) -> Unit = {},
    onLsfgPerformanceModeChanged: (Boolean) -> Unit = {},
    onAnimationComplete: (Boolean) -> Unit = {},
    /** Lets the menu open itself when the running game asks for its Steam invite dialog. */
    onRequestOpen: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val exitGameItem = QuickMenuItem(
        id = QuickMenuAction.EXIT_GAME,
        icon = Icons.AutoMirrored.Filled.ExitToApp,
        labelResId = R.string.exit_game,
        accentColor = PluviaTheme.colors.accentDanger,
    )

    val controllerItems = buildList {
        add(
            QuickMenuItem(
                id = QuickMenuAction.DISABLE_MOUSE,
                icon = Icons.Filled.Mouse,
                labelResId = R.string.disable_mouse_input,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.KEYBOARD,
                icon = Icons.Default.Keyboard,
                labelResId = R.string.keyboard,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.INPUT_CONTROLS,
                icon = Icons.Default.TouchApp,
                labelResId = R.string.input_controls,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        if (hasPhysicalController) {
            add(
                QuickMenuItem(
                    id = QuickMenuAction.EDIT_PHYSICAL_CONTROLLER,
                    icon = Icons.Default.Gamepad,
                    labelResId = R.string.edit_physical_controller,
                    accentColor = PluviaTheme.colors.accentPurple,
                )
            )
        }
        add(
            QuickMenuItem(
                id = QuickMenuAction.EDIT_CONTROLS,
                icon = Icons.Default.Edit,
                labelResId = R.string.edit_controls,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.TOUCHSCREEN_MODE,
                icon = Icons.Default.Fingerprint,
                labelResId = R.string.touchscreen_mode,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.SHOOTER_MODE,
                icon = Icons.Default.Gamepad,
                labelResId = R.string.shooter_mode_toggle,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
    }

    // Created here rather than plumbed through XServerScreen: that composable
    // sits at the dex verifier's register limit and any extra locals there
    // trip a VerifyError at class load (dex methods over 255 registers hit a
    // broken D8 codegen path).
    val bfgMenu = remember(container?.id) { container?.let { BfgMenuState.createIfAvailable(it) } }
    val inviteMenu = remember(container?.id) { SteamInviteState.createIfAvailable(container) }
    val isPowerControlAvailable = remember { PowerManager.isPServerAvailable() }

    var selectedTab by rememberSaveable {
        mutableIntStateOf(
            when {
                PrefManager.quickMenuLastTab == QuickMenuTab.LSFG && !isLsfgAvailable -> QuickMenuTab.HUD
                PrefManager.quickMenuLastTab == QuickMenuTab.BFG && bfgMenu == null -> QuickMenuTab.HUD
                PrefManager.quickMenuLastTab == QuickMenuTab.INVITE && inviteMenu == null -> QuickMenuTab.HUD
                PrefManager.quickMenuLastTab == QuickMenuTab.POWER && !isPowerControlAvailable -> QuickMenuTab.HUD
                else -> PrefManager.quickMenuLastTab
            }
        )
    }
    val selectedTabLabelResId = when (selectedTab) {
        QuickMenuTab.HUD -> R.string.performance_hud
        QuickMenuTab.LSFG -> R.string.lsfg_tab_title
        QuickMenuTab.BFG -> R.string.bfg_tab_title
        QuickMenuTab.EFFECTS -> R.string.screen_effects
        QuickMenuTab.TOOLS -> R.string.task_manager
        QuickMenuTab.INVITE -> R.string.steam_invite_tab_title
        QuickMenuTab.POWER -> R.string.power_control
        else -> R.string.quick_menu_tab_controller
    }

    val hudScrollState = rememberScrollState()
    val effectsScrollState = rememberScrollState()
    val lsfgScrollState = rememberScrollState()
    val effectsTabFocusRequester = remember { FocusRequester() }
    val controllerScrollState = rememberScrollState()
    val lsfgTabFocusRequester = remember { FocusRequester() }
    val hudTabFocusRequester = remember { FocusRequester() }
    val controllerTabFocusRequester = remember { FocusRequester() }
    val toolsTabFocusRequester = remember { FocusRequester() }
    val powerTabFocusRequester = remember { FocusRequester() }
    val hudItemFocusRequester = remember { FocusRequester() }
    val effectsItemFocusRequester = remember { FocusRequester() }
    val controllerItemFocusRequester = remember { FocusRequester() }
    val toolsItemFocusRequester = remember { FocusRequester() }
    val lsfgItemFocusRequester = remember { FocusRequester() }
    val bfgScrollState = rememberScrollState()
    val bfgTabFocusRequester = remember { FocusRequester() }
    val bfgItemFocusRequester = remember { FocusRequester() }
    val inviteTabFocusRequester = remember { FocusRequester() }
    val inviteItemFocusRequester = remember { FocusRequester() }
    val toolsScrollState = rememberScrollState()
    val inviteScrollState = rememberScrollState()
    val exitItemFocusRequester = remember { FocusRequester() }
    // P6 (spec 2026-08-12): the header close (X) becomes a real gamepad target — routed
    // explicitly from the rail/content tops via focusProperties (see routeToCloseButton).
    val closeButtonFocusRequester = remember { FocusRequester() }
    // G9 remember-selection: per-tab index of the last focused item, restored on reopen.
    var effectsFocusIndex by rememberSaveable { mutableIntStateOf(0) }
    var controllerFocusIndex by rememberSaveable { mutableIntStateOf(0) }
    var toolsFocusIndex by rememberSaveable { mutableIntStateOf(0) }
    var inviteFocusIndex by rememberSaveable { mutableIntStateOf(0) }

    // Full-screen shader browser: hoisted shader state shared with the effects tab, and
    // the open flag that swaps the menu content for the browser surface while open.
    val context = LocalContext.current
    val shaderSection = remember(renderer, container) {
        if (renderer != null) ShaderSectionState(renderer, container, context) else null
    }
    var shaderBrowserOpen by remember { mutableStateOf(false) }
    // Missão 2 (spec 2026-08-12): remembers that the browser was open so the close
    // transition can restore menu focus exactly once — the opening bootstrap must not
    // race a duplicate restore on the initial composition.
    var browserWasOpen by remember { mutableStateOf(false) }

    // The game's own "Invite friends" button reaches us as an engine callback the bionic host
    // captures. Open on the invite tab rather than drawing a separate panel, so controller focus
    // and back-to-dismiss behave like the rest of the menu.
    if (inviteMenu != null) {
        LaunchedEffect(inviteMenu) {
            while (true) {
                if (!isVisible && inviteMenu.consumeGameInviteRequest()) {
                    selectedTab = QuickMenuTab.INVITE
                    PrefManager.quickMenuLastTab = selectedTab
                    SteamInviteState.openedForGameRequest = SystemClock.uptimeMillis()
                    onRequestOpen()
                }
                delay(1000)
            }
        }
    }
    val powerItemFocusRequester = remember { FocusRequester() }

    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = isVisible

    LaunchedEffect(visibleState.currentState, visibleState.isIdle) {
        if (visibleState.isIdle) {
            onAnimationComplete(visibleState.currentState)
        }
    }

    // Track whether focus is on the tab rail (vs. tab content). Used by the hierarchical
    // back (D2): B with focus in content returns to the selected tab's button;
    // B with focus on the rail dismisses the menu.
    var railFocused by remember { mutableStateOf(false) }

    // RC2 focus guardian (spec 2026-08-10, §3.2): true while any node inside the menu
    // holds focus. If the focused row leaves the composition (shader toggle, collapse,
    // search clear, async preset load, cross-input tap), Compose 1.8 clears the focus and
    // this flips to false — the guardian re-bootstraps instead of leaving a dead menu.
    var menuHasFocus by remember { mutableStateOf(false) }

    // Coroutine scope for L2/R2 page scrolling / tab-switch focus (suspend funs).
    val quickMenuScope = rememberCoroutineScope()
    // Focus manager for the menu's own navigation (L2/R2 page-by-focus, tab-switch walk).
    val focusManager = LocalFocusManager.current
    // M5 (spec 2026-08-12 — C5): ONE mutex serializes every focus bootstrap (opening
    // effect, browser-close restore, guardian). Two concurrent walk-downs would double the
    // remembered-index offset and land the focus on the wrong row ("menu morto" feel).
    val focusMutex = remember { Mutex() }
    val density = LocalDensity.current

    // RetroArch/Ozone-inspired helpers (spec 2026-08-09): the ordered tab list for L1/R1
    // cycling and the per-tab scroll states for L2/R2 page scrolling.
    // Keyed on the availability flags: the renderer/menus may not exist on the first
    // composition (xServerView is created asynchronously), so a bare `remember` would
    // freeze the tab list without EFFECTS forever.
    // Upstream merge (PR #1698): POWER tab included so L1/R1 cycling matches the rail
    // order upstream renders (HUD, POWER, LSFG, ...) — the tab only exists when the
    // device exposes the power-control service (isPowerControlAvailable).
    val orderedTabs = remember(isLsfgAvailable, bfgMenu, inviteMenu, renderer, glRenderer, isPowerControlAvailable) {
        listOf(
            QuickMenuTab.HUD, QuickMenuTab.POWER, QuickMenuTab.LSFG, QuickMenuTab.BFG,
            QuickMenuTab.EFFECTS, QuickMenuTab.CONTROLLER, QuickMenuTab.TOOLS, QuickMenuTab.INVITE,
        ).filter { tab ->
            when (tab) {
                QuickMenuTab.LSFG -> isLsfgAvailable
                QuickMenuTab.BFG -> bfgMenu != null
                QuickMenuTab.INVITE -> inviteMenu != null
                QuickMenuTab.EFFECTS -> renderer != null || glRenderer != null
                QuickMenuTab.POWER -> isPowerControlAvailable
                else -> true
            }
        }
    }
    val currentTabScrollState: ScrollState? = when (selectedTab) {
        QuickMenuTab.HUD -> hudScrollState
        QuickMenuTab.LSFG -> lsfgScrollState
        QuickMenuTab.BFG -> bfgScrollState
        QuickMenuTab.EFFECTS -> effectsScrollState
        QuickMenuTab.CONTROLLER -> controllerScrollState
        QuickMenuTab.TOOLS -> toolsScrollState
        QuickMenuTab.INVITE -> inviteScrollState
        else -> null
    }

    /** Focuses [tab]'s content list (first row, then walk-down to the remembered index). */
    suspend fun focusTabContentOrRail(tab: Int) {
        val itemRequester = when (tab) {
            QuickMenuTab.HUD -> hudItemFocusRequester
            QuickMenuTab.LSFG -> lsfgItemFocusRequester
            QuickMenuTab.BFG -> bfgItemFocusRequester
            QuickMenuTab.EFFECTS -> effectsItemFocusRequester
            QuickMenuTab.TOOLS -> toolsItemFocusRequester
            QuickMenuTab.INVITE -> inviteItemFocusRequester
            QuickMenuTab.POWER -> powerItemFocusRequester
            else -> controllerItemFocusRequester
        }
        var landed = false
        repeat(3) {
            runCatching { itemRequester.requestFocus() }.getOrDefault(false).let { ok ->
                if (ok) {
                    landed = true
                    return@repeat
                }
            }
            withFrameNanos { }
            if (menuHasFocus) {
                landed = true
                return@repeat
            }
            delay(60)
        }
        if (landed) {
            // G9 remember-selection: walk down to the tab's last focused row.
            val remembered = when (tab) {
                QuickMenuTab.EFFECTS -> effectsFocusIndex
                QuickMenuTab.CONTROLLER -> controllerFocusIndex
                QuickMenuTab.TOOLS -> toolsFocusIndex
                QuickMenuTab.INVITE -> inviteFocusIndex
                else -> 0
            }
            repeat(remembered) { focusManager.moveFocus(FocusDirection.Down) }
            return
        }
        // Tab with no focusable content — fall back to its rail button.
        val railRequester = when (tab) {
            QuickMenuTab.HUD -> hudTabFocusRequester
            QuickMenuTab.LSFG -> lsfgTabFocusRequester
            QuickMenuTab.BFG -> bfgTabFocusRequester
            QuickMenuTab.EFFECTS -> effectsTabFocusRequester
            QuickMenuTab.TOOLS -> toolsTabFocusRequester
            QuickMenuTab.INVITE -> inviteTabFocusRequester
            QuickMenuTab.POWER -> powerTabFocusRequester
            else -> controllerTabFocusRequester
        }
        runCatching { railRequester.requestFocus() }
        railFocused = true
    }

    /**
     * P5 (spec 2026-08-12): L2/R2 page the active tab's list by MOVING FOCUS instead of
     * pure scrollBy — the selection follows the scroll, so a later DPAD move can never
     * jump to a row outside the viewport (Compose auto-scrolls the focused row into
     * view). From the rail (always visible) the legacy pure scroll is kept.
     */
    fun pageTabList(delta: Int) {
        val state: ScrollState? = currentTabScrollState
        if (state == null) return
        if (railFocused) {
            quickMenuScope.launch {
                state.scrollBy(delta * (state.viewportSize / 2).coerceAtLeast(240).toFloat())
            }
            return
        }
        val rowPx = with(density) { 56.dp.toPx() }
        val halfViewportPx = (state.viewportSize / 2).coerceAtLeast(240).toFloat()
        val steps = (halfViewportPx / rowPx).coerceAtLeast(1f).toInt()
        val direction = if (delta > 0) FocusDirection.Down else FocusDirection.Up
        repeat(steps) { focusManager.moveFocus(direction) }
    }

    /** P6: routes Up from the rail/content tops to the header close (X) button. */
    fun routeToCloseButton(): Boolean =
        runCatching { closeButtonFocusRequester.requestFocus() }.getOrDefault(false)

    fun selectAdjacentTab(delta: Int) {
        val idx = orderedTabs.indexOf(selectedTab)
        Timber.d("QuickMenu: selectAdjacentTab(delta=%d) idx=%d tabs=%s", delta, idx, orderedTabs)
        if (idx < 0) return
        val next = orderedTabs[(idx + delta + orderedTabs.size) % orderedTabs.size]
        selectedTab = next
        PrefManager.quickMenuLastTab = next
        // P4 (spec 2026-08-12): tab switch keeps the focus INSIDE the content (console /
        // Ozone pattern — one press less per switch). The new tab's list is focused at
        // its remembered row; the rail button is only the fallback when the tab has no
        // focusable content (e.g. TOOLS with no processes).
        railFocused = false
        quickMenuScope.launch {
            withFrameNanos { } // let the new tab's content compose first
            focusTabContentOrRail(next)
        }
    }


    // One lambda for both back paths (parity gamepad/touch): the physical BackHandler
    // (OnBackPressedDispatcher) and the raw gamepad B (gamepadBackHandler on the root box).
    // The paths are disjoint by construction: raw B never reaches the dispatcher and the
    // physical BACK never reaches Compose (spec 2026-08-09, §3.1).
    val backAction: () -> Unit = {
        if (railFocused) {
            onDismiss()
        } else {
            // Hierarchical back: content -> selected tab button.
            val requester = when (selectedTab) {
                QuickMenuTab.HUD -> hudTabFocusRequester
                QuickMenuTab.LSFG -> lsfgTabFocusRequester
                QuickMenuTab.BFG -> bfgTabFocusRequester
                QuickMenuTab.EFFECTS -> effectsTabFocusRequester
                QuickMenuTab.TOOLS -> toolsTabFocusRequester
                QuickMenuTab.INVITE -> inviteTabFocusRequester
                QuickMenuTab.POWER -> powerTabFocusRequester
                else -> controllerTabFocusRequester
            }
            requester.requestFocus()
            railFocused = true
        }
    }
    BackHandler(enabled = isVisible, onBack = backAction)

    Box(
        modifier = modifier
            .fillMaxSize()
            .gamepadBackHandler(backAction)
            .onFocusChanged {
                if (menuHasFocus != it.hasFocus) {
                    menuHasFocus = it.hasFocus
                    Timber.d("QuickMenu root focus: %b", it.hasFocus)
                }
            }
            .onPreviewKeyEvent { keyEvent ->
                // RetroArch/Ozone-style fast navigation (spec 2026-08-09): L1/R1 switch tabs,
                // L2/R2 page-scroll the active tab's list. Preview phase runs before the
                // focused node, so this works wherever the focus is.
                if (keyEvent.type == KeyEventType.KeyDown && isVisible && !shaderBrowserOpen) {
                    Timber.d("QuickMenu: root preview key=%d", keyEvent.nativeKeyEvent.keyCode)
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_L1 -> {
                            // P2 (spec 2026-08-12): tab switch is a SINGLE action — the
                            // Android key repeat (hold L1/R1 ~20/s) must not cycle tabs.
                            if (keyEvent.nativeKeyEvent.repeatCount == 0) selectAdjacentTab(-1)
                            true
                        }
                        KeyEvent.KEYCODE_BUTTON_R1 -> {
                            if (keyEvent.nativeKeyEvent.repeatCount == 0) selectAdjacentTab(+1)
                            true
                        }
                        // L2/R2 keep repeating: continuous paging is the desired behavior.
                        KeyEvent.KEYCODE_BUTTON_L2 -> {
                            pageTabList(-1)
                            true
                        }
                        KeyEvent.KEYCODE_BUTTON_R2 -> {
                            pageTabList(+1)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
    ) {
        if (shaderBrowserOpen && shaderSection != null) {
            // Full-screen shader browser: replaces the menu content (no focusable rows
            // behind it), owns the gamepad scope while open (the menu navigator/bridge
            // below are not composed), and closes via B/back; Home closes everything
            // through onHome (spec 2026-08-13-home-button-overlay-exit, M1).
            ShaderBrowserOverlay(
                state = shaderSection,
                onClose = { shaderBrowserOpen = false },
                onCloseQuickMenu = onDismiss,
                onHome = onHomeFromOverlay,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        // Gamepad stick/hat axis -> Compose focus navigation. Bus-level (LibraryScreen
        // pattern): the QuickMenu shares the game window with the GL surface, where
        // view-level generic-motion listeners are unreliable (spec 2026-08-09, §2.1).
        BusJoystickFocusNavigator(enabled = isVisible)
        // Gamepad A/B/L1/R1/L2/R2 -> Compose, delivered directly to this ComposeView and
        // consumed so the game never sees them while the overlay is open. PS (Mode key)
        // routes through onHomeFromOverlay (spec 2026-08-10, §3.5 — G6): PS opens the
        // menu via PhysicalControllerHandler when closed; when open, it closes it (and
        // with the Home-straight-to-game option, resumes the game — M2).
        BusGamepadKeyBridge(
            enabled = isVisible && !shaderBrowserOpen,
            modeKeyBehavior = ModeKeyBehavior.CloseOverlay,
            onCloseOverlay = onHomeFromOverlay,
        )

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0f))
                    // NOT clickable: a clickable scrim is focusable and can swallow invisible
                    // focus (P1-4) — taps only.
                    .pointerInput(Unit) {
                        detectTapGestures { onDismiss() }
                    },
            )
        }

        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(200),
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(150),
            ),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Surface(
                modifier = Modifier
                    .width(adaptivePanelWidth(400.dp))
                    .fillMaxHeight(),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
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
                            text = stringResource(R.string.quick_menu_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        QuickMenuCloseButton(
                            onClick = onDismiss,
                            focusRequester = closeButtonFocusRequester,
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .fillMaxHeight()
                                .focusGroup()
                                // P6: Up from the rail (top) exits to the header close (X)
                                // — explicit route, independent of focus-group geometry.
                                .focusProperties {
                                    up = closeButtonFocusRequester
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val tabScrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(tabScrollState)
                                    .onFocusChanged { railFocused = it.hasFocus },
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                QuickMenuTabButton(
                                    icon = Icons.Default.QueryStats,
                                    contentDescriptionResId = R.string.performance_hud,
                                    selected = selectedTab == QuickMenuTab.HUD,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.HUD
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = hudTabFocusRequester,
                                )
                                if (isPowerControlAvailable) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.BatteryChargingFull,
                                        contentDescriptionResId = R.string.power_control,
                                        selected = selectedTab == QuickMenuTab.POWER,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.POWER
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = powerTabFocusRequester,
                                    )
                                }
                                if (isLsfgAvailable) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.Speed,
                                        contentDescriptionResId = R.string.lsfg_tab_title,
                                        selected = selectedTab == QuickMenuTab.LSFG,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.LSFG
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = lsfgTabFocusRequester,
                                    )
                                }
                                if (bfgMenu != null) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.Speed,
                                        contentDescriptionResId = R.string.bfg_tab_title,
                                        selected = selectedTab == QuickMenuTab.BFG,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.BFG
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = bfgTabFocusRequester,
                                    )
                                }
                                if (inviteMenu != null) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.PersonAdd,
                                        contentDescriptionResId = R.string.steam_invite_tab_title,
                                        selected = selectedTab == QuickMenuTab.INVITE,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.INVITE
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = inviteTabFocusRequester,
                                    )
                                }
                                if (renderer != null || glRenderer != null) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.AutoFixHigh,
                                        contentDescriptionResId = R.string.screen_effects,
                                        selected = selectedTab == QuickMenuTab.EFFECTS,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.EFFECTS
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = effectsTabFocusRequester,
                                    )
                                }
                                QuickMenuTabButton(
                                    icon = Icons.Default.Gamepad,
                                    contentDescriptionResId = R.string.quick_menu_tab_controller,
                                    selected = selectedTab == QuickMenuTab.CONTROLLER,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.CONTROLLER
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = controllerTabFocusRequester,
                                )
                                QuickMenuTabButton(
                                    icon = Icons.Default.BarChart,
                                    contentDescriptionResId = R.string.task_manager,
                                    selected = selectedTab == QuickMenuTab.TOOLS,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.TOOLS
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = toolsTabFocusRequester,
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 12.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                            )

                            QuickMenuRailActionButton(
                                item = exitGameItem,
                                onClick = {
                                    if (onItemSelected(QuickMenuAction.EXIT_GAME)) {
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.width(56.dp),
                                focusRequester = exitItemFocusRequester,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize(),
                        ) {
                            Text(
                                text = stringResource(selectedTabLabelResId),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    // P6: Up from the content's first row reaches the
                                    // header close (X) button.
                                    .focusProperties {
                                        up = closeButtonFocusRequester
                                    },
                            ) {
                                when (selectedTab) {
                                    QuickMenuTab.HUD -> {
                                        PerformanceHudQuickMenuTab(
                                            isPerformanceHudEnabled = isPerformanceHudEnabled,
                                            performanceHudConfig = performanceHudConfig,
                                            fpsLimiterEnabled = fpsLimiterEnabled,
                                            fpsLimiterTarget = fpsLimiterTarget,
                                            fpsLimiterMax = fpsLimiterMax,
                                            lsfgMultiplier = if (isLsfgAvailable) lsfgMultiplier else 0,
                                            onTogglePerformanceHud = {
                                                onItemSelected(QuickMenuAction.PERFORMANCE_HUD)
                                            },
                                            onPerformanceHudConfigChanged = onPerformanceHudConfigChanged,
                                            onFpsLimiterEnabledChanged = onFpsLimiterEnabledChanged,
                                            onFpsLimiterChanged = onFpsLimiterChanged,
                                            scrollState = hudScrollState,
                                            focusRequester = hudItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.LSFG -> {
                                        LsfgQuickMenuTab(
                                            multiplier = lsfgMultiplier,
                                            flowScale = lsfgFlowScale,
                                            performanceMode = lsfgPerformanceMode,
                                            onMultiplierChanged = onLsfgMultiplierChanged,
                                            onFlowScaleChanged = onLsfgFlowScaleChanged,
                                            onPerformanceModeChanged = onLsfgPerformanceModeChanged,
                                            scrollState = lsfgScrollState,
                                            focusRequester = lsfgItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.BFG -> {
                                        if (bfgMenu != null) {
                                            BionicFgQuickMenuTab(
                                                multiplier = bfgMenu.multiplier,
                                                flowScale = bfgMenu.flowScale,
                                                model = bfgMenu.model,
                                                onMultiplierChanged = bfgMenu::applyMultiplier,
                                                onFlowScaleChanged = bfgMenu::applyFlowScale,
                                                onModelChanged = bfgMenu::applyModel,
                                                scrollState = bfgScrollState,
                                                focusRequester = bfgItemFocusRequester,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }

                                    QuickMenuTab.INVITE -> {
                                        if (inviteMenu != null) {
                                            SteamInviteQuickMenuTab(
                                                state = inviteMenu,
                                                focusRequester = inviteItemFocusRequester,
                                                initialFocusIndex = inviteFocusIndex,
                                                onFocusIndexChanged = { inviteFocusIndex = it },
                                                scrollState = inviteScrollState,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }

                                    QuickMenuTab.EFFECTS -> {
                                        if (renderer != null) {
                                            ScreenEffectsTabContent(
                                                renderer = renderer,
                                                container = container,
                                                modifier = Modifier.fillMaxSize(),
                                                firstItemFocusRequester = effectsItemFocusRequester,
                                                initialFocusIndex = effectsFocusIndex,
                                                onFocusIndexChanged = { effectsFocusIndex = it },
                                                scrollState = effectsScrollState,
                                                shaderSection = shaderSection,
                                                onOpenShaderBrowser = { shaderBrowserOpen = true },
                                            )
                                        } else if (glRenderer != null) {
                                            GLScreenEffectsTabContent(
                                                renderer = glRenderer,
                                                container = container,
                                                modifier = Modifier.fillMaxSize(),
                                                firstItemFocusRequester = effectsItemFocusRequester,
                                                initialFocusIndex = effectsFocusIndex,
                                                onFocusIndexChanged = { effectsFocusIndex = it },
                                                scrollState = effectsScrollState,
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 8.dp, vertical = 16.dp),
                                                contentAlignment = Alignment.TopStart,
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.main_loading),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }

                                    QuickMenuTab.POWER -> {
                                        PowerControlQuickMenuTab(
                                            focusRequester = powerItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.TOOLS -> {
                                        ToolsQuickMenuTab(
                                            processes = wineProcesses,
                                            isLoadingProcesses = isWineProcessesLoading,
                                            onEndProcess = onEndWineProcess,
                                            firstItemFocusRequester = toolsItemFocusRequester,
                                            initialFocusIndex = toolsFocusIndex,
                                            onFocusIndexChanged = { toolsFocusIndex = it },
                                            scrollState = toolsScrollState,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    else -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(controllerScrollState)
                                                .focusGroup(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            val clampedControllerIndex = controllerFocusIndex
                                                .coerceIn(0, controllerItems.lastIndex)
                                            controllerItems.forEachIndexed { index, item ->
                                                QuickMenuItemRow(
                                                    item = item,
                                                    isActive = item.id in activeToggleIds,
                                                    onClick = {
                                                        if (onItemSelected(item.id)) {
                                                            onDismiss()
                                                        }
                                                    },
                                                    focusRequester = if (index == clampedControllerIndex) controllerItemFocusRequester else null,
                                                    focusIndex = index,
                                                    onFocusIndexChanged = { controllerFocusIndex = it },
                                                    secondaryIcon = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        Icons.Default.Settings
                                                    else if (item.id == QuickMenuAction.SHOOTER_MODE && isShooterModeActive)
                                                        Icons.Default.Settings
                                                    else null,
                                                    secondaryContentDescriptionResId = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        R.string.gesture_settings_title
                                                    else if (item.id == QuickMenuAction.SHOOTER_MODE && isShooterModeActive)
                                                        R.string.shooter_mode_settings_title
                                                    else null,
                                                    onSecondaryClick = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        onTouchGestureSettingsClick
                                                    else if (item.id == QuickMenuAction.SHOOTER_MODE && isShooterModeActive)
                                                        onShooterModeSettingsClick
                                                    else null,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // RetroArch/Ozone footer hints (spec 2026-08-09): contextual gamepad
                        // actions at the bottom of the sidebar (localized; shown only with a
                        // gamepad connected, per shouldShowGamepadUI).
                        GamepadActionBar(
                            actions = listOf(
                                GamepadAction(GamepadButton.A, R.string.action_select),
                                GamepadAction(GamepadButton.B, R.string.action_back),
                                GamepadAction(GamepadButton.LB, R.string.action_switch_tab),
                                GamepadAction(GamepadButton.RB, R.string.action_switch_tab),
                                GamepadAction(GamepadButton.LT, R.string.action_page_scroll),
                                GamepadAction(GamepadButton.RT, R.string.action_page_scroll),
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp),
                            visible = isVisible,
                        )
                    }
                }
            }
        }
        }
    }

    LaunchedEffect(isVisible, selectedTab) {
        onToolsVisibilityChanged(isVisible && selectedTab == QuickMenuTab.TOOLS)
    }

    // RC2/RC3 (spec 2026-08-10, §3.2/§3.3): the ONE focus bootstrap, shared by the opening
    // effect and the focus guardian (composition instead of duplication). For EFFECTS the
    // walk-down waits one frame (withFrameNanos) so it walks from the focus that actually
    // landed, not stale/absent focus (RC3 race: the old code walked the same frame the
    // request was issued, so the remembered index landed on the wrong row).
    suspend fun requestMenuFocus() {
        // M5 (spec 2026-08-12 — C5): every bootstrap goes through the SAME mutex, so two
        // walk-downs can never run concurrently (opening effect x guardian x browser-close
        // restore would double the effectsFocusIndex offset and land focus on the wrong row).
        Timber.d("QuickMenu bootstrap: mutex enter (tab=%d)", selectedTab)
        focusMutex.withLock {
            // UX practice (focus hygiene): clear any stale active focus target FIRST.
            // Compose 1.8 silently drops a requestFocus when the previously active node
            // cannot be cleared (the dead-menu accumulation: 1st open works, every reopen
            // after a close can die). A forced clear makes each bootstrap deterministic.
            focusManager.clearFocus(true)
            // Focus that lands through this bootstrap (walk-down on open, guardian restores) is
            // programmatic, not user intent on the target row — the search field relies on this
            // stamp to suppress the soft keyboard (spec 2026-08-10-search-field-ime-explicit-design).
            GamepadNavigationClock.lastMoveAt = SystemClock.uptimeMillis()
            repeat(3) {
                try {
                    val hadFocus = menuHasFocus
                    val focusRequested = when (selectedTab) {
                        QuickMenuTab.HUD -> hudItemFocusRequester.requestFocus()
                        QuickMenuTab.LSFG -> lsfgItemFocusRequester.requestFocus()
                        QuickMenuTab.BFG -> bfgItemFocusRequester.requestFocus()
                        QuickMenuTab.INVITE -> inviteItemFocusRequester.requestFocus()
                        // Upstream merge (PR #1698): upstream's original focus bootstrap was a
                        // plain LaunchedEffect { repeat(3) { when (selectedTab) { ... } } }.
                        // This fork replaced it with requestMenuFocus() (gamepad hardening,
                        // spec 2026-08-12). The merge keeps the fork's version and only
                        // absorbs upstream's new POWER tab case below — no upstream behavior
                        // was removed, and the fork's bootstrap logic is unchanged.
                        QuickMenuTab.EFFECTS -> effectsItemFocusRequester.requestFocus()
                        QuickMenuTab.POWER -> powerItemFocusRequester.requestFocus()
                        QuickMenuTab.TOOLS -> toolsItemFocusRequester.requestFocus()
                        else -> controllerItemFocusRequester.requestFocus()
                    }
                    // Wait one frame for the request to actually land (RC3: the walk must
                    // never act on stale/absent focus).
                    withFrameNanos { }
                    // Instrumentation (evidence, logcat 2026-08-13): false here means the
                    // requester's node is missing/not focusable (tab content did not
                    // compose); true-but-not-landed means Compose dropped the request
                    // (stale-target drop). Distinguishing the two decides the fix.
                    Timber.d(
                        "QuickMenu bootstrap: requestFocus(tab=%d)=%b landed=%b",
                        selectedTab, focusRequested, menuHasFocus,
                    )
                    if (hadFocus || menuHasFocus) {
                        // Focus is in the menu. Only walk when THIS request landed it — a
                        // pre-existing focus (fast reopen during the exit animation) must
                        // not be displaced.
                        if (selectedTab == QuickMenuTab.EFFECTS && !hadFocus) {
                            // G9 remember-selection: walk down to the last focused row
                            // (moveFocus clamps naturally at the last focusable).
                            repeat(effectsFocusIndex) {
                                focusManager.moveFocus(FocusDirection.Down)
                            }
                        }
                        Timber.d("QuickMenu bootstrap: focus landed (tab=%d)", selectedTab)
                        return@withLock
                    }
                    // The request was silently dropped (Compose can drop requests while the
                    // enter transition / IME settle — the dead-menu symptom). Retry.
                    Timber.d("QuickMenu bootstrap: request did not land, retry %d", it)
                    delay(60)
                } catch (error: Exception) {
                    Timber.w(error, "QuickMenu bootstrap: focus retry (tab=%d)", selectedTab)
                    delay(80)
                }
            }
            // Content focus failed entirely (e.g. TOOLS with no wine processes, or a focus
            // system that keeps dropping requests) — fall back to the rail so the menu is
            // never born dead (the original bug: menu opened with no focus anywhere, so the
            // joystick did nothing). From the rail the stick can still navigate (Right
            // enters the content, Down/Up cycle the rail).
            try {
                val railRequested = hudTabFocusRequester.requestFocus()
                withFrameNanos { }
                Timber.d(
                    "QuickMenu bootstrap: rail requestFocus=%b landed=%b",
                    railRequested, menuHasFocus,
                )
                if (menuHasFocus) {
                    railFocused = true
                    Timber.d("QuickMenu bootstrap: fallback to rail focus")
                } else {
                    Timber.w("QuickMenu bootstrap: rail fallback also failed")
                }
            } catch (_: Exception) {
            }
        }
        Timber.d("QuickMenu bootstrap: mutex exit (tab=%d)", selectedTab)
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            requestMenuFocus()
        } else {
            // Defensive reset (spec 2026-08-12, Missão 3): a menu closed by ANY path while
            // the browser was still open must not reopen into the browser — the reopen
            // lands on the last tab (EFFECTS). The browser flag is scoped to the menu, so
            // the guard also clears the stale was-open latch.
            shaderBrowserOpen = false
            browserWasOpen = false
            // UX practice (focus hygiene): a close must leave a clean slate. If a stale
            // active focus target survives the exit (e.g. a text field with an open IME
            // connection), the next open's requestFocus can be silently dropped by the
            // focus system. Clearing here makes every reopen deterministic.
            focusManager.clearFocus(true)
        }
    }

    // Missão 2 (spec 2026-08-12): closing the browser recomposes the menu content; restore
    // focus immediately (requestMenuFocus walks down to the remembered row) instead of
    // waiting up to 400 ms for the guardian. The latch skips the initial composition, so
    // this never races the opening bootstrap (two concurrent walk-downs would double the
    // effectsFocusIndex offset).
    LaunchedEffect(shaderBrowserOpen) {
        if (shaderBrowserOpen) {
            browserWasOpen = true
        } else if (browserWasOpen && isVisible) {
            browserWasOpen = false
            requestMenuFocus()
        }
    }

    // RC2 focus guardian (spec 2026-08-10, §3.2), continuous: whenever the menu is
    // visible but no node inside it holds focus — the focused row was removed from the
    // composition by a shader toggle ("No filter"), a category collapse, clearing the
    // search, an async preset load, or a cross-input tap — restore it. This is a LOOP,
    // not a one-shot: a single failed restore must never leave the menu permanently dead
    // (the "abri o menu e não consegui mexer nada" symptom). requestMenuFocus() itself
    // verifies that the focus actually landed and falls back to the rail.
    // Missão 1 (spec 2026-08-12): the guardian must not run while the browser is open —
    // the menu content is not composed then, so its requesters are gone; the loop would
    // only spam logs and race the browser's own focus bootstrap. It resumes the moment
    // the browser closes.
    LaunchedEffect(isVisible, shaderBrowserOpen) {
        if (isVisible && !shaderBrowserOpen) {
            // Let the opening bootstrap land first (never fight it).
            delay(150)
            while (isVisible && !shaderBrowserOpen) {
                if (!menuHasFocus) {
                    // M5 (spec 2026-08-12): gentle guardian — while the user is actively
                    // navigating (a move < 600 ms ago), skip the cycle. requestMenuFocus()
                    // starts with clearFocus(true), which must never land mid-gesture.
                    val now = SystemClock.uptimeMillis()
                    if (now - GamepadNavigationClock.lastMoveAt < 600L) {
                        Timber.d("QuickMenu guardian: user navigating, skipping cycle")
                    } else {
                        Timber.d("QuickMenu guardian: restoring focus (tab=%d)", selectedTab)
                        requestMenuFocus()
                    }
                }
                delay(400)
            }
        }
    }
}

@Composable
private fun SteamInviteQuickMenuTab(
    state: SteamInviteState,
    focusRequester: FocusRequester? = null,
    initialFocusIndex: Int = 0,
    onFocusIndexChanged: (Int) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    val scope = rememberCoroutineScope()

    // Keep polling while the tab is up: a friend joining our lobby is the only signal Steam
    // gives us that an invite was acted on, so the rows would otherwise go stale.
    LaunchedEffect(Unit) {
        state.refresh()
        while (true) {
            delay(3000)
            state.refreshQuietly()
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickMenuSectionHeader(
            title = when {
                state.isLoading -> stringResource(R.string.main_loading)
                state.hostUnavailable -> stringResource(R.string.steam_invite_unavailable)
                else -> stringResource(R.string.steam_invite_header, state.friends.size)
            },
            subtitle = state.lastError?.let { stringResource(it) },
        )

        // G10 (spec 2026-08-10, §3.7): an empty friend list explains itself instead of
        // silently falling back to rail focus. It must ALSO stay focusable: with no rows the
        // menu would be born dead on the INVITE tab, so the empty/unavailable states render a
        // retry row that owns the tab's focus requester (spec 2026-08-11 quickmenu-invite-regression).
        if (state.friends.isEmpty() && !state.isLoading) {
            QuickMenuDetailRow(
                title = stringResource(
                    if (state.hostUnavailable) R.string.steam_invite_unavailable
                    else R.string.steam_invite_no_friends
                ),
                subtitle = stringResource(R.string.steam_invite_retry),
                accentColor = accentColor,
                onActivate = { scope.launch { state.refresh() } },
                focusRequester = focusRequester,
                focusIndex = 0,
                onFocusIndexChanged = onFocusIndexChanged,
            )
        }

        val clampedInviteIndex = if (state.friends.isEmpty()) {
            0
        } else {
            initialFocusIndex.coerceIn(0, state.friends.lastIndex)
        }
        state.friends.forEachIndexed { index, friend ->
            QuickMenuDetailRow(
                title = friend.name,
                subtitle = stringResource(
                    when {
                        friend.inOurLobby -> R.string.steam_invite_joined
                        friend.isJoinable -> R.string.steam_invite_joinable
                        friend.steamId in state.inviteSent -> R.string.steam_invite_sent
                        friend.isOnline -> R.string.steam_invite_online
                        else -> R.string.steam_invite_offline
                    },
                ),
                accentColor = accentColor,
                onActivate = {
                    scope.launch {
                        // Someone already in this game gets joined, not invited.
                        if (friend.isJoinable) state.join(friend) else state.invite(friend.steamId)
                    }
                },
                focusRequester = if (index == clampedInviteIndex) focusRequester else null,
                focusIndex = index,
                onFocusIndexChanged = onFocusIndexChanged,
            )
        }
    }
}

@Composable
private fun ToolsQuickMenuTab(
    processes: List<ProcessInfo>,
    isLoadingProcesses: Boolean,
    onEndProcess: (ProcessInfo) -> Unit,
    firstItemFocusRequester: FocusRequester? = null,
    initialFocusIndex: Int = 0,
    onFocusIndexChanged: (Int) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickMenuSectionHeader(
            title = if (isLoadingProcesses) {
                stringResource(R.string.main_loading)
            } else {
                stringResource(R.string.tools_wine_processes_running_hint, processes.size)
            },
        )

        if (!isLoadingProcesses && processes.isEmpty()) {
            Text(
                text = stringResource(R.string.tools_wine_processes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            val clampedToolsIndex = if (processes.isEmpty()) {
            0
        } else {
            initialFocusIndex.coerceIn(0, processes.lastIndex)
        }
            processes.forEachIndexed { index, process ->
                QuickMenuDetailRow(
                    title = process.name + if (process.wow64Process) {
                        stringResource(R.string.quick_menu_wine_32bit_suffix)
                    } else {
                        ""
                    },
                    subtitle = process.formattedMemoryUsage,
                    accentColor = accentColor,
                    onActivate = {
                        onEndProcess(process)
                    },
                    focusRequester = if (index == clampedToolsIndex) firstItemFocusRequester else null,
                    focusIndex = index,
                    onFocusIndexChanged = onFocusIndexChanged,
                )
            }
        }
    }
}

@Composable
private fun PerformanceHudQuickMenuTab(
    isPerformanceHudEnabled: Boolean,
    performanceHudConfig: PerformanceHudConfig,
    fpsLimiterEnabled: Boolean,
    fpsLimiterTarget: Int,
    fpsLimiterMax: Int,
    lsfgMultiplier: Int,
    onTogglePerformanceHud: () -> Unit,
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit,
    onFpsLimiterEnabledChanged: (Boolean) -> Unit,
    onFpsLimiterChanged: (Int) -> Unit,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── FPS Limiter (topmost) ────────────────────────────────────────
        val limiterControlledByLsfg = lsfgMultiplier >= 2
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_fps_limiter),
            subtitle = if (limiterControlledByLsfg) {
                stringResource(R.string.performance_hud_fps_limiter_lsfg_override)
            } else null,
            enabled = fpsLimiterEnabled && !limiterControlledByLsfg,
            onToggle = {
                if (!limiterControlledByLsfg) onFpsLimiterEnabledChanged(!fpsLimiterEnabled)
            },
            accentColor = accentColor,
            focusRequester = focusRequester,
            // G5: under LSFG the row cannot act — drop it from focus traversal (no trap).
            interactionEnabled = !limiterControlledByLsfg,
        )

        AnimatedVisibility(
            visible = fpsLimiterEnabled && !limiterControlledByLsfg,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.performance_hud_fps_limiter_target),
                    valueText = stringResource(
                        R.string.performance_hud_fps_limiter_value,
                        fpsLimiterTarget,
                    ),
                    progress = fpsLimiterProgress(fpsLimiterTarget, fpsLimiterMax),
                    onDecrease = {
                        onFpsLimiterChanged(previousFpsLimiterValue(fpsLimiterTarget, fpsLimiterMax))
                    },
                    onIncrease = {
                        onFpsLimiterChanged(nextFpsLimiterValue(fpsLimiterTarget, fpsLimiterMax))
                    },
                    accentColor = accentColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Performance HUD ──────────────────────────────────────────────
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud),
            subtitle = stringResource(R.string.performance_hud_description),
            enabled = isPerformanceHudEnabled,
            onToggle = onTogglePerformanceHud,
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_presets),
        )

        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PerformanceHudPreset.values().forEach { preset ->
                QuickMenuChoiceChip(
                    text = stringResource(preset.labelResId),
                    selected = matchesPerformanceHudPreset(performanceHudConfig, preset),
                    accentColor = accentColor,
                    onClick = {
                        onPerformanceHudConfigChanged(applyPerformanceHudPreset(performanceHudConfig, preset))
                        if (!isPerformanceHudEnabled) {
                            onTogglePerformanceHud()
                        }
                    },
                    modifier = Modifier.width(56.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_appearance),
        )

        Text(
            text = stringResource(R.string.performance_hud_size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                PerformanceHudSize.SMALL to R.string.performance_hud_size_small,
                PerformanceHudSize.MEDIUM to R.string.performance_hud_size_medium,
                PerformanceHudSize.LARGE to R.string.performance_hud_size_large,
            ).forEach { (size, labelResId) ->
                QuickMenuChoiceChip(
                    text = stringResource(labelResId),
                    selected = performanceHudConfig.size == size,
                    accentColor = accentColor,
                    onClick = {
                        onPerformanceHudConfigChanged(performanceHudConfig.copy(size = size))
                    },
                    modifier = Modifier.width(56.dp),
                )
            }
        }

        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_background_opacity),
            valueText = stringResource(
                R.string.performance_hud_percentage_value,
                (performanceHudConfig.backgroundOpacity * 100f).roundToInt(),
            ),
            progress = normalizedProgress(performanceHudConfig.backgroundOpacity, 0f, 1f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        backgroundOpacity = (performanceHudConfig.backgroundOpacity - 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        backgroundOpacity = (performanceHudConfig.backgroundOpacity + 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            accentColor = accentColor,
        )

        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_color_intensity),
            valueText = stringResource(
                R.string.performance_hud_percentage_value,
                (performanceHudConfig.colorIntensity * 100f).roundToInt(),
            ),
            progress = normalizedProgress(performanceHudConfig.colorIntensity, 0f, 1f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        colorIntensity = (performanceHudConfig.colorIntensity - 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        colorIntensity = (performanceHudConfig.colorIntensity + 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            accentColor = accentColor,
        )

        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_text_outline),
            enabled = performanceHudConfig.showTextOutline,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showTextOutline = !performanceHudConfig.showTextOutline),
                )
            },
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_metrics),
        )

        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_frame_rate),
            enabled = performanceHudConfig.showFrameRate,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showFrameRate = !performanceHudConfig.showFrameRate),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_frame_rate_graph),
            enabled = performanceHudConfig.showFrameRateGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showFrameRateGraph = !performanceHudConfig.showFrameRateGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_usage),
            enabled = performanceHudConfig.showCpuUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuUsage = !performanceHudConfig.showCpuUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_usage_graph),
            enabled = performanceHudConfig.showCpuUsageGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuUsageGraph = !performanceHudConfig.showCpuUsageGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_usage),
            enabled = performanceHudConfig.showGpuUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuUsage = !performanceHudConfig.showGpuUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_usage_graph),
            enabled = performanceHudConfig.showGpuUsageGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuUsageGraph = !performanceHudConfig.showGpuUsageGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_ram_usage),
            enabled = performanceHudConfig.showRamUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showRamUsage = !performanceHudConfig.showRamUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_battery_level),
            enabled = performanceHudConfig.showBatteryLevel,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryLevel = !performanceHudConfig.showBatteryLevel),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_power_draw),
            enabled = performanceHudConfig.showPowerDraw,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showPowerDraw = !performanceHudConfig.showPowerDraw),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_runtime_left),
            enabled = performanceHudConfig.showBatteryRuntime,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryRuntime = !performanceHudConfig.showBatteryRuntime),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_battery_temperature),
            enabled = performanceHudConfig.showBatteryTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryTemperature = !performanceHudConfig.showBatteryTemperature),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_clock_time),
            enabled = performanceHudConfig.showClockTime,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showClockTime = !performanceHudConfig.showClockTime),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_temperature),
            enabled = performanceHudConfig.showCpuTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuTemperature = !performanceHudConfig.showCpuTemperature),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_temperature),
            enabled = performanceHudConfig.showGpuTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuTemperature = !performanceHudConfig.showGpuTemperature),
                )
            },
            accentColor = accentColor,
        )
        if (PowerManager.isFanControlAvailable()) {
            var showFan by remember { mutableStateOf(PrefManager.showPerformanceHudFan) }
            QuickMenuToggleRow(
                title = stringResource(R.string.power_control_hud_show_fan),
                enabled = showFan,
                onToggle = {
                    showFan = !showFan
                    PrefManager.showPerformanceHudFan = showFan
                },
                accentColor = accentColor,
            )
        }
        if (PowerManager.isClusterTuningAvailable()) {
            var showTunerCaps by remember { mutableStateOf(PrefManager.showPerformanceHudTunerCaps) }
            QuickMenuToggleRow(
                title = stringResource(R.string.power_control_hud_show_tuner),
                enabled = showTunerCaps,
                onToggle = {
                    showTunerCaps = !showTunerCaps
                    PrefManager.showPerformanceHudTunerCaps = showTunerCaps
                },
                accentColor = accentColor,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun LsfgQuickMenuTab(
    multiplier: Int,
    flowScale: Float,
    performanceMode: Boolean,
    onMultiplierChanged: (Int) -> Unit,
    onFlowScaleChanged: (Float) -> Unit,
    onPerformanceModeChanged: (Boolean) -> Unit,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    val isEnabled = multiplier >= 2

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Multiplier (Off / 2x / 3x / 4x) ───────────────────────────────
        QuickMenuSectionHeader(
            title = stringResource(R.string.lsfg_multiplier),
        )
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0, 2, 3, 4).forEach { value ->
                QuickMenuChoiceChip(
                    text = if (value == 0) stringResource(R.string.quick_menu_off) else stringResource(R.string.quick_menu_x_multiplier, value),
                    selected = multiplier == value || (value == 0 && multiplier < 2),
                    accentColor = accentColor,
                    onClick = { onMultiplierChanged(value) },
                    modifier = Modifier.width(56.dp),
                    focusRequester = if (value == 0) focusRequester else null,
                )
            }
        }

        AnimatedVisibility(
            visible = isEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(modifier = Modifier.height(4.dp))

                // ── Flow Scale ────────────────────────────────────────────
                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.lsfg_flow_scale),
                    subtitle = stringResource(R.string.lsfg_flow_scale_desc),
                    valueText = String.format(java.util.Locale.US, "%.2f", flowScale),
                    progress = (flowScale - 0.25f) / 0.75f, // 0.25..1.0 → 0..1
                    onDecrease = {
                        val next = (flowScale - 0.05f).coerceIn(0.25f, 1.0f)
                        onFlowScaleChanged(String.format(java.util.Locale.US, "%.2f", next).toFloat())
                    },
                    onIncrease = {
                        val next = (flowScale + 0.05f).coerceIn(0.25f, 1.0f)
                        onFlowScaleChanged(String.format(java.util.Locale.US, "%.2f", next).toFloat())
                    },
                    accentColor = accentColor,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ── Performance Mode ──────────────────────────────────────
                QuickMenuToggleRow(
                    title = stringResource(R.string.lsfg_performance_mode),
                    subtitle = stringResource(R.string.lsfg_performance_mode_desc),
                    enabled = performanceMode,
                    onToggle = { onPerformanceModeChanged(!performanceMode) },
                    accentColor = accentColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun BionicFgQuickMenuTab(
    multiplier: Int,
    flowScale: Float,
    model: Int,
    onMultiplierChanged: (Int) -> Unit,
    onFlowScaleChanged: (Float) -> Unit,
    onModelChanged: (Int) -> Unit,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    val isEnabled = multiplier >= 2

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Multiplier (Off / 2x / 3x / 4x) ───────────────────────────────
        QuickMenuSectionHeader(
            title = stringResource(R.string.bfg_multiplier),
        )
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0, 2, 3, 4).forEach { value ->
                QuickMenuChoiceChip(
                    text = if (value == 0) stringResource(R.string.quick_menu_off) else stringResource(R.string.quick_menu_x_multiplier, value),
                    selected = multiplier == value || (value == 0 && multiplier < 2),
                    accentColor = accentColor,
                    onClick = { onMultiplierChanged(value) },
                    modifier = Modifier.width(56.dp),
                    focusRequester = if (value == 0) focusRequester else null,
                )
            }
        }

        AnimatedVisibility(
            visible = isEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(modifier = Modifier.height(4.dp))

                // ── Flow Scale ────────────────────────────────────────────
                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.bfg_flow_scale),
                    subtitle = stringResource(R.string.bfg_flow_scale_desc),
                    valueText = String.format(java.util.Locale.US, "%.2f", flowScale),
                    progress = (flowScale - 0.2f) / 0.8f, // 0.2..1.0 → 0..1
                    onDecrease = {
                        val next = (flowScale - 0.05f).coerceIn(0.2f, 1.0f)
                        onFlowScaleChanged(String.format(java.util.Locale.US, "%.2f", next).toFloat())
                    },
                    onIncrease = {
                        val next = (flowScale + 0.05f).coerceIn(0.2f, 1.0f)
                        onFlowScaleChanged(String.format(java.util.Locale.US, "%.2f", next).toFloat())
                    },
                    accentColor = accentColor,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ── Model (Standard / Clear) ──────────────────────────────
                QuickMenuSectionHeader(
                    title = stringResource(R.string.bfg_model),
                    subtitle = stringResource(R.string.bfg_model_desc),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickMenuChoiceChip(
                        text = stringResource(R.string.bfg_model_standard),
                        selected = model == 0,
                        accentColor = accentColor,
                        onClick = { onModelChanged(0) },
                        modifier = Modifier.width(88.dp),
                    )
                    QuickMenuChoiceChip(
                        text = stringResource(R.string.bfg_model_clear),
                        selected = model == 1,
                        accentColor = accentColor,
                        onClick = { onModelChanged(1) },
                        modifier = Modifier.width(88.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun QuickMenuSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickMenuCloseButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .size(44.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .gamepadSelectable(
                selected = false,
                onClick = onClick,
                shape = shape,
                interactionSource = interactionSource,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.quick_menu_back),
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuTabButton(
    icon: ImageVector,
    contentDescriptionResId: Int,
    selected: Boolean,
    accentColor: Color,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.18f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .gamepadSelectable(
                selected = selected,
                onClick = onSelected,
                shape = shape,
                interactionSource = interactionSource,
                accentColor = accentColor,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescriptionResId),
            tint = when {
                selected || isFocused -> accentColor
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuRailActionButton(
    item: QuickMenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.error
    }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(56.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                shape = shape,
            )
            .clip(shape)
            .background(
                if (isFocused) {
                    accentColor.copy(alpha = 0.18f)
                } else {
                    accentColor.copy(alpha = 0.08f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .gamepadSelectable(
                selected = false,
                onClick = onClick,
                shape = shape,
                interactionSource = interactionSource,
                accentColor = accentColor,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = stringResource(item.labelResId),
            tint = if (isFocused) accentColor else accentColor.copy(alpha = 0.9f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuChoiceChip(
    text: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .height(44.dp)
            .border(
                width = 1.dp,
                color = if (selected) accentColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                shape = shape,
            )
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.18f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .gamepadSelectable(
                selected = selected,
                onClick = onClick,
                shape = shape,
                interactionSource = interactionSource,
                accentColor = accentColor,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected || isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
internal fun QuickMenuAdjustmentRow(
    title: String,
    valueText: String,
    progress: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    subtitle: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    var isAdjustmentLocked by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
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
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .gamepadAdjustableRow(
                locked = isAdjustmentLocked,
                onLockChange = { isAdjustmentLocked = it },
                onAdjust = { delta -> if (delta < 0) onDecrease() else onIncrease() },
                shape = shape,
                interactionSource = interactionSource,
                accentColor = accentColor,
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
                        text = stringResource(R.string.quick_menu_locked_indicator),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                    )
                }
            }
        }

        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickMenuAdjustmentButton(
                text = stringResource(R.string.quick_menu_minus),
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
                    // Touch-only halves (spec 2026-08-10, §3.4 — G3): not Compose-focusable,
                    // so the row keeps ONE focus node and the walk-down counts one stop.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .focusProperties { canFocus = false }
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
                            .focusProperties { canFocus = false }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onIncrease,
                            ),
                    )
                }
            }

            QuickMenuAdjustmentButton(
                text = stringResource(R.string.quick_menu_plus),
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onIncrease,
            )
        }
    }
}

@Composable
private fun QuickMenuAdjustmentButton(
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
            .focusProperties { canFocus = false }
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
internal fun QuickMenuToggleRow(
    title: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    focusRequester: FocusRequester? = null,
    /** Row-level availability (spec 2026-08-10, §3.4 — G5): a row that cannot act (e.g.
     * FPS Limiter under LSFG) is not focusable — no focus trap where A does nothing.
     * Distinct from [enabled], which is the switch state (an OFF toggle must stay
     * focusable so the user can turn it ON). */
    interactionEnabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
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
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .gamepadSelectable(
                // Selection = the switch state (toggle ligado), never focus (spec D7).
                selected = enabled,
                onClick = onToggle,
                enabled = interactionEnabled,
                shape = RoundedCornerShape(14.dp),
                interactionSource = interactionSource,
                accentColor = accentColor,
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

        QuickMenuSwitch(
            enabled = enabled,
            accentColor = accentColor,
        )
    }
}

@Composable
private fun QuickMenuSwitch(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickMenuDetailRow(
    title: String,
    subtitle: String,
    accentColor: Color,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    focusIndex: Int? = null,
    onFocusIndexChanged: ((Int) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.14f),
                            accentColor.copy(alpha = 0.06f),
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
                        color = accentColor.copy(alpha = 0.8f),
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .then(
                if (focusIndex != null && onFocusIndexChanged != null) {
                    Modifier.gamepadFocusIndex(focusIndex, onFocusIndexChanged)
                } else {
                    Modifier
                }
            )
            .gamepadSelectable(
                selected = false,
                onClick = onActivate,
                shape = shape,
                interactionSource = interactionSource,
                accentColor = accentColor,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused) accentColor.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickMenuItemRow(
    item: QuickMenuItem,
    isActive: Boolean = false,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    focusIndex: Int? = null,
    onFocusIndexChanged: ((Int) -> Unit)? = null,
    secondaryIcon: ImageVector? = null,
    secondaryContentDescriptionResId: Int? = null,
    onSecondaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isEnabled = item.enabled

    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.primary
    }

    val disabledAlpha = 0.4f
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (isFocused && isEnabled) {
                    Modifier.background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.15f),
                                accentColor.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .then(
                if (focusIndex != null && onFocusIndexChanged != null) {
                    Modifier.gamepadFocusIndex(focusIndex, onFocusIndexChanged)
                } else {
                    Modifier
                }
            )
            .gamepadSelectable(
                // Selection = the item's active state (toggle ligado), never focus (spec D7).
                selected = isActive,
                onClick = onClick,
                enabled = isEnabled,
                shape = shape,
                interactionSource = interactionSource,
                accentColor = accentColor,
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (isActive) {
                        Modifier.border(BorderStroke(2.dp, accentColor), CircleShape)
                    } else Modifier
                )
                .clip(CircleShape)
                .background(
                    when {
                        !isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        isFocused || isActive -> accentColor.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = when {
                    !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
                    isFocused || isActive -> accentColor
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }

        Text(
            text = stringResource(item.labelResId),
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                isFocused -> accentColor
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )

        if (secondaryIcon != null && onSecondaryClick != null) {
            // G8 (spec 2026-08-10, §3.7): the settings gear is its own focusable node with
            // the shared gamepad focus language (ring + tracking) — not a plain clickable
            // that only "worked" by accident via synthetic DPAD_CENTER.
            val secondaryInteractionSource = remember { MutableInteractionSource() }
            val isSecondaryFocused by secondaryInteractionSource.collectIsFocusedAsState()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSecondaryFocused) {
                            accentColor.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                    )
                    .gamepadSelectable(
                        selected = false,
                        onClick = onSecondaryClick,
                        shape = CircleShape,
                        interactionSource = secondaryInteractionSource,
                        accentColor = accentColor,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = secondaryIcon,
                    contentDescription = stringResource(
                        secondaryContentDescriptionResId ?: R.string.gesture_settings_title,
                    ),
                    tint = if (isSecondaryFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun Preview_QuickMenu() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickMenu(
                isVisible = true,
                onDismiss = {},
                onItemSelected = { false },
                hasPhysicalController = false,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun Preview_QuickMenu_WithController() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickMenu(
                isVisible = true,
                onDismiss = {},
                onItemSelected = { false },
                hasPhysicalController = true,
            )
        }
    }
}
