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

// pure (no IO) so it stays previewable; Html5ControllerTabContent below does the load/save.
@Composable
internal fun Html5ControllerTabBody(
    container: WebViewContainer,
    onContainerChanged: (WebViewContainer) -> Unit,
    // needed beyond onContainerChanged: profile.save() writes its own .icp store, never WebViewContainer.
    onChangeCommitted: () -> Unit = {},
) {
    val context = LocalContext.current

    val profile = remember(container.id, container.controlsProfileId) {
        Html5DefaultControlsProfileFactory.getOrCreate(context, container)
    }

    // persist a newly minted profile id on first open.
    LaunchedEffect(container.id, profile.id) {
        if (container.controlsProfileId == 0L && profile.id >= 0) {
            onContainerChanged(container.copy(controlsProfileId = profile.id.toLong()))
        }
    }

    var showPhysicalControllerDialog by remember { mutableStateOf(false) }
    var showGestureConfigDialog by remember { mutableStateOf(false) }

    SettingsGroup {
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(stringResource(R.string.html5_controller_tab_edit_physical)) },
            onClick = { showPhysicalControllerDialog = true },
        )

        // overlay settings intentionally live in the QuickMenu, as for wine.

        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(stringResource(R.string.touch_gestures_label)) },
            onClick = { showGestureConfigDialog = true },
        )
    }

    if (showPhysicalControllerDialog) {
        PhysicalControllerConfigSection(
            profile = profile,
            onDismiss = { showPhysicalControllerDialog = false },
            onSave = {
                // ensure controllersLoaded=true before save, as XServerScreen does.
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

@Composable
fun Html5ControllerTabContent(
    appId: String,
    onWebViewContainerSaved: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var container by remember(appId) { mutableStateOf<WebViewContainer?>(null) }
    var slug by remember(appId) { mutableStateOf<String?>(null) }
    // null = in flight, false = lookup failed (usually a deleted container with a stale dialog open).
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
            scope.launch(Dispatchers.IO) {
                runCatching { WebViewContainer.save(currentSlug, updated) }
                    .onFailure { Timber.tag("Html5ControllerTab").w(it, "save failed for %s", currentSlug) }
            }
        },
        onChangeCommitted = onWebViewContainerSaved,
    )
}
