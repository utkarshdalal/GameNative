package app.gamenative.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.shaders.ApplyPresetResult
import app.gamenative.shaders.PackPrechecks
import app.gamenative.shaders.ShaderDoubleClickLogic
import app.gamenative.shaders.ShaderPagingLogic
import app.gamenative.shaders.ShaderPreset
import app.gamenative.shaders.ShaderPresetCost
import app.gamenative.shaders.friendlyName
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.theme.PluviaTheme
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.Locale

/**
 * Full-screen shader browser (opened from the QuickMenu effects tab).
 *
 * Design (Jony Ive lens — "less, on demand"): the whole libretro/slang-shaders catalog is
 * browsable instantly from the 599 KB manifest, but NO shader file ships in the APK. The
 * UI never renders a full family: presets are paginated (12/page + "Show more") and search
 * is global. Clicking a cloud preset downloads ONLY its dependency closure (user decision
 * 2026-08-12) and auto-applies it when the files finish caching.
 *
 * Gamepad: the overlay installs its own bus navigator + key bridge (the QuickMenu's are
 * gated off while it is open), B/back walks a manual back-stack, PS closes the browser
 * (not the menu), and every row participates in the focus-index protocol with
 * per-screen remember-selection.
 */
sealed interface ShaderBrowserScreen {
    data object Home : ShaderBrowserScreen
    data class Family(val name: String, val subfolder: String? = null) : ShaderBrowserScreen

    fun key(): String = when (this) {
        Home -> "home"
        is Family -> if (subfolder == null) "family:$name" else "family:$name:$subfolder"
    }
}

/** Pure back-stack for the browser (JVM-testable). */
class ShaderBrowserNav {
    private val stack = ArrayDeque<ShaderBrowserScreen>().apply { addLast(ShaderBrowserScreen.Home) }
    val current: ShaderBrowserScreen get() = stack.last()
    val atRoot: Boolean get() = stack.size == 1
    val size: Int get() = stack.size

    fun push(screen: ShaderBrowserScreen) {
        if (stack.last() != screen) stack.addLast(screen)
    }

    /** @return true when a screen was popped; false when already at root. */
    fun pop(): Boolean = if (stack.size > 1) {
        stack.removeLast()
        true
    } else {
        false
    }
}

/** Curated family order for the home list; unknown families sort after, alphabetically. */
// bezel (1.490 presets — screen bezels, not image effects) is deliberately LAST: most
// users of this app (PC/Steam games via Wine) will never use it (spec §5.3).
private val FAMILY_ORDER = listOf(
    "crt", "handheld", "ntsc", "interpolation", "misc", "hdr", "pal", "scanlines",
    "film", "cel", "pixel-art-scaling", "sharpen", "anti-aliasing", "edge-smoothing",
    "motionblur", "vhs", "deblur", "denoisers", "dithering", "blurs", "deinterlacing",
    "downsample", "warp", "stereoscopic-3d", "anamorphic", "linear", "motion-interpolation",
    "subframe-bfi", "gpu", "nes_raw_palette", "presets", "root", "bezel",
)

/** Known family label resources; anything else falls back to title-casing the raw name. */
private val FAMILY_LABEL_RES = mapOf(
    "crt" to R.string.shader_cat_crt,
    "lcd" to R.string.shader_cat_lcd,
    "interpolation" to R.string.shader_cat_interpolation,
    "misc" to R.string.shader_cat_misc,
    "film" to R.string.shader_cat_film,
    "cel" to R.string.shader_cat_cel,
    "hdr" to R.string.shader_cat_hdr,
    "ntsc" to R.string.shader_cat_ntsc,
    "reshade" to R.string.shader_cat_reshade,
    "nearest" to R.string.shader_cat_nearest,
    "bilinear" to R.string.shader_cat_bilinear,
    "stock" to R.string.shader_cat_stock,
    "root" to R.string.shader_cat_root,
)

@Composable
fun friendlyFamilyName(family: String): String {
    val resId = FAMILY_LABEL_RES[family]
    if (resId != null) return stringResource(resId)
    return family.split('_', '-')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { part -> part.replaceFirstChar { c -> c.titlecase() } }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000f)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000f)
    else -> "$bytes B"
}

