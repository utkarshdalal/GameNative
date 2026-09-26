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

// Steam Cloud for games that use greenworks' programmatic cloud API. JavaSteam has no single-call
// fileWrite, so this runs the multi-stage batch protocol like SteamAutoCloud's upload path, but on
// in-memory bytes. do NOT route these through SteamAutoCloud's UFS orchestration -- its change
// numbers, conflict resolution and SaveLocation heuristics don't fit programmatic remote storage.
object GreenworksCloudClient {

    private const val TAG = "Html5GreenworksCloud"

    // JavaSteam exposes no per-account cloud cap. games mostly use quota to gate a "back up to cloud?"
    // prompt, so `available` matters more than an accurate `total`.
    private const val CONSERVATIVE_TOTAL_BYTES = 104_857_600L // 100 MB

    data class UploadResult(
        val success: Boolean,
        val filesUploaded: Int,
        val bytesUploaded: Long,
    )

    // filenames are game-supplied (LS keys minus gn:gw:); reject scope escapes BEFORE any RPC.
    private val SAFE_FILENAME_REGEX = Regex("""^[A-Za-z0-9._\-]+$""")

    private fun validateFilename(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.length > 260) return false // matches Steam's max filename length empirically
        if (name.contains("..")) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        return SAFE_FILENAME_REGEX.matches(name)
    }

    // null when offline / not logged in / handler not bound.
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

    // the page-side deleteFile only clears localStorage; without this RPC the cloud keeps the file
    // and inbound re-downloads it every launch.
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

    // lets pack:electron flip greenworksCloudObserved BEFORE the game's first cloud write -- the
    // resolver picks SteamUfs until the flag is set. true = the app has a cloud manifest (even empty).
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

    // filenames are gn:gw:-stripped; desktop greenworks matches them verbatim.
    suspend fun upload(appId: Int, files: List<Pair<String, ByteArray>>): UploadResult {
        if (files.isEmpty()) {
            Timber.tag(TAG).i("upload skipped: empty file list for appId=%d", appId)
            return UploadResult(success = true, filesUploaded = 0, bytesUploaded = 0L)
        }
        val handles = acquireHandlesOrNull() ?: return UploadResult(false, 0, 0L)
        val (steamInstance, steamCloud, clientId) = handles

        // abort the whole batch rather than upload part of it.
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
            // force-kicks a rival desktop session.
            steamCloud.signalAppLaunchIntent(
                appId = appId,
                clientId = clientId,
                machineName = SteamUtils.getMachineName(steamInstance),
                ignorePendingOperations = true,
                osType = EOSType.AndroidUnknown,
            ).await()

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

                // cloud already has these bytes; the file still counts in the batch.
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
                        // use{} so a throw (timeout cancel, header parse) can't leak the connection
                        httpClient.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                fileSuccess = false
                                batchSuccess = false
                                Timber.tag(TAG).w("PUT failed code=%d for %s", resp.code, filename)
                            }
                        }
                    }
                }

                // transferSucceeded decides whether Steam keeps or rolls back this file.
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

            // also on failure, so Steam isn't left with a dangling batch.
            steamCloud.completeAppUploadBatch(
                appId = appId,
                batchId = batch.batchID,
                batchEResult = if (batchSuccess) EResult.OK else EResult.Fail,
            ).await()

            // fire-and-forget, like SteamAutoCloud -- there's nothing to await.
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

    suspend fun download(appId: Int): List<Pair<String, ByteArray>> {
        // THROW, don't return empty: the caller then blocks this session's outbound. treating "not ready"
        // as "cloud empty" would let WebView state overwrite real cloud bytes at exit.
        val handles = acquireHandlesOrNull()
            ?: error("greenworks INBOUND: steam handles unavailable (offline or not authed) for appId=$appId")
        val (steamInstance, steamCloud, _) = handles

        // same for every RPC / network failure below: empty means the cloud manifest is REALLY empty.
        val results = mutableListOf<Pair<String, ByteArray>>()
        // single page: greenworks games write a handful of files.
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
                // use{}: a throw below would otherwise leak one connection per file
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

    // blocking: called on the JS binder thread (SteamworksJsBridge.getCloudQuota). the timeout keeps a
    // hung RPC from wedging that thread; it falls back to the conservative defaults.
    fun getQuotaJson(appId: Int): String {
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

    // mirrors SteamAutoCloud's buildUrl.
    private fun buildSteamCdnUrl(useHttps: Boolean, urlHost: String, urlPath: String): String {
        val scheme = if (useHttps) "https" else "http"
        val cleanPath = if (urlPath.startsWith("/")) urlPath else "/$urlPath"
        return "$scheme://$urlHost$cleanPath"
    }
}
