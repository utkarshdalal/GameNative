package app.gamenative.html5.host

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.html5.savesync.OriginCodec
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import timber.log.Timber

// per-container synthetic origin served by the loopback HTTP server (Html5LocalHttpServer)
// at `http://<safeId>.localhost:<port>/`. RFC 6761: chromium resolves any host matching
// `*.localhost` to loopback (127.0.0.1 / ::1) internally without DNS, so each container
// gets its own origin via the host portion alone -- no DNS, no /etc/hosts trickery, no
// per-container port allocation.
//
// chromium origin = scheme + host + port. distinct host → distinct origin → automatic
// per-container partition for IndexedDB / LocalStorage / cookies / OPFS, with no
// dependency on the multi-profile API (which is firmware-locked-out on some devices).
//
// safeId normalization: container.id contains underscores (`STEAM_2738490`) which are
// invalid in DNS labels per RFC 1035 [a-zA-Z0-9-]. chromium's URL parser accepts them but
// the localhost shortcut resolution is shaky on hosts with `_`. underscore→hyphen +
// lowercase is the minimum to stay strictly RFC-compatible. mapping is 1:1 because no
// existing container.id uses `-` (only `_`, alphanumeric).
//
// port lifecycle:
// 1. PluviaApp.onCreate calls init(context). resolves the port once per process.
// 2. resolvePort reads <filesDir>/html5/server-port (the sentinel) FIRST. if present, it
//    must-bind that port -- drift would orphan saves that landed under the previous origin.
// 3. no sentinel = first launch (or post-clear-data). try deterministic primary derived
//    from BuildConfig.APPLICATION_ID hash; on collision walk N deterministic alternates;
//    first success is recorded to the sentinel and used forever after.
// 4. consumers (save-sync, server bind, AssetLoader) read via ensurePortAllocated() which
//    returns the cached value.
//
// chromium encodes the port into leveldb filenames as `http_<host>_<port>`; the sentinel
// guarantees the same install always resolves to the same port even if DataStore is wiped.
// .debug-suffixed builds get a different applicationId hash → different port from release.
//
// single source of truth so WebViewScreen's loadUrl, the local server's bind target, and
// save-sync leveldb prefix all stay consistent.
object WebViewOrigin {
    // dynamic/private port range per IANA (RFC 6335): 49152..65535.
    private const val PORT_RANGE_START = 49152
    private const val PORT_RANGE_SIZE = 16384 // 65536 - 49152

    // alternates we walk when primary is busy on first-launch. cap small so a degenerate
    // device (16 ports busy) falls through to a loud failure rather than silently drifting
    // far from the deterministic value. 16 ≈ 0.1% of the dynamic range.
    private const val ALTERNATE_CANDIDATES = 16

    private const val TAG = "WebViewOrigin"

    @Volatile private var resolvedPort: Int = 0
    @Volatile private var initFailure: String? = null

    // PluviaApp.onCreate calls this once. resolves the port via sentinel/deterministic walk
    // and caches the result. failure path sets initFailure so html5 surfaces a clear error
    // (see html5RuntimeDisabled flag) instead of silently binding to a drifted port.
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
                // sentinel says X but X is busy. do NOT drift -- would orphan saves.
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

    // null = port resolved cleanly; non-null = init refused to drift (sentinel busy or
    // exhausted alternates). callers gate html5 entry on this.
    fun initFailureMessage(): String? = initFailure

    fun ensurePortAllocated(): Int {
        if (resolvedPort > 0) return resolvedPort
        // pre-init fallback: return the deterministic primary so save-sync paths computed
        // before init() runs at least share the same algorithm. once init() resolves, this
        // value is replaced atomically -- any pre-init consumer is corrected on its next read.
        return deterministicPrimary()
    }

    // RFC 1035 label normalization: underscores → hyphens, lowercase. container.id chars
    // are bounded (uppercase alpha + digits + `_`) so the regex stays trivial.
    fun safeIdFor(containerId: String): String =
        containerId.lowercase().replace('_', '-')

    fun hostFor(containerId: String): String = "${safeIdFor(containerId)}.localhost"

    fun originUrl(containerId: String): String =
        "http://${hostFor(containerId)}:${ensurePortAllocated()}"

    // chromium encodes origin into leveldb filenames as scheme_host_port. for
    // http://steam-2738490.localhost:50123 → http_steam-2738490.localhost_50123.
    fun levelDbPrefix(containerId: String): String =
        OriginCodec.filenameFromUrl(originUrl(containerId))

    // ---------------- internals ----------------

    private fun deterministicPrimary(): Int {
        val hash = BuildConfig.APPLICATION_ID.hashCode()
        val offset = ((hash % PORT_RANGE_SIZE) + PORT_RANGE_SIZE) % PORT_RANGE_SIZE
        return PORT_RANGE_START + offset
    }

    private fun wrapToRange(port: Int): Int {
        val offset = ((port - PORT_RANGE_START) % PORT_RANGE_SIZE + PORT_RANGE_SIZE) % PORT_RANGE_SIZE
        return PORT_RANGE_START + offset
    }

    // bind+close test. SO_REUSEADDR mirrors what Html5LocalHttpServer uses so the test
    // matches real bind behavior (TIME_WAIT after a back-out doesn't false-positive).
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
            // atomic rename via tmp file so a crash mid-write doesn't leave a truncated value.
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