private const val PAGE_SIZE = 12

/**
 * Full-screen browser surface. Only composed while open (the QuickMenu replaces its
 * content with this when [ShaderBrowserOverlay] is shown), so it needs no `visible` flag
 * and installs its own gamepad scope without competing with the menu's.
 */
@Composable
fun ShaderBrowserOverlay(
    state: ShaderSectionState,
    onClose: () -> Unit,
    onCloseQuickMenu: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalog = state.catalog
    if (catalog == null) {
        // Manifest missing — should never happen (it ships in assets).
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.shader_catalog_missing))
        }
        return
    }

    // Own gamepad scope: the QuickMenu's navigator/bridge are gated off while open.
    // Home (PS/Guide) closes EVERYTHING (browser + menu) through [onHome]
    // (spec 2026-08-13-home-button-overlay-exit, M1) — B/back stays hierarchical.
    BusJoystickFocusNavigator(enabled = true)
    BusGamepadKeyBridge(
        enabled = true,
        modeKeyBehavior = ModeKeyBehavior.CloseOverlay,
        onCloseOverlay = onHome,
    )

    // Navigation, search and pagination are CACHED (state.browser): reopening the browser
    // restores the exact level where the user chose a shader (family/subfolder screen,
    // search query, page, remembered focus row) instead of resetting to Home.
    val nav = state.browser.nav
    val query = state.browser.query
    val pages = state.browser.pages
    fun pageOf(key: String): Int = pages[key] ?: 0

    // Install/download state lives in ShaderSectionState (hoisted): closing the browser
    // mid-download does not kill the install, and the requested preset auto-applies when
    // the pack finishes. Browser navigation also persists there (state.browser) so the
    // user returns to the same level where the shader was chosen.

    // --- focus protocol: per-screen remembered index + per-row requesters ---
    val focusIndices = state.browser.focusIndices
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val screenKey = nav.current.key()
    fun requesterFor(index: Int): FocusRequester =
        requesters.getOrPut("$screenKey:$index") { FocusRequester() }

    // Double-click gesture (spec 2026-08-12, Missão 5): last preset that was REALLY
    // applied (cloud rows / failed applies / toggle-off CLEARS never arm). A second press
    // on the same row within WINDOW_MS applies-and-closes the whole QuickMenu.
    var armedPath by remember { mutableStateOf<String?>(null) }
    var armedAtMs by remember { mutableLongStateOf(0L) }
    // The arm expires with the window: the hint on the armed row disappears when the
    // gesture can no longer fire (keeps the UI honest — no stale "press again" label).
    LaunchedEffect(armedPath, armedAtMs) {
        if (armedPath != null) {
            delay(ShaderDoubleClickLogic.WINDOW_MS)
            armedPath = null
        }
    }

    // M3 (spec 2026-08-12 — favoritos): SharedPreferences is not Compose state, so the
    // favorite paths are mirrored into an observable local list — toggling updates the
    // store AND the list, and the star / Home "Favoritos" section recompose immediately.
    // (A bare state WRITE with no reader does not invalidate Compose — the mirror IS
    // the read source; this also avoids SharedPreferences reads per row recomposition.)
    var favoritePaths by remember { mutableStateOf(state.favorites.list()) }
    val context = LocalContext.current
    fun toggleFavorite(preset: ShaderPreset) {
        state.favorites.toggle(preset.path)
        favoritePaths = state.favorites.list()
        GamepadHaptics.vibrate(context)
    }

    var navTick by remember { mutableIntStateOf(0) }
    var pendingFocus by remember { mutableIntStateOf(-1) }
    fun goTo(screen: ShaderBrowserScreen) {
        nav.push(screen)
        pendingFocus = 0
        navTick++
    }

    fun navigateBack() {
        // Search is a mode, not a screen: B with an active query clears it first.
        if (nav.current == ShaderBrowserScreen.Home && query.isNotBlank()) {
            state.browser.query = ""
            pendingFocus = 0
            navTick++
            return
        }
        if (nav.pop()) {
            pendingFocus = focusIndices[nav.current.key()] ?: 0
            navTick++
        } else {
            onClose()
        }
    }

    /**
     * M1 (spec 2026-08-12 — paginação por gamepad): L1/R1 step one page (repeat-gated
     * by the caller, P2 pattern), L2/R2 page continuously (P5 pattern). Only screens
     * with a slice are pageable — search results (pages["search"]) and family screens
     * (pages[familyKey]). The Home surface (recents + family list, no LoadMore row)
     * ignores the keys entirely: no surprise paging where there is nothing to page.
     *
     * Focus after paging reuses the existing pendingFocus/navTick protocol: the row
     * that was focused BEFORE the swap, clamped into the new page's slots (the clamp
     * prevents asking for the "Show more" row of a page that does not have one), so
     * the selection keeps its relative position and Compose auto-scrolls it in.
     *
     * @return true when the page actually changed (caller consumes the key).
     */
    fun pageScreen(delta: Int): Boolean {
        val screen = nav.current
        // Home is pageable ONLY while showing search results (its other branch — recents
        // + family list — has no slice). Family screens are always pageable.
        val isSearch = screen == ShaderBrowserScreen.Home && state.browser.query.isNotBlank()
        if (!isSearch && screen !is ShaderBrowserScreen.Family) return false
        val key: String
        val count: Int
        if (isSearch) {
            key = "search"
            count = catalog.search(state.browser.query).size
        } else {
            val family = screen as ShaderBrowserScreen.Family
            key = family.key()
            count = catalog.presetsIn(family.name, family.subfolder).size
        }
        val current = pages[key] ?: 0
        val newPage = ShaderPagingLogic.decidePage(current, delta, count, PAGE_SIZE)
        if (newPage == current) return false

        // Slots of the NEW page composition: preset slice + optional "Show more" row
        // (+1 for the search field slot, which is not part of any page).
        val sliceSize = minOf(PAGE_SIZE, count - newPage * PAGE_SIZE).coerceAtLeast(0)
        val remaining = count - (newPage + 1) * PAGE_SIZE
        val rows = sliceSize + if (remaining > 0) 1 else 0
        val slots = rows + if (isSearch) 1 else 0
        val oldFocus = focusIndices[screenKey] ?: 0
        pendingFocus = ShaderPagingLogic.clampRowIndex(oldFocus, slots)
        pages[key] = newPage
        navTick++
        return true
    }

    // Initial focus when the browser opens: restore the remembered row of the restored
    // screen (the cached navigation state may not be Home), falling back to row 0.
    LaunchedEffect(Unit) {
        pendingFocus = focusIndices[nav.current.key()] ?: 0
        navTick++
    }
    LaunchedEffect(navTick) {
        if (navTick > 0 && pendingFocus >= 0) {
            val target = pendingFocus
            var landed = false
            repeat(3) {
                try {
                    requesterFor(target).requestFocus()
                    landed = true
                    return@LaunchedEffect
                } catch (_: Exception) {
                    delay(80)
                }
            }
            // Target row unavailable (e.g. remembered index for the search field slot
            // on a different screen) — fall back to the first row rather than losing
            // focus entirely.
            if (!landed && target != 0) {
                try {
                    requesterFor(0).requestFocus()
                } catch (_: Exception) {
                    // Focus guardian in the menu restores focus on next open.
                }
            }
        }
    }

    // M6 (spec 2026-08-12 — C6): the browser tracks its own focus (the QuickMenu guardian
    // is gated OFF while this surface is composed) so a lost focus can be restored here
    // without waiting for PS/B.
    var browserHasFocus by remember { mutableStateOf(false) }

    // M6 focus guardian: continuous loop. If the focused row leaves the composition
    // ("Show more" repages, search clear swaps the list, an async install refreshes rows,
    // a category collapse), Compose clears the focus and NOTHING else would restore it —
    // a dead browser. Gentle like the menu guardian: while the user is actively navigating
    // (< 600 ms since the last focus move) the cycle is skipped, so a restore (which starts
    // with clearFocus) never lands mid-gesture.
    // NOTE: this block must NOT capture the composition-scoped `requesterFor` — it is
    // re-created per composition with the screenKey of THAT composition. The guardian
    // computes the key from nav.current at CALL time, like the rows do.
    LaunchedEffect(Unit) {
        delay(150) // let the opening bootstrap land first (never fight it).
        while (true) {
            if (!browserHasFocus) {
                val now = SystemClock.uptimeMillis()
                if (now - GamepadNavigationClock.lastMoveAt < 600L) {
                    Timber.d("ShaderBrowser guardian: user navigating, skipping cycle")
                } else {
                    val screen = nav.current.key()
                    val remembered = focusIndices[screen] ?: 0
                    Timber.d("ShaderBrowser guardian: restoring focus row=%d screen=%s", remembered, screen)
                    fun requesterForCurrent(index: Int): FocusRequester =
                        requesters.getOrPut("$screen:$index") { FocusRequester() }
                    try {
                        requesterForCurrent(remembered).requestFocus()
                        delay(60) // let the request land before verifying
                        if (!browserHasFocus && remembered != 0) {
                            Timber.d("ShaderBrowser guardian: fallback to row 0")
                            requesterForCurrent(0).requestFocus()
                        }
                    } catch (_: Exception) {
                        try {
                            requesterForCurrent(0).requestFocus()
                        } catch (_: Exception) {
                            // Next loop iteration retries; never leave the browser dead.
                        }
                    }
                }
            }
            delay(400)
        }
    }

    BackHandler(enabled = true, onBack = { navigateBack() })

    // --- shared row builder ---
    @Composable
    fun BrowserRow(
        title: String,
        subtitle: String? = null,
        selected: Boolean = false,
        enabled: Boolean = true,
        leadingIcon: (@Composable () -> Unit)? = null,
        trailingIcon: (@Composable () -> Unit)? = null,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
        onKeyEventOverride: ((android.view.KeyEvent) -> Boolean)? = null,
        onYKey: (() -> Unit)? = null,
        index: Int,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        val accent = PluviaTheme.colors.accentPurple
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .focusRequester(requesterFor(index))
                .gamepadFocusIndex(index) { focusIndices[screenKey] = it }
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isFocused) {
                        Brush.horizontalGradient(
                            colors = listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.08f)),
                        )
                    } else {
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Transparent),
                        )
                    },
                )
                .then(
                    if (onKeyEventOverride != null) {
                        Modifier.onKeyEvent { keyEvent ->
                            onKeyEventOverride(keyEvent.nativeKeyEvent)
                        }
                    } else {
                        Modifier
                    },
                )
                .gamepadSelectable(
                    selected = selected,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    enabled = enabled,
                    shape = RoundedCornerShape(14.dp),
                    interactionSource = interactionSource,
                    accentColor = accent,
                    onYKey = onYKey,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leadingIcon != null) {
                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    leadingIcon()
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailingIcon != null) {
                // Wrap-content (not the fixed 20.dp box): a row can carry the active
                // check AND the favorite star side by side (M3).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    trailingIcon()
                }
            }
        }
    }

    @Composable
    fun PresetRow(preset: ShaderPreset, index: Int) {
        val local = state.pack.isLocal(preset)
        val broken = preset.broken
        val downloadingThis = state.installing && state.pendingPreset?.path == preset.path
        val failedThis = state.installFailed && state.pendingPreset?.path == preset.path
        // M3 (spec 2026-08-12 — favoritos): user-pinned candidates for the experiment
        // session; independent from recents. Y (focused row) or touch long-press toggles.
        val favorite = preset.path in favoritePaths
        // M5 (spec 2026-08-12 — etiqueta "pesado"): data-driven, no curated list —
        // the manifest's pass count + closure size decide before anything is downloaded.
        val heavy = ShaderPresetCost.isHeavyPreset(preset)
        // Progressive disclosure (spec 2026-08-12, UX fix 2): while the double-click
        // gesture is armed on THIS row, its subtitle teaches the close-with-shader
        // accelerator. The arm (and the hint) expires with the window.
        val gestureArmed = armedPath == preset.path
        BrowserRow(
            title = friendlyName(preset.path),
            subtitle = when {
                downloadingThis -> stringResource(
                    R.string.shader_downloading,
                    (state.progress * 100).toInt(),
                ) + " · " + stringResource(R.string.shader_download_cancel_hint)
                failedThis && state.installNoSpace -> stringResource(
                    R.string.shader_download_no_space_hint,
                    formatBytes(preset.bytes.coerceAtLeast(1) * 2 + PackPrechecks.HEADROOM_BYTES),
                )
                failedThis -> stringResource(R.string.shader_download_failed)
                gestureArmed -> stringResource(R.string.shader_double_click_hint)
                else -> listOfNotNull(
                    friendlyFamilyName(preset.family),
                    preset.passes.takeIf { it > 0 }?.let {
                        pluralStringResource(R.plurals.quick_menu_n_passes, it, it)
                    },
                    formatBytes(preset.bytes).takeIf { preset.bytes > 0 },
                ).joinToString(" · ")
            },
            selected = state.isActive(preset),
            // Cloud rows stay enabled + focusable: selecting one downloads ONLY this
            // preset's closure (user decision 2026-08-12) and applies it automatically.
            enabled = !broken,
            leadingIcon = when {
                broken -> {
                    {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = stringResource(R.string.shader_broken),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                !local -> {
                    {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = stringResource(R.string.shader_not_downloaded),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> null
            },
            trailingIcon = {
                if (state.isActive(preset)) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = PluviaTheme.colors.accentPurple,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (favorite) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = stringResource(R.string.shader_favorite),
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (heavy) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = stringResource(R.string.shader_heavy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            },
            // Cancel affordance for an in-flight download on THIS row: raw B while focused
            // (main phase — the focused row wins over the surface's hierarchical back) or
            // touch long-press. A plain click while downloading does NOTHING: double-click
            // habit must never cancel a download by accident (spec 2026-08-12, UX fix 1).
            onKeyEventOverride = if (downloadingThis) {
                { event ->
                    if (event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B &&
                        event.action == android.view.KeyEvent.ACTION_DOWN
                    ) {
                        state.cancelInstall()
                        true
                    } else {
                        false
                    }
                }
            } else {
                null
            },
            onLongClick = if (downloadingThis) {
                // Cancel keeps priority while a download is in flight on this row
                // (spec 2026-08-12, UX fix 1); otherwise long-press toggles the
                // favorite (M3).
                { state.cancelInstall() }
            } else {
                { toggleFavorite(preset) }
            },
            onYKey = { toggleFavorite(preset) },
            onClick = {
                when {
                    broken -> Unit
                    downloadingThis -> Unit
                    !local -> {
                        // Cloud row: first click starts the download. NEVER arms the
                        // double-click gesture (spec 2026-08-12 §5.1.3) — the user must
                        // see the shader applied before the close gesture can exist.
                        state.startInstall(preset)
                    }
                    else -> {
                        val action = ShaderDoubleClickLogic.decide(
                            armedPath = armedPath,
                            armedAtMs = armedAtMs,
                            path = preset.path,
                            nowMs = SystemClock.uptimeMillis(),
                        )
                        when (action) {
                            ShaderDoubleClickLogic.Action.Activate ->
                                // Arm ONLY on a real load (Applied). A toggle-off CLEAR of
                                // the active preset never arms: double-clicking an active
                                // row then reads as the predictable off-then-on toggle, and
                                // never surprises the user with "shader off + menu closed"
                                // (spec 2026-08-12, UX fix 3).
                                when (state.applyPreset(preset)) {
                                    ApplyPresetResult.Applied -> {
                                        armedPath = preset.path
                                        armedAtMs = SystemClock.uptimeMillis()
                                    }
                                    ApplyPresetResult.Cleared -> armedPath = null
                                    ApplyPresetResult.Missing -> Unit
                                }
                            ShaderDoubleClickLogic.Action.ConfirmAndClose -> {
                                // Second press on the same row inside the window: the preset
                                // is already applied — close the whole QuickMenu, the fast
                                // experiment loop PS → pick → A A → see the game.
                                armedPath = null
                                onClose()
                                onCloseQuickMenu()
                            }
                        }
                    }
                }
            },
            index = index,
        )
    }

    @Composable
    fun LoadMoreRow(remaining: Int, index: Int, onLoadMore: () -> Unit) {
        if (remaining <= 0) return
        BrowserRow(
            title = stringResource(R.string.shader_load_more, remaining),
            onClick = onLoadMore,
            index = index,
        )
    }

    // --- content per screen ---
    val scrollState = rememberScrollState()
    var focusSlot = 0
    fun nextSlot(): Int = focusSlot++

    Column(
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { focusState ->
                if (browserHasFocus != focusState.hasFocus) {
                    browserHasFocus = focusState.hasFocus
                    Timber.d("ShaderBrowser root focus: %b", focusState.hasFocus)
                }
            }
            // M1 (spec 2026-08-12 — paginação por gamepad): L1/R1 page one step gated by
            // repeatCount == 0 (the Android key repeat must not cycle pages — P2 pattern),
            // L2/R2 page continuously while held (P5 pattern). Only consumed when the page
            // actually changed; the preview phase runs before the focused row, so this
            // works wherever the focus is.
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val native = keyEvent.nativeKeyEvent
                    val consumed = when (native.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_L1 ->
                            if (native.repeatCount == 0) pageScreen(-1) else false
                        KeyEvent.KEYCODE_BUTTON_R1 ->
                            if (native.repeatCount == 0) pageScreen(+1) else false
                        KeyEvent.KEYCODE_BUTTON_L2 -> pageScreen(-1)
                        KeyEvent.KEYCODE_BUTTON_R2 -> pageScreen(+1)
                        else -> false
                    }
                    consumed
                } else {
                    false
                }
            }
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .gamepadBackHandler(::navigateBack),
    ) {
        // Header: back chip + title.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!nav.atRoot) {
                val backIndex = nextSlot()
                val backInteraction = remember { MutableInteractionSource() }
                val backFocused by backInteraction.collectIsFocusedAsState()
                Row(
                    modifier = Modifier
                        .focusRequester(requesterFor(backIndex))
                        .gamepadFocusIndex(backIndex) { focusIndices[screenKey] = it }
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (backFocused) {
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        PluviaTheme.colors.accentPurple.copy(alpha = 0.14f),
                                        PluviaTheme.colors.accentPurple.copy(alpha = 0.07f),
                                    ),
                                )
                            } else {
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, Color.Transparent),
                                )
                            },
                        )
                        .gamepadSelectable(
                            selected = false,
                            onClick = ::navigateBack,
                            shape = RoundedCornerShape(10.dp),
                            interactionSource = backInteraction,
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.shader_browser_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.shader_browser_back),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Text(
                text = when (val screen = nav.current) {
                    ShaderBrowserScreen.Home -> stringResource(R.string.shader_browser_title)
                    is ShaderBrowserScreen.Family ->
                        if (screen.subfolder == null) {
                            friendlyFamilyName(screen.name)
                        } else {
                            friendlyName(screen.subfolder)
                        }
                },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(vertical = 8.dp),
        ) {
            when (val screen = nav.current) {
                ShaderBrowserScreen.Home -> {
                    // Search-first: the field is the first focusable row of the surface.
                    GamepadSearchField(
                        query = query,
                        onQueryChange = { state.browser.query = it },
                        placeholder = stringResource(R.string.shader_search),
                        focusIndex = nextSlot(),
                        onFocusIndexChanged = { focusIndices["home"] = it },
                        focusRequester = requesterFor(0),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (query.isNotBlank()) {
                        val results = catalog.search(query)
                        if (results.isEmpty()) {
                            Text(
                                text = stringResource(R.string.shader_no_results, query.trim()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        } else {
                            val page = pageOf("search")
                            val slice = catalog.page(results, page, PAGE_SIZE)
                            slice.forEach { PresetRow(it, nextSlot()) }
                            LoadMoreRow(results.size - (page + 1) * PAGE_SIZE, nextSlot()) {
                                pages["search"] = page + 1
                            }
                        }
                    } else {
                        // M3 (spec 2026-08-12 — favoritos): stable "candidates" list,
                        // ABOVE recents, same rows (broken presets filtered out).
                        val favorites = favoritePaths
                            .mapNotNull { catalog.preset(it) }
                            .filter { !it.broken }
                        if (favorites.isNotEmpty()) {
                            SectionHeader(stringResource(R.string.shader_favorites))
                            favorites.forEach { PresetRow(it, nextSlot()) }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        val recents = state.recents.list()
                            .mapNotNull { catalog.preset(it) }
                            .filter { !it.broken }
                        if (recents.isNotEmpty()) {
                            SectionHeader(stringResource(R.string.shader_recents))
                            recents.forEach { PresetRow(it, nextSlot()) }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        val ordered = FAMILY_ORDER + catalog.families.map { it.name }
                            .filter { it !in FAMILY_ORDER }
                            .sorted()
                        SectionHeader(stringResource(R.string.shader_browse))
                        ordered.forEach { name ->
                            val family = catalog.families.firstOrNull { it.name == name } ?: return@forEach
                            BrowserRow(
                                title = friendlyFamilyName(name),
                                subtitle = pluralStringResource(
                                    R.plurals.quick_menu_n_presets,
                                    family.count,
                                    family.count,
                                ),
                                onClick = { goTo(ShaderBrowserScreen.Family(name)) },
                                index = nextSlot(),
                            )
                        }
                    }
                }

                is ShaderBrowserScreen.Family -> {
                    val subfolders = if (screen.subfolder == null) catalog.subfolders(screen.name) else emptyList()
                    val showSubfolders = subfolders.size > 1
                    if (showSubfolders) {
                        SectionHeader(stringResource(R.string.shader_family_sections))
                        subfolders.forEach { sub ->
                            val count = catalog.presetsIn(screen.name, sub).size
                            BrowserRow(
                                title = friendlyName(sub),
                                subtitle = pluralStringResource(R.plurals.quick_menu_n_presets, count, count),
                                onClick = { goTo(ShaderBrowserScreen.Family(screen.name, sub)) },
                                index = nextSlot(),
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    val familyKey = screen.key()
                    val page = pageOf(familyKey)
                    val items = catalog.presetsIn(screen.name, screen.subfolder)
                    val slice = catalog.page(items, page, PAGE_SIZE)
                    slice.forEach { PresetRow(it, nextSlot()) }
                    LoadMoreRow(items.size - (page + 1) * PAGE_SIZE, nextSlot()) {
                        pages[familyKey] = page + 1
                    }
                }
            }
        }

        // P3 (spec 2026-08-12): the deepest surface needs its key hints the most — the
        // browser replaces the whole menu content, so its own footer teaches A (select /
        // download), B (back) and PS/Guide (back to the game — Home closes the browser
        // AND the menu, spec 2026-08-13-home-button-overlay-exit M1). Shown only with a
        // gamepad connected (shouldShowGamepadUI), like the menu's bar.
        // M1 (spec 2026-08-12): pagination hints (LB/RB) follow the QuickMenu footer
        // pattern — short labels, six actions max, so the bar stays one line inside the
        // menu panel width. LT/RT hold-paging still works but is not advertised here:
        // eight actions overflowed the panel and collapsed the shader list.
        GamepadActionBar(
            actions = listOf(
                GamepadAction(GamepadButton.A, R.string.shader_browser_action_select),
                GamepadAction(GamepadButton.B, R.string.shader_browser_back),
                GamepadAction(GamepadButton.Y, R.string.shader_browser_action_favorite),
                GamepadAction(GamepadButton.LB, R.string.shader_browser_action_prev_page),
                GamepadAction(GamepadButton.RB, R.string.shader_browser_action_next_page),
                GamepadAction(GamepadButton.GUIDE, R.string.shader_browser_action_close),
            ),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    // Metered-network disclosure (spec §4.2.3 adapted to per-preset fetches): shown when
    // downloadPreset signals PackMeteredException — no byte was transferred yet.
    MessageDialog(
        visible = state.meteredConfirm,
        onDismissRequest = { state.meteredConfirm = false },
        onDismissClick = { state.meteredConfirm = false },
        onConfirmClick = {
            val pending = state.pendingPreset
            state.meteredConfirm = false
            if (pending != null) state.startInstall(pending, allowMetered = true)
        },
        confirmBtnText = stringResource(R.string.shader_download_confirm),
        dismissBtnText = stringResource(R.string.cancel),
        title = stringResource(R.string.shader_metered_title),
        message = stringResource(
            R.string.shader_metered_body,
            formatBytes(state.pendingPreset?.bytes ?: 0),
        ),
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
    )
}
