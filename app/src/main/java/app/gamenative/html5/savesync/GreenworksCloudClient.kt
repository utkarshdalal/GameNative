package app.gamenative.html5.savesync

import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import app.gamenative.utils.SteamUtils
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import java.security.MessageDigest
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

// greenworks programmatic-cloud helper that wraps the JavaSteam
// SteamCloud multi-stage batch protocol (signalAppLaunchIntent → beginAppUploadBatch →
// per-file beginFileUpload + HTTP PUT block(s) + commitFileUpload → completeAppUploadBatch
// → signalAppExitSyncDone). mirrors SteamAutoCloud.syncUserFiles:551-781 verbatim, but
// (a) reads bytes from an in-memory ByteArray (no on-disk staging),
// (b) drops change-number / pattern / on-disk staging machinery,
// (c) computes SHA-1 in-memory via MessageDigest.

// CONTEXT spirit: do NOT route greenworks bytes through SteamAutoCloud's UFS-pattern
// orchestration (change-number bookkeeping, conflict resolution, SaveLocation heuristics
// don't fit programmatic remote storage). reuse only the JavaSteam protocol primitives.

// CONTEXT LITERAL "fileWrite" was a misread -- JavaSteam exposes no single-call
// SteamCloud.fileWrite. Finding 1 documents the multi-stage protocol
// is the only path. this client implements that path.

// thread-model: caller is Html5SaveSyncService running on Dispatchers.IO inside
// runBlocking from the WebViewDestroyed event subscriber. all RPC suspends use kotlinx
// .coroutines.future.await; HTTP PUTs run inside withTimeout(SteamService.requestTimeout)
// against the same okhttp client SteamAutoCloud uses.
object GreenworksCloudClient {

    private const val TAG = "Html5GreenworksCloud"

    // revision: JavaSteam exposes no getQuota; the per-account
    // cloud cap is not in the protobuf surface. use a conservative constant -- Cookie
    // Clicker uses quota only to gate "back up to cloud?" UI. accuracy of `available`
    // (= total - used) matters more than accuracy of `total`.
    private const val CONSERVATIVE_TOTAL_BYTES = 104_857_600L // 100 MB

    data class UploadResult(
        val success: Boolean,
        val filesUploaded: Int,
        val bytesUploaded: Long,
    )

    // V5. reject ../, leading slash, control chars, backslashes
    // BEFORE any RPC fires. greenworks-stripped filenames are game-supplied (LS keys
    // minus the gn:gw: prefix); a malicious or buggy game could try to scope-escape.
    private val SAFE_FILENAME_REGEX = Regex("""^[A-Za-z0-9._\-]+$""")

