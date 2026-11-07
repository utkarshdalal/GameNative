package app.gamenative.html5.host

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.html5.savesync.OriginCodec
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import timber.log.Timber

// per-container origin `http://<safeId>.localhost:<port>/`. chromium resolves `*.localhost` to loopback
// without DNS (RFC 6761), so a distinct host gives each container its own storage partition
// (IndexedDB / localStorage / OPFS) with a single server port.
//
// the port is part of the origin, so it must NEVER drift: storage (and saves) live under it. the first
// successful bind is recorded in a sentinel file and every later launch must bind exactly that port.
object WebViewOrigin {
    // dynamic/private port range per IANA (RFC 6335): 49152..65535.
    private const val PORT_RANGE_START = 49152
    private const val PORT_RANGE_SIZE = 16384 // 65536 - 49152

    // small so a busy device fails loudly instead of landing far from the deterministic port.
    private const val ALTERNATE_CANDIDATES = 16

    private const val TAG = "WebViewOrigin"

    @Volatile private var resolvedPort: Int = 0
    @Volatile private var initFailure: String? = null

    // on failure html5 is disabled for the session with a clear error rather than binding a drifted port.
    fun init(context: Context) {
        if (resolvedPort > 0) return
        runCatching {
            val sentinel = sentinelFile(context)
            val recorded = readSentinel(sentinel)
            if (recorded != null) {
                if (canBind(recorded)) {
                    Timber.tag(TAG).i("resolved html5 port=%d (from sentinel)", recorded)
                    resolvedPort = recorded
                    return@runCatching
                }
                // do NOT drift -- would orphan saves.
                initFailure = "html5 port $recorded busy (recorded by sentinel)"
                Timber.tag(TAG).e(
                    "sentinel recorded port=%d but bind failed — refusing to drift, html5 disabled this session",
                    recorded,
                )
                return@runCatching
            }
            val primary = deterministicPrimary()
            for (offset in 0..ALTERNATE_CANDIDATES) {
                val candidate = wrapToRange(primary + offset)
                if (canBind(candidate)) {
                    Timber.tag(TAG).i(
                        "resolved html5 port=%d (offset=%d from deterministic primary=%d)",
                        candidate, offset, primary,
                    )
                    writeSentinel(sentinel, candidate)
                    resolvedPort = candidate
                    return@runCatching
                }
            }
            initFailure = "no html5 port could be bound (tried $primary..${primary + ALTERNATE_CANDIDATES})"
            Timber.tag(TAG).e("%s — html5 disabled this session", initFailure)
        }.onFailure {
            initFailure = "html5 port resolution threw: ${it.message}"
            Timber.tag(TAG).e(it, "html5 port init failed")
        }
    }

    // callers gate html5 entry on this.
    fun initFailureMessage(): String? = initFailure

    fun ensurePortAllocated(): Int {
        if (resolvedPort > 0) return resolvedPort
        // pre-init fallback; consumers pick up the resolved value on their next read.
        return deterministicPrimary()
    }

    // `_` isn't valid in a DNS label and chromium's localhost handling is shaky with it. 1:1 because
    // container ids never contain `-`.
    fun safeIdFor(containerId: String): String =
        containerId.lowercase().replace('_', '-')

    fun hostFor(containerId: String): String = "${safeIdFor(containerId)}.localhost"

    fun originUrl(containerId: String): String =
        "http://${hostFor(containerId)}:${ensurePortAllocated()}"

    // chromium's leveldb filename form, e.g. http_steam-2738490.localhost_50123.
    fun levelDbPrefix(containerId: String): String =
        OriginCodec.filenameFromUrl(originUrl(containerId))

    private fun deterministicPrimary(): Int {
        val hash = BuildConfig.APPLICATION_ID.hashCode()
        val offset = ((hash % PORT_RANGE_SIZE) + PORT_RANGE_SIZE) % PORT_RANGE_SIZE
        return PORT_RANGE_START + offset
    }

    private fun wrapToRange(port: Int): Int {
        val offset = ((port - PORT_RANGE_START) % PORT_RANGE_SIZE + PORT_RANGE_SIZE) % PORT_RANGE_SIZE
        return PORT_RANGE_START + offset
    }

    // SO_REUSEADDR like the real server, so a TIME_WAIT port doesn't read as busy.
    private fun canBind(port: Int): Boolean = runCatching {
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
            true
        }
    }.getOrDefault(false)

    private fun sentinelFile(context: Context): File =
        File(File(context.filesDir, "html5"), "server-port")

    private fun readSentinel(file: File): Int? {
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText(Charsets.UTF_8).trim()
            text.toIntOrNull()?.takeIf { it in PORT_RANGE_START until (PORT_RANGE_START + PORT_RANGE_SIZE) }
        }.getOrNull()
    }

    private fun writeSentinel(file: File, port: Int) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(port.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "writeSentinel failed for port=%d (will re-resolve next launch)", port)
        }
    }
}
