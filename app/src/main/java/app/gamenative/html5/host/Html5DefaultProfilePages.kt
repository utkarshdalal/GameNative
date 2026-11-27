package app.gamenative.html5.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import app.gamenative.html5.asar.AppSignInHosts
import app.gamenative.html5.asar.ProtocolServedPage
import app.gamenative.html5.savesync.Html5PendingLsPurge
import app.gamenative.html5.shim.ElectronMainBridge
import app.gamenative.utils.ContainerUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

// a protocol-served page on a WebView without profiles (Html5PageProfiles) runs in the default profile. the hosts it
// ran at are kept per container: at uninstall the game files, and so the page, may be gone. reset and uninstall clear
// those hosts' cookies and storage there; localStorage goes through Html5PendingLsPurge like a loopback origin's.
object Html5DefaultProfilePages {

    private const val TAG = "Html5DefaultProfilePages"

    private val serviceWorkerHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var serviceWorkerGuardSet = false

    fun hostsOf(page: ProtocolServedPage): Set<String> =
        (page.aliasHosts + page.host).mapTo(LinkedHashSet()) { it.lowercase() }.filterNotTo(LinkedHashSet(), AppSignInHosts::covers)

    fun originOf(host: String): String = "https://$host"

    // at launch, on the main thread with WebView loaded.
    @SuppressLint("RequiresFeature")
    fun record(context: Context, containerId: String, page: ProtocolServedPage) {
        val hosts = hostsOf(page)
        PageRecords.put(recordFile(context), containerId, hosts)
        serviceWorkerHosts += hosts
        if (!serviceWorkerGuardSet) {
            runCatching { ServiceWorkerController.getInstance().setServiceWorkerClient(PageHostWorkers) }
                .onSuccess { serviceWorkerGuardSet = true }
                .onFailure { Timber.tag(TAG).w(it, "service worker guard not set") }
        }
    }

    // the page blocks registration itself (no-service-worker.js); a worker that got in anyway fetches nothing.
    // every other default-profile worker is left alone.
    private object PageHostWorkers : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
            if (request.url.host?.lowercase() in serviceWorkerHosts) Html5PageProfiles.forbidden() else null
    }

    // reset clears the container's hosts; uninstall only those no other installed container ran at.
    fun clear(context: Context, containerId: String, uninstall: Boolean) {
        val app = context.applicationContext
        val file = recordFile(app)
        val records = PageRecords.read(file)
        if (containerId !in records) return
        val hosts = if (uninstall) {
            PageRecords.ownHosts(records, containerId) { it != containerId && ContainerUtils.hasContainer(app, it) }
        } else {
            records.getValue(containerId)
        }
        if (hosts.isEmpty()) {
            PageRecords.remove(file, containerId)
            return
        }
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val jar = ElectronMainBridge.SignInGuardJar(ElectronMainBridge.ProfileJar(CookieManager.getInstance()))
                val cookies = hosts.sumOf { ElectronMainBridge.expireAll(jar, it) }
                jar.flush()
                hosts.forEach { WebStorage.getInstance().deleteOrigin(originOf(it)) }
                Timber.tag(TAG).i("cleared %s for %s: %d cookie(s)", hosts, containerId, cookies)
            }.onFailure { Timber.tag(TAG).w(it, "default-profile clear failed for %s", containerId) }
        }
        hosts.forEach { ContainerUtils.deleteOriginFiles(app, originOf(it)) }
        // localStorage: purged at the next html5 init once uninstalled, else cleared by the page's next launch.
        Html5PendingLsPurge.enqueue(app, containerId)
    }

    fun originsToPurge(context: Context, containerId: String, isInstalled: (String) -> Boolean): List<String> =
        PageRecords.ownHosts(PageRecords.read(recordFile(context)), containerId) { it != containerId && isInstalled(it) }
            .map(::originOf)

    // after the localStorage purge: records of uninstalled containers it no longer needs.
    fun forgetPurged(context: Context, isPending: (String) -> Boolean, isInstalled: (String) -> Boolean) {
        val file = recordFile(context)
        PageRecords.read(file).keys.filter { !isInstalled(it) && !isPending(it) }.forEach { PageRecords.remove(file, it) }
    }

    private fun recordFile(context: Context) = File(context.filesDir, "html5/default-profile-pages")
}

// one line per container: "<containerId> <host> <host>...".
internal object PageRecords {
    @Synchronized
    fun read(file: File): Map<String, Set<String>> {
        if (!file.isFile) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val parts = line.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size < 2) null else parts[0] to parts.drop(1).toSet()
        }.toMap()
    }

    @Synchronized
    fun put(file: File, containerId: String, hosts: Set<String>) {
        val records = read(file)
        if (records[containerId] == hosts) return
        write(file, records + (containerId to hosts))
    }

    @Synchronized
    fun remove(file: File, containerId: String) {
        val records = read(file)
        if (containerId in records) write(file, records - containerId)
    }

    // the container's hosts that no container `others` accepts also ran at.
    fun ownHosts(records: Map<String, Set<String>>, containerId: String, others: (String) -> Boolean): Set<String> {
        val shared = records.filterKeys(others).values.flatten().toSet()
        return records[containerId].orEmpty() - shared
    }

    private fun write(file: File, records: Map<String, Set<String>>) {
        val lines = records.filterValues { it.isNotEmpty() }.map { (id, hosts) -> (listOf(id) + hosts).joinToString(" ") }
        if (lines.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
