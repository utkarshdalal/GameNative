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

// visibility flags and container state stay hoisted in WebViewScreen.
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
    // dismiss/save keep edit mode active so the user can edit other elements.
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

    // uses the active per-container ControlsProfile; container.extra("profileId") is Wine-only.
    if (showPhysicalControllerDialog) {
        PhysicalControllerDialog(
            profile = activeControlsProfile,
            onDismiss = { onDismissPhysicalControllerDialog() },
            onSave = {
                activeControlsProfile.addController("*")
                activeControlsProfile.save()
                activeControlsProfile.loadControllers()
                PluviaApp.inputControlsView?.setProfile(activeControlsProfile)
                // a remapped axis-bound key would otherwise stay synthetically held down.
                html5InputSynthesizer.reset()
                onDismissPhysicalControllerDialog()
            },
        )
    }

    // saves persist AND push the config live to touch.js, so game state survives the change.
    if (showGestureDialog) {
        val current = remember(container.gestureConfig) {
            TouchGestureConfig.fromJson(container.gestureConfig, TouchGestureConfig.html5Defaults())
        }
        app.gamenative.ui.component.dialog.TouchGestureSettingsDialog(
            gestureConfig = current,
            onDismiss = { onDismissGestureDialog() },
            onSave = { updated ->
                val newJson = updated.toJson()
                // keeps the parse-time config in sync if the WebView reloads.
                onContainerChange(container.copy(gestureConfig = newJson))
                pickerScope.launch(Dispatchers.IO) {
                    persistContainer(container.copy(gestureConfig = newJson), "gestureConfig persist failed")
                }
                webView.post {
                    webView.evaluateJavascript("window.__gnGestureConfig = $newJson;", null)
                }
                onDismissGestureDialog()
            },
            showHtml5Extras = true,
        )
    }

    // live-applies on change; persists ONCE on Done, never per slider drag (save thrash).
    if (showOverlayControlsDialog) {
        OverlayControlsDialog(
            initialOpacity = container.overlayOpacity,
            initialVisible = container.overlayVisible,
            onLiveOpacity = { v ->
                PluviaApp.inputControlsView?.setOverlayOpacity(v)
                PluviaApp.inputControlsView?.invalidate()
            },
            onLiveVisible = { v ->
                // ICV loads elements lazily on first paint, so a hide -> show on a never-painted ICV would draw
                // nothing without loadElements. visibility must flip too: ICV paints a grey background over the
                // WebView even with controls hidden.
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
                // so observers re-fire and a reopened dialog shows the new values.
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
