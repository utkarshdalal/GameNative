// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import app.gamenative.service.xbox.StsToken
import app.gamenative.service.xbox.XboxTokenStore
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The account has no entitlement for the content: not owned, or not in its current subscription. */
class XboxNotEntitledException(val description: String, val code: Long) : LiveException("Not entitled to this content: $description")

object XboxLicensing {
    /**
     * Content keys of [contentId]: key id to 32-byte content key (CIK). The device's license key unwraps the
     * packed keys of the content license that licensing.mp.microsoft.com issues for the signed-in account.
     */
    suspend fun contentKeys(
        store: XboxTokenStore,
        cfg: LiveConfig,
        contentId: String,
        market: String,
        sts: LiveSts = LiveSts(cfg),
    ): Map<UUID, ByteArray> {
        val deviceToken = LiveDeviceAuth.ensureDevice(store, cfg, sts)
        val user = store.user() ?: throw LiveException("No Xbox account is signed in")
        val userSts = store.userStsToken() ?: throw LiveException("The Xbox account sign-in has no STS token")

        val msDevice = sts.exchangeDeviceToken(deviceToken, cfg.appStore, cfg.scopeStore, cfg.policyMbiSsl).toStsToken()
        val userOutcome = sts.exchangeUserToken(
            userToken = userSts,
            username = user.username,
            deviceToken = deviceToken,
            inlineFlowToken = null,
            uiMode = cfg.silentUx,
            clientId = cfg.appStore,
            scopes = listOf(ScopePolicy(cfg.scopeStore, cfg.policyMbiSsl)),
        )
        val deviceCompact = (msDevice as? StsToken.Compact)?.value ?: throw LiveException("Unsupported token")
        val userCompact = when (userOutcome) {
            is UserTokenOutcome.Fault -> throw LiveException("Failed to get exchange MS token")
            is UserTokenOutcome.Issued -> (userOutcome.tokens.firstOrNull()?.toStsToken() as? StsToken.Compact)?.value
                ?: throw LiveException("Unsupported token")
        }

        val splicenseBlock = licenseContent(cfg, deviceCompact, userCompact, user.puid, contentId, market)
        val gameLicense = SpLicense.parseBase64(splicenseBlock)
        val device = store.device() ?: throw LiveException("Device is not registered")
        val encryptedKey = SpLicense.parseBase64(device.spLicense).encryptedDeviceKey
            ?: throw LiveException("Device license has no EncryptedDeviceKey")
        val deviceKey = LiveBlobs.deviceKey(encryptedKey)
        return gameLicense.contentKeys.mapValues { (_, packed) -> LiveBlobs.unwrapContentKey(deviceKey, packed) }
    }

    /** `.cik` file contents: the key id in GUID byte order followed by the content key. */
    fun cikFile(keyId: UUID, contentKey: ByteArray): ByteArray = uuidToBytesLe(keyId) + contentKey

    fun licenseRequestJson(cfg: LiveConfig, userToken: String, ticketReference: String, contentId: String, market: String): String {
        val identity = JSONObject()
            .put("identityType", cfg.licensingIdentityType)
            .put("identityValue", userToken)
            .put("localTicketReference", ticketReference)
        return JSONObject()
            .put("clientChallenge", cfg.licensingClientChallenge)
            .put("concurrencyMode", cfg.licensingConcurrencyMode)
            .put("contentId", contentId)
            .put(
                "deviceContext",
                JSONObject()
                    .put("hardwareManufacturer", cfg.licensingDeviceContext)
                    .put("hardwareType", cfg.licensingDeviceContext)
                    .put("mobileOperator", cfg.licensingDeviceContext),
            )
            .put("licenseVersion", cfg.licensingLicenseVersion)
            .put("market", market)
            .put("needKey", true)
            .put("keyOnly", true)
            .put("users", JSONObject().put(cfg.licensingUserSid, JSONArray().put(identity)))
            .toString()
    }

    /** Parses the licensing response into the content license's SPLicenseBlock. */
    fun parseLicenseResponse(code: Int, body: String): String {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw LiveException("Licensing returned HTTP $code: ${body.take(200)}", e)
        }
        json.optJSONObject("license")?.let { license ->
            val value = license.optJSONArray("keys")?.optJSONObject(0)?.optString("value")
                ?.takeIf { it.isNotEmpty() } ?: throw LiveException("Content license has no keys")
            val xml = String(Base64.getMimeDecoder().decode(value), Charsets.UTF_8)
            val root = LiveXml.parse(xml, namespaceAware = false)
            return root.childText("SPLicenseBlock") ?: throw LiveException("Content license has no SPLicenseBlock")
        }
        json.optJSONObject("satisfactionFailure")?.let {
            throw XboxNotEntitledException(it.optString("description"), it.optLong("code"))
        }
        throw LiveException("Licensing returned HTTP $code without a license")
    }

    private suspend fun licenseContent(
        cfg: LiveConfig,
        deviceToken: String,
        userToken: String,
        ticketReference: String,
        contentId: String,
        market: String,
    ): String {
        val reply = LiveHttp.post(
            cfg.licensingUrl,
            licenseRequestJson(cfg, userToken, ticketReference, contentId, market),
            "application/json",
            mapOf(
                "from" to cfg.licensingFrom,
                "Authorization" to deviceToken,
                "User-Agent" to cfg.licensingUserAgent,
                "MS-CV" to correlationVector(),
            ),
        )
        return parseLicenseResponse(reply.code, reply.body)
    }

    /** Correlation vector v2: 22 base64 characters of random base, then the extension counter. */
    fun correlationVector(): String =
        Base64.getEncoder().encodeToString(LiveCrypto.randomBytes(16)).substring(0, 22) + ".0"
}
