package app.gamenative.service.download

import app.gamenative.data.DepotInfo
import app.gamenative.R
import app.gamenative.data.DownloadInfo
import app.gamenative.data.SteamApp
import app.gamenative.service.SteamService
import app.gamenative.utils.generateSteamApp
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import android.content.Context
import android.content.Intent
import app.gamenative.BuildConfig
import app.gamenative.data.GameSource
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.Volatile

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

    private const val CDN_AUTH_TOKEN_TIMEOUT_MS = 30_000L

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
     * Depots Steam refuses to serve (no manifest gid for the branch, depot key denied —
     * e.g. a DLC the account doesn't own) are skipped, not fatal.
     *
     * Returns the ids of the depots that were actually downloaded, so the caller only
     * marks those complete (a skipped depot must NOT be recorded as downloaded — it
     * would be filtered out as "already downloaded" forever after).
     * Throws [DownloadFailedException] when not a single depot was servable, and
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
        val resolvedDepots = coroutineScope {
            selectedDepots.toSortedMap().map { (depotId, depot) ->
                async {
                    resolveDepotForDownload(
                        steamApps, steamContent, appId, depotId, depot, branch, branchPassword, parentScope,
                    )
                }
            }.awaitAll()
        }.filterNotNull()
        for (resolved in resolvedDepots) {
            depotsJson.put(
                JSONObject()
                    .put("depot_id", resolved.depotId)
                    // gid/code are uint64 in Steam's protocol but signed Longs here; send them
                    // as unsigned decimal strings so high-bit values survive JSON → Rust.
                    .put("manifest_id", java.lang.Long.toUnsignedString(resolved.gid))
                    .put("depot_key_hex", resolved.depotKeyHex)
                    .put("manifest_request_code", java.lang.Long.toUnsignedString(resolved.requestCode)),
            )
            resolvedDepotIds.add(resolved.depotId)
        }
        if (depotsJson.length() == 0) {
            throw DownloadFailedException(
                "No entitled depots to download " +
                    "(all ${selectedDepots.size} selected depot(s) skipped: ${selectedDepots.keys.sorted()})",
            )
        }

        val plan = JSONObject()
            .put("install_dir", installDir)
            .put("ca_bundle_path", "")
            // fresh = discard the journal entries for these depots and re-validate every
            // existing chunk on disk — the old engine's update/verify semantics.
            .put("fresh", isUpdateOrVerify)
            .put("max_workers", maxWorkers)
            .put("process_workers", processWorkers)
            .put("pipeline_logs", SHOW_PIPELINE_LOGS)
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
            depotIdToOwningAppId = selectedDepots.mapValues { (_, depot) ->
                when {
                    depot.dlcAppId != SteamService.INVALID_APP_ID -> depot.dlcAppId
                    depot.depotFromApp != SteamService.INVALID_APP_ID -> depot.depotFromApp
                    else -> appId
                }
            },
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
        depotIdToOwningAppId: Map<Int, Int>,
        downloadInfo: DownloadInfo,
        parentScope: CoroutineScope,
    ) {
        // Track cumulative per-depot bytes to calculate deltas (same unit the engine reports:
        // decompressed chunk bytes written; totalExpectedBytes is the uncompressed depot size
        // set in SteamService — same unit, so the bar reaches 100% exactly at completion).
        val depotCumulativeBytes = ConcurrentHashMap<Int, Long>()
        // First progress callback ends the "Preparing depots" key-prep phase (owner-pinned, so a
        // late callback from an unwound run cannot wipe a newer attempt's message).
        val keyPrepCleared = AtomicBoolean(false)
        // Set while the engine is re-hashing on-disk chunks (resume/verify): the status row
        // shows "Verifying <file>". Cleared on the first real download progress.
        val verifyStatusActive = AtomicBoolean(false)

        val listener = object : NativeSteamDownloadListener {
            override fun onVerifying(path: String) {
                verifyStatusActive.set(true)
                val svc = SteamService.instance ?: return
                downloadInfo.updateStatusMessage(svc.getString(R.string.download_verifying_file, path))
            }

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
                if (!verifying && verifyStatusActive.compareAndSet(true, false)) {
                    // Verify sweep finished, real chunks are flowing — drop the verify status.
                    downloadInfo.updateStatusMessage(null)
                }
                if (verifying) {
                    // Verified-existing bytes: these were already counted in the persisted
                    // snapshot this run resumed from. Crediting them again double-counts and
                    // the bar races to 100% while remaining chunks are still downloading.
                    // Only track the high-water so later real downloads delta from it.
                    depotCumulativeBytes.merge(depotId, depotDone, ::maxOf)
                } else {
                    // Parallel callbacks can arrive out of order; read-check-set must be
                    // atomic or two threads credit overlapping deltas. Clamp to the
                    // high-water so a late, smaller depotDone credits nothing.
                    val delta = synchronized(depotCumulativeBytes) {
                        val previous = depotCumulativeBytes[depotId] ?: 0L
                        val newHigh = maxOf(previous, depotDone)
                        depotCumulativeBytes[depotId] = newHigh
                        newHigh - previous
                    }
                    if (delta > 0L) {
                        downloadInfo.updateBytesDownloaded(delta, System.currentTimeMillis())
                    }
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
                // Manifest request codes are app-scoped like depot keys: use the
                // depot's owning app (DLC depots fail under the parent app id).
                val owningAppId = depotIdToOwningAppId[depotId] ?: appId
                return try {
                    kotlinx.coroutines.runBlocking {
                        fetchManifestRequestCode(
                            steamContent, depotId, owningAppId, manifestId, branch, this,
                        )
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "refreshManifestRequestCode failed for depot $depotId")
                    0L
                }
            }

            override fun getCdnAuthToken(depotId: Int, host: String): String? {
                // CDN auth tokens are app-scoped like depot keys: use the depot's owning
                // app (DLC depots fail under the parent app id).
                val owningAppId = depotIdToOwningAppId[depotId] ?: appId
                return try {
                    kotlinx.coroutines.runBlocking {
                        val auth = kotlinx.coroutines.withTimeoutOrNull(CDN_AUTH_TOKEN_TIMEOUT_MS) {
                            steamContent.getCDNAuthToken(
                                app = owningAppId,
                                depot = depotId,
                                hostName = host,
                                parentScope = this,
                            ).await()
                        }
                        if (auth == null) {
                            Timber.tag(TAG).w("getCDNAuthToken timed out for depot $depotId host $host")
                            null
                        } else if (auth.result == EResult.OK && auth.token.isNotEmpty()) {
                            Timber.tag(TAG).i("CDN auth token issued for depot $depotId host $host (expires ${auth.expiration})")
                            auth.token
                        } else {
                            Timber.tag(TAG).w("getCDNAuthToken for depot $depotId host $host: ${auth.result}")
                            null
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "getCdnAuthToken failed for depot $depotId host $host")
                    null
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

        // The handle owns a registry slot in native; release it exactly once no matter
        // how the run ends (success, failure, or cancellation).
        var handle = 0L
        try {
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

                handle = NativeSteamDownload.start(plan, completionListener)
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
        } finally {
            if (handle != 0L) {
                NativeSteamDownload.release(handle)
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

    // Cache of owning-app PICS data for shared depots (e.g. Steamworks Common
    // Redistributables app 228980), keyed by app id. Shared between depots in one run.
    // Plain map (nulls = "fetch failed"): the depot-resolution loop is sequential.
    private val owningAppInfoCache = java.util.Collections.synchronizedMap(HashMap<Int, SteamApp?>())

    /**
     * Resolves the manifest gid for [depot] on [branch], including password-protected beta
     * branches (via `checkAppBetaPassword` + `picsGetPrivateBeta`, mirroring the old
     * DepotDownloader's private-beta depot-section path).
     */
    private class ResolvedDepot(
        val depotId: Int,
        val gid: Long,
        val depotKeyHex: String,
        val requestCode: Long,
    )

    private suspend fun resolveDepotForDownload(
        steamApps: SteamApps,
        steamContent: SteamContent,
        appId: Int,
        depotId: Int,
        depot: DepotInfo,
        branch: String,
        branchPassword: String?,
        parentScope: CoroutineScope,
    ): ResolvedDepot? {
        val gid = resolveManifestGid(steamApps, appId, depotId, depot, branch, branchPassword)
        if (gid == 0L) {
            Timber.tag(TAG).w("Skipping depot $depotId: no manifest gid for branch $branch")
            return null
        }

        // Depot keys are granted to the app that OWNS the depot, not necessarily
        // the app being downloaded: DLC depots (e.g. Vampire Survivors' 2230761,
        // owned by DLC app 2230760) are refused with FileNotFound when requested
        // as the parent app, even though the account owns the DLC. Ask the owning
        // app first (dlcAppId, then depotfromapp), fall back to the parent app.
        val owningAppId = when {
            depot.dlcAppId != SteamService.INVALID_APP_ID -> depot.dlcAppId
            depot.depotFromApp != SteamService.INVALID_APP_ID -> depot.depotFromApp
            else -> appId
        }
        var keyCallback = steamApps.getDepotDecryptionKey(depotId, owningAppId).await()
        if ((keyCallback.result != EResult.OK || keyCallback.depotKey.size != 32) && owningAppId != appId) {
            Timber.tag(TAG).d("Depot $depotId key denied as owning app $owningAppId (${keyCallback.result}), retrying as $appId")
            keyCallback = steamApps.getDepotDecryptionKey(depotId, appId).await()
        }
        if (keyCallback.result != EResult.OK || keyCallback.depotKey.size != 32) {
            val dlcNote = if (depot.dlcAppId != SteamService.INVALID_APP_ID) {
                " (depot belongs to DLC app ${depot.dlcAppId} — not owned by this account?)"
            } else {
                ""
            }
            Timber.tag(TAG).w("Skipping depot $depotId: depot key denied (${keyCallback.result})$dlcNote")
            return null
        }

        val requestCode = fetchManifestRequestCode(
            steamContent, depotId, owningAppId, gid, branch, parentScope,
        )
        return ResolvedDepot(depotId, gid, keyCallback.depotKey.toHex(), requestCode)
    }

    private suspend fun resolveManifestGid(
        steamApps: SteamApps,
        appId: Int,
        depotId: Int,
        depot: DepotInfo,
        branch: String,
        branchPassword: String?,
    ): Long {
        depot.manifests[branch]?.gid?.takeIf { it != 0L }?.let { return it }

        // Shared depot carrying no manifests of its own (e.g. depot 228990 Steamworks
        // Common Redistributables, listed under the game with sharedinstall + no
        // manifest section): the manifest data lives in the OWNING app's depot
        // section. JavaSteam's getSteam3DepotManifest recurses into depotfromapp for
        // exactly this case — the depot must be resolved, never skipped.
        if (depot.manifests.isEmpty()) {
            val owningAppId = when {
                depot.depotFromApp != SteamService.INVALID_APP_ID -> depot.depotFromApp
                depot.dlcAppId != SteamService.INVALID_APP_ID -> depot.dlcAppId
                else -> appId
            }
            if (owningAppId != appId) {
                resolveOwningAppManifestGid(steamApps, owningAppId, depotId, branch)
                    ?.let { return it }
            }
        }

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

    /**
     * Resolves a shared depot's manifest gid from its OWNING app's depot section —
     * JavaSteam's `getSteam3DepotManifest` recursion for depots with no `manifests`
     * node but a `depotfromapp`. Reads the local app-info DB first; on a miss, makes
     * one live PICS request for the owning app (cached per run).
     */
    private suspend fun resolveOwningAppManifestGid(
        steamApps: SteamApps,
        owningAppId: Int,
        depotId: Int,
        branch: String,
    ): Long? {
        val owningApp = owningAppInfoCache.getOrPut(owningAppId) {
            SteamService.getAppInfoOf(owningAppId) ?: fetchAppInfoLive(steamApps, owningAppId)
        } ?: return null
        val manifests = owningApp.depots[depotId]?.manifests ?: return null
        val gid = manifests[branch]?.gid ?: manifests["public"]?.gid ?: return null
        return gid.takeIf { it != 0L }?.also {
            Timber.tag(TAG).i("Depot $depotId: manifest gid $it resolved via owning app $owningAppId")
        }
    }

    /** One live PICS fetch for an app missing from the local DB (shared redist apps). */
    private suspend fun fetchAppInfoLive(
        steamApps: SteamApps,
        appId: Int,
    ): SteamApp? = try {
        steamApps.picsGetProductInfo(
            apps = listOf(PICSRequest(id = appId)),
            packages = emptyList(),
        ).await().results.firstOrNull()?.apps?.values?.firstOrNull()
            ?.keyValues?.generateSteamApp()
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "live PICS fetch failed for owning app $appId")
        null
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

    // ═════════════════════════════════════════════════════════════════════════
    // Download queue (one active download across Steam/Epic/GOG/Amazon, queue-
    // managed auto-pause/resume, wake-lock while transferring). Merged from the
    // former GameDownloadService object.
    // ═════════════════════════════════════════════════════════════════════════

    data class DownloadEntry(
        val gameSource: GameSource,
        val gameId: String,
        val downloadInfo: DownloadInfo?,
        val dlcGameIds: List<Int>,
        val installPath: String?,
        val containerLanguage: String?,
    )

    @Volatile
    private var currentDownloadingKey: String? = null
    private val downloadQueue = CopyOnWriteArrayList<String>()
    private val registeredDownloads = ConcurrentHashMap<String, DownloadEntry>()

    /**
     * Hardcoded switch for native engine pipeline logs (throughput / fetch-window / staging
     * lines). Steam emits them from Rust via android_log (tag GN_STEAM_DL) — the flag travels
     * in the plan JSON so no JNI callback is even wired when off; Epic/GOG/Amazon forward their
     * lines over JNI `onLog`, gated at the Timber call sites in their managers.
     */
    val SHOW_PIPELINE_LOGS = BuildConfig.DEBUG

    /**
     * Serializes every queue state transition (pause-all + register, remove + resume).
     * Without it, two concurrent registrations can each scan before either inserts and
     * BOTH stay active, breaking the one-at-a-time contract.
     */
    private val queueLock = Any()

    /**
     * Runs the deferred queue-resume store startups (DB / container / disk work — Epic
     * even `runBlocking`s a Room query), so they never run on the caller's thread of
     * unregister/remove (which can be the main thread) and never inside [queueLock].
     */
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun makeKey(gameSource: GameSource, gameId: String): String {
        return "${gameSource.name}_$gameId"
    }

    /**
     * Register a new download. This will auto-pause all other active downloads.
     * Should only be called by Each Service once (4 callees)
     */
    fun registerDownload(
        gameSource: GameSource,
        gameId: String,
        downloadInfo: DownloadInfo,
        dlcGameIds: List<Int> = emptyList(),
        installPath: String? = null,
        containerLanguage: String? = null,
    ) {
        val key = makeKey(gameSource, gameId)

        synchronized(queueLock) {
            // Auto-pause all other active downloads. Skip entries whose transfer is
            // already done and which are only syncing saves (post-install). Enqueued
            // placeholders have no DownloadInfo yet — nothing to pause.
            registeredDownloads.forEach { (existingKey, entry) ->
                if (existingKey != key && currentDownloadingKey != key && entry.downloadInfo?.isPostInstallSyncing() != true) {
                    Timber.i("[GameDownloadService] Auto-pausing ${entry.gameSource} download for ${entry.gameId}")
                    entry.downloadInfo?.cancel(message = "Paused for new download")
                }
            }

            // Register / Update the download hash map
            registeredDownloads[key] = DownloadEntry(
                gameSource = gameSource,
                gameId = gameId,
                downloadInfo = downloadInfo,
                dlcGameIds = dlcGameIds,
                installPath = installPath,
                containerLanguage = containerLanguage
            )

            // Add to the queue if it is not there
            if (!downloadQueue.contains(key)) {
                downloadQueue.add(key)
            }

            // Always set current download key
            currentDownloadingKey = key
            Timber.i("[GameDownloadService] Registered $gameSource download for $gameId")
        }
    }

    /**
     * Enqueue a download WITHOUT starting it: registers a placeholder entry (no
     * DownloadInfo yet) at the END of the queue so it survives as a pending item and
     * is resumed by [resumeNextDownload] when the downloads ahead of it finish.
     * Cancels nothing and never touches [currentDownloadingKey]. When the queue
     * reaches the entry, the store's download entry point runs and its
     * [registerDownload] call replaces the placeholder with the real DownloadInfo.
     * Used by "Resume all" after an app kill, when the in-memory queue was lost.
     */
    fun enqueueDownload(
        gameSource: GameSource,
        gameId: String,
        dlcGameIds: List<Int> = emptyList(),
        installPath: String? = null,
        containerLanguage: String? = null,
    ) {
        val key = makeKey(gameSource, gameId)

        synchronized(queueLock) {
            if (registeredDownloads.containsKey(key)) {
                return // already registered (queued or running) — keep the live entry
            }
            registeredDownloads[key] = DownloadEntry(
                gameSource = gameSource,
                gameId = gameId,
                downloadInfo = null,
                dlcGameIds = dlcGameIds,
                installPath = installPath,
                containerLanguage = containerLanguage
            )
            if (!downloadQueue.contains(key)) {
                downloadQueue.add(key)
            }
            Timber.i("[GameDownloadService] Enqueued $gameSource download for $gameId")
        }
    }

    /**
     * Unregister a download when it completed.
     * Should only be called by Each Service once (4 callees)
     * Automatically resumes the next paused download if available.
     */
    fun unregisterDownload(context: Context, gameSource: GameSource, gameId: String) {
        val key = makeKey(gameSource, gameId)

        synchronized(queueLock) {
            registeredDownloads.remove(key)
            downloadQueue.remove(key)
            Timber.i("[GameDownloadService] Unregistered $gameSource download for $gameId")
        }

        // Off the caller's thread and OUTSIDE queueLock: store startups do DB /
        // container / disk work (Epic even `runBlocking`s a Room query), so running
        // one here would ANR a main-thread caller and stall every other queue call.
        queueScope.launch { resumeNextDownload(context.applicationContext) }
    }

    /**
     * Remove download when pressing delete button
     * Should only be called by Each Service once (4 callees)
     */
    fun removeDownload(context: Context, gameSource: GameSource, gameId: String) {
        val key = makeKey(gameSource, gameId)
        var shouldResume = false

        synchronized(queueLock) {
            // If currentEntry is downloading, cancel it, and resume
            if (currentDownloadingKey == key) {
                val currentEntry = registeredDownloads[currentDownloadingKey]
                currentEntry?.downloadInfo?.cancel()
                shouldResume = true
            }

            registeredDownloads.remove(key)
            downloadQueue.remove(key)
            Timber.i("[GameDownloadService] Removed $gameSource download for $gameId")
        }

        // Same threading rule as unregisterDownload: never on the caller, never in-lock.
        if (shouldResume) {
            queueScope.launch { resumeNextDownload(context.applicationContext) }
        }
    }

    /**
     * Resume next pending download. The claim (queue head -> current) is atomic under
     * [queueLock]; the store startup itself runs unlocked on the caller's coroutine.
     */
    private fun resumeNextDownload(context: Context) {
        val nextEntry: DownloadEntry?
        synchronized(queueLock) {
            val nextKey = downloadQueue.firstOrNull()
            nextEntry = nextKey?.let { registeredDownloads[it] }
            if (nextEntry != null) {
                currentDownloadingKey = nextKey
            }
        }

        if (nextEntry != null) {
            Timber.i("[GameDownloadService] Resuming ${nextEntry.gameSource} download for ${nextEntry.gameId}")
            when (nextEntry.gameSource) {
                GameSource.STEAM -> SteamService.downloadApp(
                    appId = nextEntry.gameId.toInt()
                )
                GameSource.AMAZON -> {
                    val installPath = nextEntry.installPath
                        ?: AmazonService.resolveInstallPath(context, nextEntry.gameId)
                    if (installPath != null) {
                        AmazonService.downloadGame(
                            context = context,
                            productId = nextEntry.gameId,
                            installPath = installPath
                        )
                    } else {
                        Timber.w("[GameDownloadService] No install path for Amazon ${nextEntry.gameId}, not resuming")
                    }
                }
                GameSource.GOG -> GOGService.downloadGame(
                    context = context,
                    gameId = nextEntry.gameId,
                    installPath = nextEntry.installPath!!,
                    containerLanguage = nextEntry.containerLanguage!!
                )
                GameSource.EPIC -> EpicService.downloadGame(
                    context = context,
                    appId = nextEntry.gameId.toInt(),
                    dlcGameIds = nextEntry.dlcGameIds,
                    installPath = nextEntry.installPath!!,
                    containerLanguage = nextEntry.containerLanguage!!
                )
                // Do Nothing for Custom Game
                GameSource.CUSTOM_GAME -> {}
            }
        }
    }


    fun isPaused(gameSource: GameSource, gameId: String) : Boolean {
        val key = makeKey(gameSource, gameId)
        return registeredDownloads.contains(key) && currentDownloadingKey != key
    }
}
