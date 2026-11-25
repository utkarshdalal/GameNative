// Based on xal-rs (https://github.com/xodus-gaming/xal-rs), MIT.
package app.gamenative.service.xbox.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** sisu/authorize answer for the Windows RPS flow. */
data class XboxSisuAuthorization(
    val titleToken: XboxToken,
    val userToken: XboxToken,
    val authorizationToken: XboxToken,
    val webPage: String,
    val useModernGamertag: Boolean?,
) {
    companion object {
        fun parse(json: String): XboxSisuAuthorization {
            val root = JSONObject(json)
            return XboxSisuAuthorization(
                titleToken = XboxToken.parse(root.getJSONObject("TitleToken")),
                userToken = XboxToken.parse(root.getJSONObject("UserToken")),
                authorizationToken = XboxToken.parse(root.getJSONObject("AuthorizationToken")),
                webPage = root.str("WebPage") ?: "",
                useModernGamertag = if (root.has("UseModernGamertag") && !root.isNull("UseModernGamertag")) {
                    root.getBoolean("UseModernGamertag")
                } else {
                    null
                },
            )
        }
    }
}

/**
 * The signed, title-scoped token calls a Windows client makes: a device token from an RPS device
 * ticket, sisu/authorize with a title's client id, and XSTS with device/title/user tokens.
 * Every request carries the Signature header from [signer]; the device token is bound to its key,
 * so keep one signer for the whole chain.
 */
class XboxSisu(
    private val client: OkHttpClient,
    private val config: XboxConfig,
    val signer: XboxRequestSigner,
) {
    private val cv = XboxCorrelationVector()
    private val endpointsLock = Mutex()
    private var endpoints: XboxTitleEndpoints? = null

    suspend fun titleEndpoints(): XboxTitleEndpoints = endpointsLock.withLock {
        endpoints ?: run {
            val request = Request.Builder()
                .url(config.value(XboxApiConfig.TITLE_ENDPOINTS_URL))
                .header("x-xbl-contract-version", config.value(XboxApiConfig.AUTH_CONTRACT_VERSION))
                .get()
                .build()
            XboxTitleEndpoints.parse(XboxHttp.send(client, request).body).also { endpoints = it }
        }
    }

    /** Device token from the compact device ticket for user.auth.xboxlive.com (token broker policy). */
    suspend fun deviceTokenRps(rpsDeviceTicket: String): XboxToken {
        val body = JSONObject()
            .put("RelyingParty", config.value(XboxApiConfig.AUTH_RELYING_PARTY))
            .put("TokenType", "JWT")
            .put(
                "Properties",
                JSONObject()
                    .put("AuthMethod", "RPS")
                    .put("RpsTicket", rpsDeviceTicket)
                    .put("SiteName", config.value(XboxApiConfig.USER_AUTH_SITE_NAME))
                    .put("Version", config.value(XboxApiConfig.SISU_CLIENT_VERSION))
                    .put("ProofKey", signer.proofKey.toJson()),
            )
        return XboxToken.parse(signedPost(config.value(XboxApiConfig.DEVICE_AUTH_URL), body, contractVersion = true))
    }

    /**
     * sisu/authorize with the user's compact xboxlive.signin ticket issued for [appId] (a title's
     * client id) and [deviceToken] from [deviceTokenRps]. The title id travels inside that ticket.
     */
    suspend fun authorizeRps(
        accessToken: String,
        deviceToken: String,
        appId: String,
        sessionId: String? = null,
    ): XboxSisuAuthorization {
        val body = JSONObject()
            .put("AccessToken", accessToken)
            .put("AppId", appId)
            .put("DeviceToken", deviceToken)
            .put("Sandbox", config.value(XboxApiConfig.SANDBOX))
            .put("SiteName", config.value(XboxApiConfig.USER_AUTH_SITE_NAME))
            .apply { if (sessionId != null) put("SessionId", sessionId) }
            .put("ProofKey", signer.proofKey.toJson())
        return XboxSisuAuthorization.parse(
            signedPost(config.value(XboxApiConfig.SISU_AUTHORIZE_URL), body, contractVersion = false),
        )
    }

    suspend fun xstsToken(
        deviceToken: String?,
        titleToken: String?,
        userToken: String?,
        relyingParty: String,
    ): XboxToken {
        val properties = JSONObject().put("SandboxId", config.value(XboxApiConfig.SANDBOX))
        if (deviceToken != null) properties.put("DeviceToken", deviceToken)
        if (titleToken != null) properties.put("TitleToken", titleToken)
        if (userToken != null) properties.put("UserTokens", JSONArray().put(userToken))
        val body = JSONObject()
            .put("RelyingParty", relyingParty)
            .put("TokenType", "JWT")
            .put("Properties", properties)
        return XboxToken.parse(signedPost(config.value(XboxApiConfig.XSTS_URL), body, contractVersion = true))
    }

    private suspend fun signedPost(url: String, body: JSONObject, contractVersion: Boolean): String {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val policy = titleEndpoints().policyFor(url) ?: throw IllegalStateException("No signature policy found for url")
        val signature = signer.sign(policy, "POST", url, authorization = null, body = bytes)
        val request = Request.Builder()
            .url(url)
            .apply {
                if (contractVersion) header("x-xbl-contract-version", config.value(XboxApiConfig.AUTH_CONTRACT_VERSION))
            }
            .header("MS-CV", cv.next())
            .header("Signature", signature)
            .post(XboxHttp.jsonBody(bytes))
            .build()
        return XboxHttp.send(client, request).body
    }
}
