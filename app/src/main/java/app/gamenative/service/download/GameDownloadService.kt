package app.gamenative.service.download

import app.gamenative.data.DepotInfo
import app.gamenative.data.DownloadInfo
import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Single entry point for ALL store game downloads in GameNative.
 *
 * Every real download pipeline runs in Rust inside `libgndownload.so`
 * (`app/src/main/cpp/gn-download/rust`); this service is the only Kotlin↔native boundary:
 *
 * - [downloadSteamApp] — Steam CDN depot downloads. JavaSteam stays the CM client: depot keys,
 *   manifest request codes and the CDN server list are resolved here through `SteamApps` /
 *   `SteamContent`, then the Rust engine (ported from Bannerlator's `bl-steam-client` depot
 *   pipeline) fetches manifests + chunks, decrypts/decompresses/verifies and writes files.
 *   The on-disk journal keeps the old JavaSteam DepotDownloader `.DepotDownloader/` format so
 *   in-progress downloads resume across the swap.
 * - [downloadGogChunks] / [downloadEpicChunks] / [downloadAmazonFiles] — the byte-fetching
 *   engines for the other stores; the store managers keep manifest/auth/post-install logic.
 */
object GameDownloadService {

    private const val TAG = "GameDownloadService"

    /** Thrown when a native download run finishes with `success = false` (not a cancel). */
    class DownloadFailedException(message: String) : Exception(message)

    // ─────────────────────────────────────────────────────────────────────────────
    // Steam
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Downloads (or, when [isUpdateOrVerify], re-verifies) [selectedDepots] of [appId] into
     * [installDir], reporting progress into [downloadInfo] exactly like the old
     * `DepotDownloader` listener did (per-depot delta bytes + per-depot fraction).
     *
     * Returns normally on success; throws [DownloadFailedException] on failure and
     * [kotlinx.coroutines.CancellationException] when the calling job is cancelled.
     */
    suspend fun downloadSteamApp(
        appId: Int,
        selectedDepots: Map<Int, DepotInfo>,
        branch: String,
        branchPassword: String?,
        installDir: String,
        isUpdateOrVerify: Boolean,
        depotIdToIndex: Map<Int, Int>,
        downloadInfo: DownloadInfo,
        maxWorkers: Int,
        processWorkers: Int,
        parentScope: CoroutineScope,
    ) {
        val steamClient = SteamService.instance?.steamClient
            ?: throw DownloadFailedException("Steam client not available")
        val steamApps = steamClient.getHandler(SteamApps::class.java)
            ?: throw DownloadFailedException("SteamApps handler not available")
        val steamContent = steamClient.getHandler(SteamContent::class.java)
            ?: throw DownloadFailedException("SteamContent handler not available")

        // ── 1. Resolve the CDN server list (JavaSteam CM call) ──────────────────
        val servers = resolveCdnServers(steamContent, parentScope)
        if (servers.isEmpty()) throw DownloadFailedException("No CDN servers available")

        // ── 2. Resolve per-depot (gid, depot key, manifest request code) ────────
        val depotsJson = JSONArray()
        val resolvedDepotIds = mutableListOf<Int>()
        for ((depotId, depot) in selectedDepots.toSortedMap()) {
            val gid = resolveManifestGid(steamApps, appId, depotId, depot, branch, branchPassword)
            if (gid == 0L) {
                Timber.tag(TAG).w("Skipping depot $depotId: no manifest gid for branch $branch")
                continue
            }

            val keyCallback = steamApps.getDepotDecryptionKey(depotId, appId).await()
            if (keyCallback.result != EResult.OK || keyCallback.depotKey.size != 32) {
                Timber.tag(TAG).w("Skipping depot $depotId: depot key denied (${keyCallback.result})")
                continue
            }

            val requestCode = fetchManifestRequestCode(
                steamContent, depotId, appId, gid, branch, parentScope,
            )

            depotsJson.put(
                JSONObject()
                    .put("depot_id", depotId)
                    // gid/code are uint64 in Steam's protocol but signed Longs here; send them
                    // as unsigned decimal strings so high-bit values survive JSON → Rust.
                    .put("manifest_id", java.lang.Long.toUnsignedString(gid))
                    .put("depot_key_hex", keyCallback.depotKey.toHex())
                    .put("manifest_request_code", java.lang.Long.toUnsignedString(requestCode)),
            )
            resolvedDepotIds.add(depotId)
        }
        if (depotsJson.length() == 0) {
            throw DownloadFailedException("No entitled depots to download")
        }

        val plan = JSONObject()
            .put("install_dir", installDir)
            .put("ca_bundle_path", "")
            // fresh = discard the journal entries for these depots and re-validate every
            // existing chunk on disk — the old engine's update/verify semantics.
            .put("fresh", isUpdateOrVerify)
            .put("max_workers", maxWorkers)
            .put("process_workers", processWorkers)
            .put("servers", serversToJson(servers))
            .put("depots", depotsJson)
            .toString()

        // ── 3. Run the native engine, mapping callbacks onto DownloadInfo ────────
        runNativeSteamDownload(
            plan = plan,
            appId = appId,
            branch = branch,
            steamContent = steamContent,
            depotIdToIndex = depotIdToIndex,
            downloadInfo = downloadInfo,
            parentScope = parentScope,
        )
    }

