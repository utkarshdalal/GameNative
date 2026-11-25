// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class XboxPackageFile(
    val contentId: String,
    val versionId: String,
    val fileName: String,
    val fileSize: Long,
    val fileHash: String,
    val keyBlob: String,
    val cdnRootPaths: List<String>,
    val backgroundCdnRootPaths: List<String>,
    val relativeUrl: String,
    val updateType: Int,
    val deltaVersionId: String?,
    val licenseUsageType: Int,
    val modifiedDate: String,
) {
    /** Full download URLs, one per CDN root, in the order the service lists them. */
    val urls: List<String> get() = cdnRootPaths.map { it + relativeUrl }

    val isMsixvc: Boolean get() = fileName.endsWith(".msixvc")
}

data class XboxPackageDetails(
    val contentId: String,
    val versionId: String,
    val version: String,
    val packageFiles: List<XboxPackageFile>,
    val hashOfHashes: String?,
    val updatePredownload: Boolean,
    val availabilityDate: String,
) {
    /** The game's main package: the first file whose name ends in .msixvc. Other files (.xsp, .eappx, ...) ride along. */
    val mainPackage: XboxPackageFile? get() = packageFiles.firstOrNull { it.isMsixvc }

    val msixvcUrls: List<String> get() = mainPackage?.urls.orEmpty()
}

/** GetBasePackage on packagespc, authorized with an XSTS token for the update relying party. */
class XboxPackages(
    private val client: OkHttpClient,
    private val config: XboxConfig,
    private val xsts: XboxXsts,
) {
    /** Null when the service answers PackageFound=false (usually: the account does not own it). */
    suspend fun basePackage(contentId: String): XboxPackageDetails? {
        val auth = xsts.authorizationHeader(config.value(XboxApiConfig.UPDATE_RELYING_PARTY))
        val request = Request.Builder()
            .url(config.fill(XboxApiConfig.PACKAGES_URL, mapOf("contentId" to contentId)))
            .header("x-xbl-contract-version", config.value(XboxApiConfig.PACKAGES_CONTRACT_VERSION))
            .header("Authorization", auth)
            .get()
            .build()
        val reply = XboxHttp.send(client, request, checkStatus = false)
        return try {
            parse(reply.body)
        } catch (e: Exception) {
            if (reply.code !in 200..299) throw XboxHttpException(request.url.toString(), reply.code, reply.body.take(512))
            throw e
        }
    }

    companion object {
        /** The package, or null for the PackageFound=false answer; throws if the JSON is neither. */
        fun parse(json: String): XboxPackageDetails? {
            val root = JSONObject(json)
            return try {
                XboxPackageDetails(
                    contentId = root.getString("ContentId"),
                    versionId = root.getString("VersionId"),
                    version = root.getString("Version"),
                    packageFiles = root.getJSONArray("PackageFiles").let { files ->
                        (0 until files.length()).map { parseFile(files.getJSONObject(it)) }
                    },
                    hashOfHashes = root.str("HashOfHashes"),
                    updatePredownload = root.getBoolean("UpdatePredownload"),
                    availabilityDate = root.getString("AvailabilityDate"),
                ).also { root.getBoolean("PackageFound") }
            } catch (e: org.json.JSONException) {
                if (root.has("PackageFound")) null else throw e
            }
        }

        private fun parseFile(f: JSONObject) = XboxPackageFile(
            contentId = f.getString("ContentId"),
            versionId = f.getString("VersionId"),
            fileName = f.getString("FileName"),
            fileSize = f.getLong("FileSize"),
            fileHash = f.getString("FileHash"),
            keyBlob = f.getString("KeyBlob"),
            cdnRootPaths = strings(f.getJSONArray("CdnRootPaths")),
            backgroundCdnRootPaths = strings(f.getJSONArray("BackgroundCdnRootPaths")),
            relativeUrl = f.getString("RelativeUrl"),
            updateType = f.getInt("UpdateType"),
            deltaVersionId = f.str("DeltaVersionId"),
            licenseUsageType = f.getInt("LicenseUsageType"),
            modifiedDate = f.getString("ModifiedDate"),
        )

        private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    }
}
