package app.gamenative.service.ea

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * Origin Online Activation licences. The game's DRM (Core/activation.dll) reads
 * `<contentId>.dlf` from the licence store at startup and compares its MachineHash
 * with the hash it computes itself, so the hash is computed on the Wine side and handed in.
 */
object EaLicenseManager {
    data class License(val contentId: String, val machineHash: String, val startTime: String, val gameToken: String?, val signature: String, val blob: ByteArray)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun licenseDir(prefixDriveC: File): File = File(prefixDriveC, EaConstants.LICENSE_DIR).also { it.mkdirs() }

    /** A licence younger than two weeks is reused, as the EA app does. */
    fun needsUpdate(prefixDriveC: File, contentId: String): Boolean {
        val f = File(licenseDir(prefixDriveC), "$contentId.dlf")
        if (!f.exists() || f.length() <= 65) return true
        return runCatching {
            val xml = String(EaCrypto.ooaDecrypt(f.readBytes().copyOfRange(65, f.length().toInt())))
            val start = Regex("<StartTime>([^<]+)</StartTime>").find(xml)?.groupValues?.get(1) ?: return true
            Instant.now().toEpochMilli() - Instant.parse(start).toEpochMilli() > TimeUnit.DAYS.toMillis(14)
        }.getOrDefault(true)
    }


    /**
     * Asks EA to pull the entitlements granted through linked storefronts (Steam, Epic) into the
     * EA account, the way the EA app does before it verifies a Steam-launched title. Returns true
     * when the request was accepted.
     */
    suspend fun refreshExternalEntitlements(context: Context, userId: String): Boolean = withContext(Dispatchers.IO) {
        val token = EaAuthManager.accessToken(context)
        val url = EaConstants.ENTITLEMENT_REFRESH_ENDPOINT.format(userId)
        for (method in listOf("PUT", "POST", "GET")) {
            val req = Request.Builder().url(url)
                .method(method, if (method == "GET") null else ByteArray(0).toRequestBody(null))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            val ok = runCatching {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    Timber.i("EA refreshExternalEntitlements $method -> ${resp.code}")
                    if (resp.code == 405 || resp.code == 404) return@use null
                    resp.isSuccessful
                }
            }.onFailure { Timber.w(it, "EA refreshExternalEntitlements $method failed") }.getOrNull()
            if (ok != null) return@withContext ok
        }
        false
    }

    fun isNotEntitled(e: Throwable): Boolean = e.message?.contains("NOT_ENTITLED") == true

    suspend fun request(
        context: Context,
        contentId: String,
        machineHash: String,
        requestToken: String? = null,
        requestType: String? = null,
    ): License = withContext(Dispatchers.IO) {
        val token = EaAuthManager.accessToken(context)
        val nonce = EaCrypto.hex(ByteArray(4).also { SecureRandom().nextBytes(it) })
        val url = EaConstants.LICENSE_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("contentId", contentId)
            .addQueryParameter("machineHash", machineHash)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("ea_eadmtoken", token)
            .apply {
                if (requestToken != null) addQueryParameter("requestToken", requestToken)
                if (requestType != null) addQueryParameter("requestType", requestType)
            }
            .build()
        val req = Request.Builder().url(url)
            .header("X-Requester-Id", "Origin Online Activation")
            .header("User-Agent", "EACTransaction")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) error("EA licence HTTP ${resp.code}: ${String(body).replace(Regex("value=\"[^\"]*\""), "value=\"…\"").take(300)}")
            val signature = resp.header("x-signature") ?: error("EA licence: missing x-signature")
            val xml = String(EaCrypto.ooaDecrypt(body))
            fun field(name: String) = Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)
            Timber.i("EA licence granted for $contentId (hash ${field("MachineHash")})")
            License(
                contentId = field("ContentId") ?: contentId,
                machineHash = field("MachineHash") ?: machineHash,
                startTime = field("StartTime") ?: "",
                gameToken = field("GameToken")?.takeIf { it.isNotEmpty() },
                signature = signature,
                blob = body,
            )
        }
    }

    /**
     * Writes `<contentId>.dlf` and `<contentId>_cached.dlf`: a 65-byte signature slot followed by the
     * encrypted licence. Games whose Core folder still has activation.exe keep the signature base64
     * encoded; the others expect the decoded bytes.
     */
    fun save(prefixDriveC: File, license: License, signatureEncoded: Boolean) {
        val sig = if (signatureEncoded) license.signature.toByteArray(Charsets.US_ASCII) else Base64.decode(license.signature, Base64.DEFAULT)
        val header = ByteArray(65).also { System.arraycopy(sig, 0, it, 0, minOf(sig.size, 65)) }
        val dir = licenseDir(prefixDriveC)
        for (name in listOf("${license.contentId}.dlf", "${license.contentId}_cached.dlf")) {
            File(dir, name).writeBytes(header + license.blob)
        }
        Timber.i("EA licence saved to ${dir.absolutePath}")
    }
}
