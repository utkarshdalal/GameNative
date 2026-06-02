package app.gamenative.html5.savesync

import android.content.Context
import app.gamenative.html5.host.WebViewOrigin
import app.gamenative.utils.ContainerUtils
import java.io.File
import timber.log.Timber

// uninstall can't clear a game's localStorage in place: WebStorage.deleteOrigin leaves the LS leveldb untouched
// and chromium keeps that store open for the whole process once any WebView ran, so an
// iq80 purge then would be a second writer. uninstall queues the appId (its WebView origin derives from it, no container
// needed); the next boot purges that origin before any WebView opens. a game installed again is skipped at boot -- its
// live keys may be new -- and its first launch clears the origin page-side (ls-restore.js), which dequeues it.
object Html5PendingLsPurge {

    private const val TAG = "Html5PendingLsPurge"

    fun enqueue(context: Context, appId: String) = enqueue(queueFile(context), appId)

    fun isPending(context: Context, appId: String): Boolean = appId in read(queueFile(context))

    fun remove(context: Context, appId: String) = remove(queueFile(context), appId)

    // boot only, before any WebView opens (Html5LeveldbHealth). a failed purge throws and keeps the queue for the next boot.
    fun purgeAtBoot(context: Context): Int = purgeAtBoot(
        queue = queueFile(context),
        lsDir = File(context.dataDir, "app_webview/Default/Local Storage/leveldb"),
        isInstalled = { ContainerUtils.hasContainer(context, it) },
        originFor = WebViewOrigin::originUrl,
    )

    @Synchronized
    internal fun enqueue(queue: File, appId: String) {
        val appIds = read(queue)
        if (appIds.add(appId)) write(queue, appIds)
    }

    @Synchronized
    internal fun remove(queue: File, appId: String) {
        val appIds = read(queue)
        if (appIds.remove(appId)) write(queue, appIds)
    }

    @Synchronized
    internal fun purgeAtBoot(
        queue: File,
        lsDir: File,
        isInstalled: (String) -> Boolean,
        originFor: (String) -> String,
    ): Int {
        val (reinstalled, uninstalled) = read(queue).partition(isInstalled)
        if (uninstalled.isEmpty()) return 0
        val origins = uninstalled.mapTo(HashSet(), originFor)
        val purged = LevelDbRewriter.purgeLsOrigins(lsDir) { it in origins }
        write(queue, reinstalled.toSet())
        Timber.tag(TAG).i(
            "purged %d localStorage key(s) of %d uninstalled game(s); %d reinstalled left queued",
            purged, uninstalled.size, reinstalled.size,
        )
        return purged
    }

    private fun queueFile(context: Context) = File(context.filesDir, "html5/ls-pending-purge")

    private fun read(queue: File): MutableSet<String> =
        if (queue.isFile) queue.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()

    private fun write(queue: File, appIds: Set<String>) {
        if (appIds.isEmpty()) {
            queue.delete()
            return
        }
        queue.parentFile?.mkdirs()
        queue.writeText(appIds.joinToString("\n", postfix = "\n"))
    }
}
