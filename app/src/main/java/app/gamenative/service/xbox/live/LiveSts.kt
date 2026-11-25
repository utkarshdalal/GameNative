// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import app.gamenative.service.xbox.StsToken
import java.security.interfaces.RSAPrivateCrtKey
import java.util.Base64
import okhttp3.OkHttpClient

/** Outcome of a user token exchange: issued tokens, or a fault that may carry an approval URL. */
sealed class UserTokenOutcome {
    data class Issued(val tokens: List<SecurityTokenResponse>) : UserTokenOutcome()
    data class Fault(val inlineAuthUrl: String?, val properties: PassportProperties?, val code: String?) : UserTokenOutcome()
}

/** login.live.com RST2.srf and deviceaddcredential.srf. */
class LiveSts(private val cfg: LiveConfig, private val http: OkHttpClient = LiveHttp.client) {
    private val soapHeaders get() = mapOf("User-Agent" to cfg.userAgent)

    suspend fun registerDevice(membername: String, password: String, components: List<DeviceComponent>): DeviceAddResult {
        val reply = LiveHttp.post(
            cfg.deviceAddUrl,
            DeviceCredential.requestXml(cfg, membername, password, components),
            SOAP_CONTENT_TYPE,
            soapHeaders,
            http,
        )
        return DeviceCredential.parseResponse(reply.body)
    }

    suspend fun send(request: RstRequest): RstResponse {
        val reply = LiveHttp.post(cfg.rst2Url, request.xml, SOAP_CONTENT_TYPE, soapHeaders, http)
        if (reply.body.isBlank()) throw LiveException("RST2 returned HTTP ${reply.code} with no body")
        return RstResponseDecoder.decode(reply.body, request.signature)
    }

    /** First device sign-in after registration: the request is signed with the device RSA key from ClepSignState. */
    suspend fun authenticateDevice(username: String, key: RSAPrivateCrtKey): RstResponse =
        send(
            RstRequestBuilder(cfg)
                .deviceUsername(username)
                .signature(RstSignature.Rsa(key))
                .scope(ScopePolicy(cfg.scopeDeviceSts))
                .build(),
        )

    /** Device token for [address] issued to [appId]; returns the first token of the response. */
    suspend fun exchangeDeviceToken(
        deviceToken: StsToken.Legacy,
        appId: String,
        address: String,
        policy: String?,
    ): SecurityTokenResponse {
        val response = send(
            RstRequestBuilder(cfg)
                .ssoFlags(cfg.ssoFlags)
                .hostingApp(appId)
                .deviceToken(deviceToken)
                .signature(hmacSignature(deviceToken))
                .scope(ScopePolicy(address, policy))
                .build(),
        )
        return when (val body = response.body) {
            is RstBody.Responses -> body.tokens.firstOrNull() ?: throw LiveException("Device token exchange returned no token")
            is RstBody.Fault -> throw LiveException("Device token exchange failed: ${body.code} ${body.reason}")
            is RstBody.Other -> throw LiveException("Device token exchange returned ${body.name}")
        }
    }

    /**
     * Exchanges the user's legacy token for [scopes], proving the device with [deviceToken].
     * [uiMode] replaces the InlineUX value (for example the silent mode); [inlineFlowToken] comes from web sign-in.
     */
    suspend fun exchangeUserToken(
        userToken: StsToken.Legacy,
        username: String,
        deviceToken: StsToken.Legacy,
        inlineFlowToken: String?,
        uiMode: String?,
        clientId: String,
        scopes: List<ScopePolicy>,
    ): UserTokenOutcome {
        val builder = RstRequestBuilder(cfg)
            .userHint(username)
            .deviceToken(deviceToken)
            .userToken(userToken)
            .hostingApp(clientId)
            .ssoFlags(cfg.ssoFlags)
            .licenseSignatureKeyVersion(null)
            .signature(hmacSignature(deviceToken))
        uiMode?.let { builder.inlineUx(it) }
        inlineFlowToken?.let { builder.inlineFt(it) }
        scopes.forEach { builder.scope(it) }
        val response = send(builder.build())
        return when (val body = response.body) {
            is RstBody.Fault -> UserTokenOutcome.Fault(response.properties?.inlineAuthUrl, response.properties, body.code)
            is RstBody.Responses -> UserTokenOutcome.Issued(body.tokens)
            is RstBody.Other -> throw LiveException("User token exchange returned ${body.name}")
        }
    }

    companion object {
        private const val SOAP_CONTENT_TYPE = "application/soap+xml"

        /** The device token's proof key is a ClepHmacState blob holding the HMAC secret. */
        fun hmacSignature(deviceToken: StsToken.Legacy): RstSignature.Hmac {
            val secret = deviceToken.binarySecret ?: throw LiveException("Device token has no proof key")
            return RstSignature.Hmac(LiveBlobs.clepHmacSecret(Base64.getMimeDecoder().decode(secret)))
        }
    }
}
