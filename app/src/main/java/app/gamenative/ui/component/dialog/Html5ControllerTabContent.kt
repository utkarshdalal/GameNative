package app.gamenative.ui.component.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.data.TouchGestureConfig
import app.gamenative.html5.host.WebViewScreenViewModel
import app.gamenative.html5.input.Html5DefaultControlsProfileFactory
import app.gamenative.runtime.WebViewContainer
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

// HTML5-variant ControllerTab content swap -- internal body.
// public entry is Html5ControllerTabContent(appId, ...) below which loads the WebViewContainer
// and threads a save callback. this body is pure (no IO) and previewable.
// reuses Wine-shared components (PhysicalControllerConfigSection, ControllerBindingDialog,
// TouchGestureSettingsDialog) -- zero new mapping UI written here.
@Composable
internal fun Html5ControllerTabBody(
    container: WebViewContainer,
    onContainerChanged: (WebViewContainer) -> Unit,
    // explicit "user committed a change in this tab" signal. fired by both inner
    // dialogs' onSave so the parent ContainerConfigDialog's html5Edited flag flips for BOTH
    // gesture edits AND physical-controller mapping edits. profile.save() writes directly to
    // its own .icp store and never touches WebViewContainer, so onContainerChanged alone can't
    // detect that path.
    onChangeCommitted: () -> Unit = {},
) {
    val context = LocalContext.current

    // resolve the active per-container ControlsProfile (mirrors WebViewScreen Step A).
    // factory is container-aware -- each container gets its OWN profile
    // (Wine parity). bootstrap path mints a fresh profile; existing path loads by id.
    val profile = remember(container.id, container.controlsProfileId) {
        Html5DefaultControlsProfileFactory.getOrCreate(context, container)
    }

    // bootstrap: persist newly minted profile id back to container on first open (Wine parity).
    LaunchedEffect(container.id, profile.id) {
        if (container.controlsProfileId == 0L && profile.id >= 0) {
            onContainerChanged(container.copy(controlsProfileId = profile.id.toLong()))
        }
    }

    var showPhysicalControllerDialog by remember { mutableStateOf(false) }
    var showGestureConfigDialog by remember { mutableStateOf(false) }

    SettingsGroup {
        // 1. Physical Controller mapping entry → reuses Wine PhysicalControllerConfigSection
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(stringResource(R.string.html5_controller_tab_edit_physical)) },
            onClick = { showPhysicalControllerDialog = true },
        )

        // overlay edit + overlay opacity / visibility intentionally absent: live in QuickMenu
        // EDIT_OVERLAY mid-game (Wine parity -- controller settings belong in the quick action
        // sidebar, not the container config tab).

        // opens shared TouchGestureSettingsDialog with showHtml5Extras=true (cursor-mode +
        // 3-finger-tap cards). Wine call sites omit the flag.
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(stringResource(R.string.touch_gestures_label)) },
            onClick = { showGestureConfigDialog = true },
        )
    }

    // physical controller config dialog -- mirrors XServerScreen's PhysicalControllerConfigSection call
    if (showPhysicalControllerDialog) {
        PhysicalControllerConfigSection(
            profile = profile,
            onDismiss = { showPhysicalControllerDialog = false },
            onSave = {
                // ensure controllersLoaded=true before save (parity with XServerScreen's onSave)
                profile.addController("*")
                profile.save()
                profile.loadControllers()
                if (container.controlsProfileId == 0L && profile.id >= 0) {
                    onContainerChanged(container.copy(controlsProfileId = profile.id.toLong()))
                }
                onChangeCommitted()
                showPhysicalControllerDialog = false
                Timber.tag("Html5ControllerTab").d("saved profile %s (id=%d)", profile.name, profile.id)
            },
        )
    }

    // gesture editor -- fromJson handles "" → defaults; toJson roundtrip persists.
    // showHtml5Extras=true reveals cursor-mode + 3-finger-tap cards (Wine call sites omit param).
    if (showGestureConfigDialog) {
        val current = remember {
            TouchGestureConfig.fromJson(container.gestureConfig, TouchGestureConfig.html5Defaults())
        }
        TouchGestureSettingsDialog(
            gestureConfig = current,
            onDismiss = { showGestureConfigDialog = false },
            onSave = { updated ->
                onContainerChanged(container.copy(gestureConfig = updated.toJson()))
                onChangeCommitted()
                showGestureConfigDialog = false
            },
            showHtml5Extras = true,
        )
    }
}

// public entry for ContainerConfigDialog HTML5 ControllerTab swap.
// owns slug→WebViewContainer load + save side-effects so Html5ControllerTabBody stays pure.
// loads on first composition keyed on appId; saves persist to disk + signal caller via
// onWebViewContainerSaved (e.g. for snackbar feedback).
@Composable
fun Html5ControllerTabContent(
    appId: String,
    onWebViewContainerSaved: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var container by remember(appId) { mutableStateOf<WebViewContainer?>(null) }
    var slug by remember(appId) { mutableStateOf<String?>(null) }
    // tri-state load gate: null=in-flight, true=loaded, false=lookup failed (slug unresolved
    // or JSON missing -- usually a deleted container with a stale config dialog open).
    var loaded by remember(appId) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(appId) {
        val resolved = withContext(Dispatchers.IO) {
            val s = WebViewScreenViewModel.slugFromAppId(appId) ?: return@withContext null
            WebViewContainer.load(s)?.let { s to it }
        }
        if (resolved != null) {
            slug = resolved.first
            container = resolved.second
            loaded = true
        } else {
            Timber.tag("Html5ControllerTab").w("no html5 container for appId=%s", appId)
            loaded = false
        }
    }

    if (loaded == false) {
        Text(stringResource(R.string.html5_controller_tab_unavailable))
        return
    }
    val current = container ?: return
    val currentSlug = slug ?: return

    Html5ControllerTabBody(
        container = current,
        onContainerChanged = { updated ->
            container = updated
            // persist asynchronously -- callers are sync onClick handlers
            scope.launch(Dispatchers.IO) {
                runCatching { WebViewContainer.save(currentSlug, updated) }
                    .onFailure { Timber.tag("Html5ControllerTab").w(it, "save failed for %s", currentSlug) }
            }
        },
        // fired synchronously from each dialog's onSave so parent dirty-flag flips even when
        // the change went through a path that doesn't touch WebViewContainer (profile saves).
        onChangeCommitted = onWebViewContainerSaved,
    )
}