    private fun validateFilename(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.length > 260) return false // matches Steam's max filename length empirically
        if (name.contains("..")) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        // strict allowlist -- greenworks save names are typically `cookieClickerSave.txt` shape
        return SAFE_FILENAME_REGEX.matches(name)
    }

    // graceful no-op accessor. all three public entry points return early when any of the
    // three required handles is null (offline / pre-login / Steam handler not bound).
    private fun acquireHandlesOrNull(): Triple<SteamService, SteamCloud, Long>? {
        val steamInstance = SteamService.instance
        if (steamInstance == null) {
            Timber.tag(TAG).d("acquireHandlesOrNull: SteamService.instance null — skipping")
            return null
        }
        val steamCloud = steamInstance.steamCloudHandler()
        if (steamCloud == null) {
            Timber.tag(TAG).d("acquireHandlesOrNull: _steamCloud null (offline?) — skipping")
            return null
        }
        val clientId = PrefManager.clientId
        if (clientId == null) {
            Timber.tag(TAG).d("acquireHandlesOrNull: PrefManager.clientId null (not authed) — skipping")
            return null
        }
        return Triple(steamInstance, steamCloud, clientId)
    }

    /**
     * delete a single file from Steam Cloud. used to clean up stale probe / debug files
     * the renderer's deleteFile only removes from localStorage; Steam Cloud retains the
     * file (as a non-tombstone manifest entry) and INBOUND keeps re-downloading it. this
     * issues the actual SteamCloud.deleteFile RPC.
     */
    suspend fun deleteFromCloud(appId: Int, filename: String): Boolean {
        val handles = acquireHandlesOrNull() ?: return false
        val (_, steamCloud, _) = handles
        return try {
            val ok = steamCloud.deleteFile(appId, filename).await()
            Timber.tag(TAG).i("deleteFromCloud: appId=%d filename=%s ok=%s", appId, filename, ok)
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "deleteFromCloud: failed appId=%d filename=%s", appId, filename)
            false
        }
    }

    /**
     * first-launch probe. cheap getAppFileListChange RPC to learn whether the
     * Steam app has any cloud surface at all. used by Html5SaveSyncService to flip the
     * greenworksCloudObserved flag for pack:electron containers BEFORE any in-game write
     * fires (closes chicken-and-egg: resolver default = SteamUfs unless flag already true).
     *
     * returns true on a successful manifest fetch (cloud surface exists for app, even if
     * empty), false on any failure (offline / not authed / RPC throw / unknown handler).
     * caller treats false as "fall through to existing unsupported-snackbar path".
     */
    suspend fun probeCloud(appId: Int): Boolean {
        val handles = acquireHandlesOrNull() ?: return false
        val (_, steamCloud, _) = handles
        return try {
            steamCloud.getAppFileListChange(appId, 0L).await()
            Timber.tag(TAG).i("probeCloud: appId=%d cloud surface present", appId)
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "probeCloud: appId=%d RPC failed — fall through", appId)
            false
        }
    }


    /**
     * upload greenworks programmatic-cloud bytes to Steam Cloud for [appId]. runs the
     * multi-stage batch protocol mirroring SteamAutoCloud.syncUserFiles:551-781.
     *
     * @param appId numeric Steam app id (e.g. 1454400 for Cookie Clicker)
     * @param files list of (filename, bytes). filenames are gn:gw:-stripped; cross-device
     *              greenworks readers (desktop) match on these names verbatim.
     */
    suspend fun upload(appId: Int, files: List<Pair<String, ByteArray>>): UploadResult {
        if (files.isEmpty()) {
            Timber.tag(TAG).i("upload skipped: empty file list for appId=%d", appId)
            return UploadResult(success = true, filesUploaded = 0, bytesUploaded = 0L)
        }
        val handles = acquireHandlesOrNull() ?: return UploadResult(false, 0, 0L)
        val (steamInstance, steamCloud, clientId) = handles

        // V5 validation pre-flight: reject any filename that wouldn't survive Steam's
        // remote storage path semantics. log + abort the whole batch rather than partial.
        val invalid = files.firstOrNull { !validateFilename(it.first) }
        if (invalid != null) {
            Timber.tag(TAG).e(
                "upload aborted: invalid filename %s for appId=%d (rejected by V5 validation)",
                invalid.first,
                appId,
            )
            return UploadResult(false, 0, 0L)
        }

        Timber.tag(TAG).i(
            "OUTBOUND start n=%d bytes=%d appId=%d",
            files.size,
            files.sumOf { it.second.size.toLong() },
            appId,
        )

        return try {
            // 1. session claim -- force-kick rival desktop session.
            steamCloud.signalAppLaunchIntent(
                appId = appId,
                clientId = clientId,
                machineName = SteamUtils.getMachineName(steamInstance),
                ignorePendingOperations = true,
                osType = EOSType.AndroidUnknown,
            ).await()

            // 2. begin batch.
            val batch = steamCloud.beginAppUploadBatch(
                appId = appId,
                machineName = SteamUtils.getMachineName(steamInstance),
                clientId = clientId,
                filesToDelete = emptyList(),
                filesToUpload = files.map { it.first },
                appBuildId = 0L,
            ).await()

            var batchSuccess = true
            var filesUploaded = 0
            var bytesUploaded = 0L

            files.forEach { (filename, bytes) ->
                val sha = MessageDigest.getInstance("SHA-1").digest(bytes)

                val info = steamCloud.beginFileUpload(
                    appId = appId,
                    filename = filename,
                    fileSize = bytes.size,
                    rawFileSize = bytes.size,
                    fileSha = sha,
                    timestamp = Date(System.currentTimeMillis()),
                    uploadBatchId = batch.batchID,
                ).await()

                // SHA-dedupe: cloud already has these bytes -- no transfer needed,
                // file IS counted in the batch (carries forward at completeAppUploadBatch).
                if (info.blockRequests.isEmpty()) {
                    Timber.tag(TAG).i("file %s already in cloud (SHA dedup)", filename)
                    filesUploaded++
                    return@forEach
                }

                var fileSuccess = true
                info.blockRequests.forEach { block ->
                    val httpUrl = buildSteamCdnUrl(block.useHttps, block.urlHost, block.urlPath)
                    val sliceStart = block.blockOffset.toInt()
                    val slice = bytes.copyOfRange(sliceStart, sliceStart + block.blockLength)

                    val mediaType = block.requestHeaders
                        .firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }
                        ?.value?.toMediaTypeOrNull()
                        ?: "application/octet-stream".toMediaTypeOrNull()
                    val body = slice.toRequestBody(mediaType)
                    val headers = Headers.headersOf(
                        *block.requestHeaders.flatMap { listOf(it.name, it.value) }.toTypedArray(),
                    )
                    val request = Request.Builder()
                        .url(httpUrl)
                        .put(body)
                        .headers(headers)
                        .addHeader("Accept", "text/html,*/*;q=0.9")
                        .addHeader("accept-encoding", "gzip,identity,*;q=0")
                        .addHeader("accept-charset", "ISO-8859-1,utf-8,*;q=0.7")
                        .addHeader("user-agent", "Valve/Steam HTTP Client 1.0")
                        .build()
                    val httpClient = steamInstance.steamClient!!.configuration.httpClient
                    withTimeout(SteamService.requestTimeout) {
                        // use{} closes the Response on every path -- a throw between execute() and
                        // close (timeout cancel, header parse) would otherwise leak the connection
                        httpClient.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                fileSuccess = false
                                batchSuccess = false
                                Timber.tag(TAG).w("PUT failed code=%d for %s", resp.code, filename)
                            }
                        }
                    }
                }

                // commit. transferSucceeded controls whether Steam keeps or rolls back this file.
                val commitOk = steamCloud.commitFileUpload(
                    transferSucceeded = fileSuccess,
                    appId = appId,
                    fileSha = sha,
                    filename = filename,
                ).await()
                if (commitOk && fileSuccess) {
                    filesUploaded++
                    bytesUploaded += bytes.size.toLong()
                } else {
                    Timber.tag(TAG).w("commitFileUpload returned false for %s", filename)
                    batchSuccess = false
                }
            }

            // 3. complete batch -- call even on failure so Steam doesn't leave a dangling batch.
            steamCloud.completeAppUploadBatch(
                appId = appId,
                batchId = batch.batchID,
                batchEResult = if (batchSuccess) EResult.OK else EResult.Fail,
            ).await()

            // 4. signal exit. uploadsCompleted reports per-file success summary; uploadsRequired
            // is true iff there were any files in the batch. fire-and-forget per SteamAutoCloud
            // (line 1553) -- no CompletionStage to await.
            steamCloud.signalAppExitSyncDone(
                appId = appId,
                clientId = clientId,
                uploadsCompleted = batchSuccess,
                uploadsRequired = files.isNotEmpty(),
            )

            Timber.tag(TAG).i(
                "OUTBOUND done n=%d bytes=%d ok=%s appId=%d",
                filesUploaded,
                bytesUploaded,
                batchSuccess,
                appId,
            )
            UploadResult(success = batchSuccess, filesUploaded = filesUploaded, bytesUploaded = bytesUploaded)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "OUTBOUND failed appId=%d", appId)
            UploadResult(success = false, filesUploaded = 0, bytesUploaded = 0L)
        }
    }

    /**
     * download all greenworks programmatic-cloud bytes for [appId] currently in Steam Cloud.
     *
     * uses getAppFileListChange (manifest) + clientFileDownload (per-file presigned URL)
     * + OkHttp GET. tombstones (persistState.number != 0) are skipped. mirrors
     * SteamAutoCloud.kt.
     */
    suspend fun download(appId: Int): List<Pair<String, ByteArray>> {
        // throw on handles-unavailable so the caller's inboundFailedThisSession gate fires
        // and outbound short-circuits. swallowing here would conflate "session not ready" with
        // "cloud genuinely empty" and the WebView-side accumulated state would later overwrite
        // real cloud bytes at exit.
        val handles = acquireHandlesOrNull()
            ?: error("greenworks INBOUND: steam handles unavailable (offline or not authed) for appId=$appId")
        val (steamInstance, steamCloud, _) = handles

        // any throw inside this body -- manifest RPC failure, clientFileDownload RPC failure,
        // OkHttp network error -- propagates to syncInbound's try/catch which marks the appId
        // as inbound-failed-this-session and suppresses outbound. emptyList is reserved for
        // the LEGITIMATE "cloud manifest empty" case only.
        val results = mutableListOf<Pair<String, ByteArray>>()
        // single-page walk is sufficient for greenworks file counts (Cookie Clicker writes
        // 1-3 files). full pagination would be SteamAutoCloud.kt shape if a future
        // greenworks title goes wide.
        val manifest = steamCloud.getAppFileListChange(appId, 0L).await()
        Timber.tag(TAG).i(
            "INBOUND manifest fetched n=%d appId=%d",
            manifest.files.size,
            appId,
        )
        manifest.files.forEach { f ->
            val name = f.filename
            if (name.isNullOrEmpty()) return@forEach
            if (f.persistState.number != 0) return@forEach // skip tombstones
            if (!validateFilename(name)) {
                Timber.tag(TAG).w("INBOUND skip invalid filename: %s", name)
                return@forEach
            }
            val info = steamCloud.clientFileDownload(appId, name).await()
            if (info.urlHost.isEmpty()) {
                Timber.tag(TAG).w("INBOUND empty urlHost for %s", name)
                return@forEach
            }
            val httpUrl = buildSteamCdnUrl(info.useHttps, info.urlHost, info.urlPath)
            val headers = Headers.headersOf(
                *info.requestHeaders.flatMap { listOf(it.name, it.value) }.toTypedArray(),
            )
            val request = Request.Builder().url(httpUrl).headers(headers).build()
            val httpClient = steamInstance.steamClient!!.configuration.httpClient
            withTimeout(SteamService.requestTimeout) {
                // use{} closes the Response on every path: the error() throw below and any
                // body-read failure run inside a per-file loop, so a leak here compounds
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.tag(TAG).w("INBOUND GET failed code=%d for %s", resp.code, name)
                        error("greenworks INBOUND: HTTP ${resp.code} for $name on appId=$appId")
                    }
                    val bytes = resp.body?.bytes()
                        ?: error("greenworks INBOUND: null body for $name on appId=$appId")
                    results += name to bytes
                }
            }
        }
        Timber.tag(TAG).i(
            "INBOUND done n=%d bytes=%d appId=%d",
            results.size,
            results.sumOf { it.second.size.toLong() },
            appId,
        )
        return results
    }

    /**
     * derive `getCloudQuota` JSON from getAppFileListChange manifest. JavaSteam doesn't
     * expose the per-account cloud cap revision); we use a
     * conservative 100 MB total. used = sum of rawFileSize over non-tombstone entries;
     * available = total - used (clamped at 0).
     *
     * called from SteamworksJsBridge.getCloudQuota on the binder thread.
     * caches the result session-scoped so we run this at most once per game launch.
     */
    fun getQuotaJson(appId: Int): String {
        // synchronous wrapper -- bridge is binder-thread; caller can't suspend.
        // run a runBlocking IO to gather the manifest. acceptable: bridge already runs sync I/O
        // (writeAchievementsJsonAtomic, file reads). bound the RPC with withTimeout -- same as
        // upload/download -- so a hung getAppFileListChange can't wedge the JS binder thread
        // indefinitely; the timeout falls through to the conservative fallback below.
        return runBlocking(Dispatchers.IO) {
            val handles = acquireHandlesOrNull()
            if (handles == null) {
                return@runBlocking conservativeQuotaJson()
            }
            val (_, steamCloud, _) = handles
            try {
                val manifest = withTimeout(SteamService.requestTimeout) {
                    steamCloud.getAppFileListChange(appId, 0L).await()
                }
                val used = manifest.files
                    .filter { !it.filename.isNullOrEmpty() && it.persistState.number == 0 }
                    .sumOf { it.rawFileSize.toLong() }
                val available = (CONSERVATIVE_TOTAL_BYTES - used).coerceAtLeast(0L)
                JSONObject().apply {
                    put("total", CONSERVATIVE_TOTAL_BYTES)
                    put("available", available)
                }.toString()
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "getQuotaJson: falling back to conservative defaults")
                conservativeQuotaJson()
            }
        }
    }

    private fun conservativeQuotaJson(): String =
        JSONObject().apply {
            put("total", CONSERVATIVE_TOTAL_BYTES)
            put("available", CONSERVATIVE_TOTAL_BYTES)
        }.toString()

    // mirrors SteamAutoCloud's buildUrl helper. https vs http per Steam-server flag,
    // urlHost is the CDN authority, urlPath is the presigned path with auth params.
    private fun buildSteamCdnUrl(useHttps: Boolean, urlHost: String, urlPath: String): String {
        val scheme = if (useHttps) "https" else "http"
        val cleanPath = if (urlPath.startsWith("/")) urlPath else "/$urlPath"
        return "$scheme://$urlHost$cleanPath"
    }
}
