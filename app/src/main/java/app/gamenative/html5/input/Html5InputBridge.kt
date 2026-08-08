package app.gamenative.html5.input

import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentLinkedQueue

// queue-shaped JS-exposed bridge. main thread enqueues normalized event-spec JSON strings;
// JS binder thread drains via drainQueue() per rAF tick. ConcurrentLinkedQueue is lock-free
// FIFO; safe for single-producer / single-consumer.

class Html5InputBridge {
    private val queue = ConcurrentLinkedQueue<String>()

    // the 3-finger-tap "open_quick_menu" action: touch.js fires a window event that hops
    // through this bridge so the host screen can flip showQuickMenu = true. callback set by
    // WebViewScreen at WebView attach; @Volatile because the JS binder thread invokes
    // openQuickMenu() while the main thread mutates the field on Compose recompose.
    @Volatile
    var onOpenQuickMenu: (() -> Unit)? = null

    // called from main thread on each binding press/release/cursor-move event
    fun enqueue(eventSpecJson: String) {
        queue.add(eventSpecJson)
    }

    // JS shim calls this once per requestAnimationFrame tick.
    // returns "[]" empty or "[{...},{...}]" array; caller JSON.parses + dispatches.
    @JavascriptInterface
    fun drainQueue(): String {
        val specs = buildList {
            var s = queue.poll()
            while (s != null) {
                add(s)
                s = queue.poll()
            }
        }
        return if (specs.isEmpty()) "[]" else "[${specs.joinToString(",")}]"
    }

    // invoked from JS binder thread when touch.js dispatches a
    // window 'gn-open-quickmenu' event (3-finger-tap action open_quick_menu). hops to the
    // host screen's QuickMenu state via the registered callback.
    @JavascriptInterface
    fun openQuickMenu() {
        onOpenQuickMenu?.invoke()
    }
}
