package app.gamenative.html5.host

import android.view.View
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.gamenative.PluviaApp
import app.gamenative.data.TouchGestureConfig
import app.gamenative.html5.input.Html5InputSynthesizer
import app.gamenative.runtime.WebViewContainer
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

// the conditional in-game dialogs rendered as siblings of the game Box: element editor,
// physical-controller config, touch-gesture settings, overlay controls. pure UI + persistence;
// dialog-visibility flags + container state stay hoisted in WebViewScreen and flow in here.
@Composable
internal fun WebViewScreenDialogs(
    elementToEdit: ControlElement?,
    onDismissElementEditor: () -> Unit,
    showPhysicalControllerDialog: Boolean,
    onDismissPhysicalControllerDialog: () -> Unit,
    activeControlsProfile: ControlsProfile,
    html5InputSynthesizer: Html5InputSynthesizer,
    showGestureDialog: Boolean,
    onDismissGestureDialog: () -> Unit,
    showOverlayControlsDialog: Boolean,
    onDismissOverlayControlsDialog: () -> Unit,
    container: WebViewContainer,
    onContainerChange: (WebViewContainer) -> Unit,
    webView: WebView,
    appId: String,
    pickerScope: CoroutineScope,
    persistContainer: (WebViewContainer, String) -> Unit,
) {
    // ElementEditorDialog hosts the binding picker (shared with Wine path).
    // dismiss/save both keep edit mode active so user can edit other elements (Wine pattern).
    val ed = elementToEdit
    val icv = PluviaApp.inputControlsView
    if (ed != null && icv != null) {
        app.gamenative.ui.component.dialog.ElementEditorDialog(
            element = ed,
            view = icv,
            onDismiss = { onDismissElementEditor() },
            onSave = {
                icv.profile?.save()
                icv.invalidate()
                onDismissElementEditor()
            },
        )
    }

    // physical controller config dialog -- mirrors XServerScreen.kt. uses the
    // active per-container ControlsProfile (not container.extra("profileId") -- that path is
    // Wine-only). save() persists bindings; loadControllers() refreshes in-memory state.
    if (showPhysicalControllerDialog) {
        PhysicalControllerDialog(
            profile = activeControlsProfile,
            onDismiss = { onDismissPhysicalControllerDialog() },
            onSave = {
                activeControlsProfile.addController("*")
                activeControlsProfile.save()
                activeControlsProfile.loadControllers()
                PluviaApp.inputControlsView?.setProfile(activeControlsProfile)
                // clear stale axisKeyState so a remap of an axis-bound key
                // doesn't leave the prior key in synthetic-down state forever.
                html5InputSynthesizer.reset()
                onDismissPhysicalControllerDialog()
            },
        )
    }

    // shared TouchGestureSettingsDialog with HTML5 extras. onSave
    // persists to disk (container.gestureConfig) AND emits live config update via
    // evaluateJavascript so the unified touch.js shim picks up changes WITHOUT re-inject /
    // teardown -- game state preserved. webView.post defers to main loop (mirrors the prior
    // hot-swap precedent -- same robustness).
    if (showGestureDialog) {
        val current = remember(container.gestureConfig) {
            TouchGestureConfig.fromJson(container.gestureConfig, TouchGestureConfig.html5Defaults())
        }
        app.gamenative.ui.component.dialog.TouchGestureSettingsDialog(
            gestureConfig = current,
            onDismiss = { onDismissGestureDialog() },
            onSave = { updated ->
                val newJson = updated.toJson()
                // also update local container so the interceptor's gestureConfigJson key
                // recomputes -- keeps parse-time + live JS state in sync if the WebView reloads.
                onContainerChange(container.copy(gestureConfig = newJson))
                pickerScope.launch(Dispatchers.IO) {
                    persistContainer(container.copy(gestureConfig = newJson), "gestureConfig persist failed")
                }
                // live update -- touch.js reads window.__gnGestureConfig fresh on next event.
                webView.post {
                    webView.evaluateJavascript("window.__gnGestureConfig = $newJson;", null)
                }
                onDismissGestureDialog()
            },
            showHtml5Extras = true,
        )
    }

    // Wine-parity overlay controls dialog. Live-applies opacity
    // + visibility to ICV on each change for instant feedback. Persists to container JSON ONCE
    // on Done -- never on slider drag -- to avoid the snackbar/save thrash that plagued the prior
    // ContainerConfig placement.
    if (showOverlayControlsDialog) {
        OverlayControlsDialog(
            initialOpacity = container.overlayOpacity,
            initialVisible = container.overlayVisible,
            onLiveOpacity = { v ->
                PluviaApp.inputControlsView?.setOverlayOpacity(v)
                PluviaApp.inputControlsView?.invalidate()
            },
            onLiveVisible = { v ->
                // mirror Wine's showInputControls(context, show) -- when toggling
                // back ON, re-read element JSON from the active profile so the overlay reappears.
                // setShowTouchscreenControls only flips a boolean; ICV.onDraw lazy-loaded elements
                // ONCE on first paint, so a hide → show cycle on a never-pre-loaded ICV would
                // otherwise paint nothing. ICV does NOT clear elements on hide and we deliberately
                // do NOT call setProfile(null) -- keeping the profile loaded so toggle-on stays cheap.
                // also toggle View visibility -- ICV's view paint covers the
                // WebView with a grey background even when "show controls" flag is false. mirror
                // Wine's hideInputControls (XServerScreen.kt) GONE/VISIBLE pattern.
                PluviaApp.inputControlsView?.let { icv ->
                    if (v) {
                        icv.profile?.loadElements(icv)
                        Timber.tag("WebViewScreen").d(
                            "ICV onLiveVisible toggle-ON: loadElements called, profile.elements=%d",
                            icv.profile?.elements?.size ?: -1,
                        )
                    }
                    icv.setShowTouchscreenControls(v)
                    icv.visibility = if (v) View.VISIBLE else View.GONE
                    Timber.tag("WebViewScreen").d("ICV live visibility: %s", if (v) "VISIBLE" else "GONE")
                    icv.invalidate()
                }
            },
            onDone = { opacity, visible ->
                onDismissOverlayControlsDialog()
                // keep local container state in sync with the persist write so the
                // LaunchedEffect observers above re-fire AND a follow-up reopen of the dialog
                // shows the new initial values (initialOpacity/initialVisible cache via remember).
                onContainerChange(container.copy(overlayOpacity = opacity, overlayVisible = visible))
                pickerScope.launch(Dispatchers.IO) {
                    val slug = WebViewScreenViewModel.slugFromAppId(appId)
                    if (slug == null) {
                        Timber.tag("WebViewScreen").w(
                            "overlayControls.persist BLOCKED: slugFromAppId(%s) returned null", appId,
                        )
                        return@launch
                    }
                    runCatching {
                        WebViewContainer.save(
                            slug,
                            container.copy(overlayOpacity = opacity, overlayVisible = visible),
                        )
                    }
                        .onFailure { Timber.tag("WebViewScreen").w(it, "overlay controls persist failed") }
                        .onSuccess {
                            Timber.tag("WebViewScreen").d(
                                "overlayControls.persist OK slug=%s opacity=%.2f visible=%b",
                                slug, opacity, visible,
                            )
                        }
                }
            },
        )
    }
}
