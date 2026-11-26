package app.gamenative.html5.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ServiceWorkerClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.UiThread
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import timber.log.Timber

// a page served at its own real https origin (ProtocolServedPage) runs in a WebView profile that belongs to its
// container: that origin's cookies and storage never meet the default profile, which holds the app's own
// sign-ins. every other title stays on the default profile.
@SuppressLint("RequiresFeature")
object Html5PageProfiles {

    private const val TAG = "Html5PageProfiles"

    fun nameFor(containerId: String): String = "html5-" + WebViewOrigin.safeIdFor(containerId)

    fun isSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    // the first call on a new WebView: a profile can't be set once the WebView was used. null = not set, and the
    // page must not load.
    @UiThread
    fun attach(context: Context, webView: WebView, containerId: String): Profile? {
        if (!isSupported()) return null
        val name = nameFor(containerId)
        // a reset or uninstall queued it while WebView wasn't loaded; drop it before it's used.
        if (name in pending(context)) drop(context, name)
        attachedHere += name
        return runCatching {
            WebViewCompat.setProfile(webView, name)
            ProfileStore.getInstance().getOrCreateProfile(name).also {
                it.serviceWorkerController.setServiceWorkerClient(NoServiceWorkers)
            }
        }.onFailure { Timber.tag(TAG).e(it, "profile %s could not be set", name) }
            .getOrNull()
            ?.also { Timber.tag(TAG).i("page runs in its own WebView profile %s", it.name) }
    }

    // the page blocks registration itself (no-service-worker.js); a worker that got in anyway fetches nothing.
    private object NoServiceWorkers : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    // profiles this process has loaded; chromium can't delete those until the next process.
    private val attachedHere: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    // container reset / uninstall. never the first WebView load of a process: that would skip the leveldb/crashpad
    // maintenance of Html5Runtime.init, which runs the queue afterwards.
    fun remove(context: Context, containerId: String) {
        val app = context.applicationContext
        val name = nameFor(containerId)
        when (dropFor(name in attachedHere, Html5Runtime.webViewLoadedInProcess(app))) {
            ProfileDrop.QueueOnly -> ProfileQueue.add(queueFile(app), name)
            ProfileDrop.Now -> Handler(Looper.getMainLooper()).post { drop(app, name) }
        }
    }

    // html5 init, after the leveldb/crashpad maintenance and before any WebView opens.
    fun deletePending(context: Context) {
        val app = context.applicationContext
        val names = pending(app)
        if (names.isEmpty()) return
        Handler(Looper.getMainLooper()).post { names.forEach { drop(app, it) } }
    }

    // a loaded profile is cleared in place instead of deleted later: a late delete would take what was played after
    // the reset with it.
    @UiThread
    private fun drop(context: Context, name: String) {
        if (!isSupported()) return
        runCatching {
            val store = ProfileStore.getInstance()
            if (name in store.allProfileNames) {
                val deleted = try {
                    store.deleteProfile(name)
                } catch (e: IllegalStateException) {
                    false
                }
                if (deleted) {
                    Timber.tag(TAG).i("deleted WebView profile %s", name)
                } else {
                    store.getProfile(name)?.let(::clear)
                    Timber.tag(TAG).i("WebView profile %s is loaded; cleared it in place", name)
                }
            }
            ProfileQueue.remove(queueFile(context), name)
        }.onFailure { Timber.tag(TAG).w(it, "WebView profile %s delete failed", name) }
    }

    private fun clear(profile: Profile) {
        profile.webStorage.deleteAllData()
        profile.cookieManager.removeAllCookies { profile.cookieManager.flush() }
        profile.geolocationPermissions.clearAll()
    }

    private fun pending(context: Context): Set<String> = ProfileQueue.read(queueFile(context))

    private fun queueFile(context: Context) = File(context.filesDir, "html5/profile-pending-delete")
}

internal enum class ProfileDrop { Now, QueueOnly }

// Now = on the main thread through the profile store (delete, or clear in place when loaded).
internal fun dropFor(attachedHere: Boolean, webViewLoaded: Boolean): ProfileDrop =
    if (attachedHere || webViewLoaded) ProfileDrop.Now else ProfileDrop.QueueOnly

internal object ProfileQueue {
    @Synchronized
    fun read(queue: File): Set<String> =
        if (queue.isFile) queue.readLines().filter { it.isNotBlank() }.toSet() else emptySet()

    @Synchronized
    fun add(queue: File, name: String) {
        val names = read(queue)
        if (name !in names) write(queue, names + name)
    }

    @Synchronized
    fun remove(queue: File, name: String) {
        val names = read(queue)
        if (name in names) write(queue, names - name)
    }

    private fun write(queue: File, names: Set<String>) {
        if (names.isEmpty()) {
            queue.delete()
            return
        }
        queue.parentFile?.mkdirs()
        queue.writeText(names.joinToString("\n", postfix = "\n"))
    }
}