    private suspend fun runNativeSteamDownload(
        plan: String,
        appId: Int,
        branch: String,
        steamContent: SteamContent,
        depotIdToIndex: Map<Int, Int>,
        downloadInfo: DownloadInfo,
        parentScope: CoroutineScope,
    ) {
        // Track cumulative per-depot bytes to calculate deltas (same unit the engine reports:
        // decompressed chunk bytes written; totalExpectedBytes stays the manifest download size,
        // matching the old listener's approximation).
        val depotCumulativeBytes = ConcurrentHashMap<Int, Long>()
        // First progress callback ends the "Preparing depots" key-prep phase (owner-pinned, so a
        // late callback from an unwound run cannot wipe a newer attempt's message).
        val keyPrepCleared = AtomicBoolean(false)

        val listener = object : NativeSteamDownloadListener {
            override fun onProgress(
                depotId: Int,
                depotDone: Long,
                depotTotal: Long,
                depotsDone: Int,
                depotsTotal: Int,
                verifying: Boolean,
            ) {
                if (keyPrepCleared.compareAndSet(false, true)) {
                    SteamService.clearDepotKeyPrep(downloadInfo.gameId, owner = downloadInfo)
                }
                val previous = depotCumulativeBytes.put(depotId, depotDone) ?: 0L
                val delta = depotDone - previous
                if (delta > 0L) {
                    downloadInfo.updateBytesDownloaded(delta, System.currentTimeMillis())
                }
                if (depotTotal > 0L) {
                    depotIdToIndex[depotId]?.let { index ->
                        downloadInfo.setProgress(
                            (depotDone.toFloat() / depotTotal.toFloat()).coerceIn(0f, 1f),
                            index,
                        )
                    }
                }
                downloadInfo.persistProgressSnapshot()
            }

            override fun refreshManifestRequestCode(depotId: Int, manifestId: Long): Long {
                return try {
                    kotlinx.coroutines.runBlocking {
                        fetchManifestRequestCode(
                            steamContent, depotId, appId, manifestId, branch, this,
                        )
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "refreshManifestRequestCode failed for depot $depotId")
                    0L
                }
            }

            override fun onComplete(
                success: Boolean,
                error: String,
                bytesWritten: Long,
                depotsCompleted: Int,
                depotsSkipped: Int,
            ) = Unit // handled by the suspension below
        }

        suspendCancellableCoroutine { cont ->
            val completionListener = object : NativeSteamDownloadListener by listener {
                override fun onComplete(
                    success: Boolean,
                    error: String,
                    bytesWritten: Long,
                    depotsCompleted: Int,
                    depotsSkipped: Int,
                ) {
                    if (success) {
                        Timber.tag(TAG).i(
                            "Steam download for app $appId complete: $bytesWritten bytes, " +
                                "$depotsCompleted depots completed, $depotsSkipped skipped",
                        )
                        if (cont.isActive) cont.resume(Unit)
                    } else {
                        if (cont.isActive) {
                            cont.resumeWithException(DownloadFailedException(error.ifEmpty { "download failed" }))
                        }
                    }
                }
            }

            val handle = NativeSteamDownload.start(plan, completionListener)
            if (handle == 0L) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        DownloadFailedException("native Steam engine failed to start"),
                    )
                }
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                NativeSteamDownload.cancel(handle)
            }
        }
    }

    private suspend fun resolveCdnServers(
        steamContent: SteamContent,
        parentScope: CoroutineScope,
    ): List<Server> {
        return try {
            val cellId = runCatching { app.gamenative.PrefManager.cellId }.getOrDefault(0)
            steamContent.getServersForSteamPipe(
                cellId = cellId.takeIf { it > 0 },
                maxNumServers = 20,
                parentScope = parentScope,
            ).await()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GetServersForSteamPipe failed")
            emptyList()
        }
    }

    private fun serversToJson(servers: List<Server>): JSONArray {
        val out = JSONArray()
        for (server in servers) {
            val host = server.host ?: continue
            if (host.isEmpty() || server.steamChinaOnly) continue
            out.put(
                JSONObject()
                    .put("host", host)
                    .put("vhost", server.vHost ?: "")
                    .put("type", server.type ?: "")
                    .put("cell_id", server.cellId)
                    .put("steam_china_only", false)
                    .put(
                        "https_support",
                        if (server.protocol == Server.ConnectionProtocol.HTTPS) "mandatory" else "",
                    ),
            )
        }
        return out
    }

    private suspend fun fetchManifestRequestCode(
        steamContent: SteamContent,
        depotId: Int,
        appId: Int,
        manifestId: Long,
        branch: String,
        parentScope: CoroutineScope,
    ): Long {
        return try {
            steamContent.getManifestRequestCode(
                depotId = depotId,
                appId = appId,
                manifestId = manifestId,
                branch = branch,
                branchPasswordHash = null,
                parentScope = parentScope,
            ).await()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "getManifestRequestCode failed for depot $depotId")
            0L
        }
    }

    /**
     * Resolves the manifest gid for [depot] on [branch], including password-protected beta
     * branches (via `checkAppBetaPassword` + `picsGetPrivateBeta`, mirroring the old
     * DepotDownloader's private-beta depot-section path).
     */
    private suspend fun resolveManifestGid(
        steamApps: SteamApps,
        appId: Int,
        depotId: Int,
        depot: DepotInfo,
        branch: String,
        branchPassword: String?,
    ): Long {
        depot.manifests[branch]?.gid?.takeIf { it != 0L }?.let { return it }

        if (branch.equals("public", ignoreCase = true)) {
            return 0L
        }

        // Non-passworded branch with no gid → nothing to do.
        if (branchPassword.isNullOrBlank()) {
            // Last-resort: public manifest (keeps the old weight-calculation fallback alive).
            return depot.manifests["public"]?.gid ?: 0L
        }

        // Passworded branch: decrypt via the private beta depot section.
        return try {
            val keys = SteamService.checkPrivateBranchPassword(appId, branchPassword)
            val branchKey = keys[branch] ?: keys.values.firstOrNull() ?: return 0L
            val accessToken = steamApps.picsGetAccessTokens(appId).await()
                .appTokens[appId] ?: 0L
            val privateBeta = steamApps.picsGetPrivateBeta(
                appId, accessToken, branch, branchKey,
            ).await()
            val gidNode = privateBeta.depotSection[depotId.toString()]["manifests"][branch]["gid"]
            gidNode.asUnsignedLong()?.toLong() ?: 0L
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "private beta gid resolution failed for depot $depotId")
            0L
        }
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (b in this) {
            out.append(digits[(b.toInt() shr 4) and 0xf])
            out.append(digits[b.toInt() and 0xf])
        }
        return out.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // GOG
    // ─────────────────────────────────────────────────────────────────────────────

    /** True when the native GOG engine is loaded. */
    fun isGogNativeAvailable(): Boolean = NativeGogDownload.isAvailable()

    /** Starts a native GOG chunk-download loop (base install, DLC, or redist assembly). */
    fun downloadGogChunks(
        kind: Int,
        depotManifests: Array<String>,
        cdnBase: String,
        installDir: String,
        skipPaths: Array<String>,
        maxWorkers: Int,
        processWorkers: Int,
        sortLargestFirst: Boolean,
        label: String,
        listener: NativeGogDownloadListener,
    ): Long = NativeGogDownload.start(
        kind, depotManifests, cdnBase, installDir, skipPaths, "",
        maxWorkers, processWorkers, sortLargestFirst, label, listener,
    )

    fun cancelGogDownload(handle: Long) = NativeGogDownload.cancel(handle)

    fun releaseGogDownload(handle: Long) = NativeGogDownload.release(handle)

    // ─────────────────────────────────────────────────────────────────────────────
    // Epic
    // ─────────────────────────────────────────────────────────────────────────────

    /** Blocking Epic chunk fetch for `pendingFileIdx` (fills `<installDir>/.chunks`). */
    fun downloadEpicChunks(
        manifest: ByteArray,
        installDir: String,
        cdnPrefixes: Array<String>,
        pendingFileIdx: IntArray,
        expectedChunks: Int,
        expectedBytes: Long,
        maxWorkers: Int,
        processWorkers: Int,
        cancel: AtomicBoolean?,
        listener: NativeEpicDownload.Listener,
    ): NativeEpicDownload.Result = NativeEpicDownload.run(
        manifest, installDir, cdnPrefixes, pendingFileIdx, expectedChunks, expectedBytes, "",
        maxWorkers, processWorkers, cancel, listener,
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Amazon
    // ─────────────────────────────────────────────────────────────────────────────

    /** Blocking Amazon file-download run. `planJson` = `[{relPath, url, size, sha256hex}]`. */
    fun downloadAmazonFiles(
        planJson: String,
        installDir: String,
        maxWorkers: Int,
        processWorkers: Int,
        isCancelled: NativeAmazonCancelCheck?,
        listener: NativeAmazonDownloadListener,
    ): NativeAmazonDownload.RunResult = NativeAmazonDownload.runBlocking(
        planJson, installDir, "", maxWorkers, processWorkers, isCancelled, listener,
    )
}
