package app.gamenative.html5.shim

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

// in-game exit requests (nw.App.quit, nw.Window.close, C3 Quit) from JS shims.
// onExit calls the back dispatcher directly, NOT AndroidEvent.BackPressed: that event reaches both
// WebViewScreen and MainViewModel, and the double NavHost pop throws IllegalStateException.
// latched because games often cascade quit calls (nw.App.quit -> nw.Window.close -> ...).
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
