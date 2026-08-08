package app.gamenative.html5.shim

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

// host-side bridge for JS runtime lifecycle calls (e.g. nw.App.quit, nw.Window.close, C3 Quit action).
// JS shims route in-game exit requests through `window.__gnRuntimeBridge.exit(source)`.

// NOT via PluviaApp.events.emit(AndroidEvent.BackPressed): that fires BOTH WebViewScreen.onBack AND
// MainViewModel.onBackPressed, each of which triggers a NavHost pop -- "Cannot transition entry that
// is not in the back stack" IllegalState. XServerScreen's back-exit pattern calls the dispatcher
// directly and avoids the double-subscriber duplication; we do the same here.

// onExit is provided by WebViewScreen as a bound lambda that calls
// ComponentActivity.onBackPressedDispatcher.onBackPressed() on the captured Activity context.

// @JavascriptInterface invocations come off the WebView chromium thread -- we must marshal to main.
// LATCH: games often cascade quit calls (nw.App.quit → nw.Window.close → …); fire once per mount.
class Html5RuntimeBridge(private val onExit: () -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fired = AtomicBoolean(false)

    @JavascriptInterface
    fun exit(source: String) {
        if (!fired.compareAndSet(false, true)) {
            Timber.tag("Html5RuntimeBridge").d("exit() suppressed (already firing) from %s", source)
            return
        }
        Timber.tag("Html5RuntimeBridge").i("exit() called from %s", source)
        mainHandler.post { onExit() }
    }
}
