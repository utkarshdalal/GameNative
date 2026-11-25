// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import app.gamenative.service.xbox.StsToken
import app.gamenative.service.xbox.XboxTokenStore

/** Reply to the game runtime's MSA token request; times are Unix seconds, device fields empty/0 when unavailable. */
data class RuntimeMsaToken(val token: String, val expiry: Long, val deviceRps: String, val deviceExpiry: Long)

/** The game runtime's MSA token request: `<MSATokenRequest><ClientId/><AllowUi/><MsaFullTrust/></MSATokenRequest>`. */
data class RuntimeMsaRequest(val clientId: String, val allowUi: Boolean, val fullTrust: Boolean)

/** A compact (RPS) token and its expiry in Unix seconds. */
data class RpsToken(val token: String, val expiry: Long)

object MsaTokens {
    /**
     * Token the game runtime asks for with [clientId]. Also refreshes the user's STS token, which the same
     * response carries as its last entry.
     */
    suspend fun msaTokenForRuntime(
        store: XboxTokenStore,
        cfg: LiveConfig,
        clientId: String,
        fullTrust: Boolean,
        sts: LiveSts = LiveSts(cfg),
    ): RuntimeMsaToken {
        val deviceToken = LiveDeviceAuth.ensureDevice(store, cfg, sts)
        val userSts = store.userStsToken() ?: throw LiveException("The Xbox account sign-in has no STS token")
        val scope = if (fullTrust) cfg.runtimeFullTrustScope else cfg.runtimeSignInScope

        // The device token is optional for the runtime; a failed exchange leaves it empty.
        val device = try {
            val response = sts.exchangeDeviceToken(deviceToken, cfg.appTokenBroker, cfg.runtimeDeviceScope, cfg.policyTokenBroker)
            (response.toStsToken() as? StsToken.Compact)?.let { it.value to response.expiresEpochSeconds }
        } catch (e: LiveException) {
            null
        } catch (e: java.io.IOException) {
            null
        }

        val outcome = sts.exchangeUserToken(
            userToken = userSts,
            username = cfg.silentUsername,
            deviceToken = deviceToken,
            inlineFlowToken = null,
            uiMode = cfg.silentUx,
            clientId = clientId,
            scopes = listOf(
                ScopePolicy(cfg.runtimeUserScope.replace("{scope}", scope).replace("{clientId}", clientId), cfg.policyTokenBroker),
                ScopePolicy(cfg.scopeDeviceSts),
            ),
        )
        val tokens = when (outcome) {
            is UserTokenOutcome.Issued -> outcome.tokens
            is UserTokenOutcome.Fault -> throw LiveException("MSA token exchange failed (${outcome.code})")
        }
        if (tokens.size < 2) throw LiveException("MSA token exchange returned ${tokens.size} token(s)")
        val refreshed = tokens.last()
        val refreshedToken = refreshed.toStsToken()
        store.saveUserToken(refreshed.storeAddress(refreshedToken), refreshedToken)

        val first = tokens.first()
        val compact = first.toStsToken() as? StsToken.Compact ?: throw LiveException("MSA token is not a compact token")
        return RuntimeMsaToken(
            token = compact.value,
            expiry = first.expiresEpochSeconds,
            deviceRps = device?.first ?: "",
            deviceExpiry = device?.second ?: 0,
        )
    }

    fun parseRuntimeRequest(xml: String): RuntimeMsaRequest {
        val root = LiveXml.parse(xml, namespaceAware = false)
        fun flag(vararg names: String) = names.firstNotNullOfOrNull { root.childText(it) }?.trim()
            ?.let { it == "true" || it == "1" } ?: false
        return RuntimeMsaRequest(
            clientId = root.childText("ClientId") ?: throw LiveException("MSA token request has no ClientId"),
            allowUi = flag("AllowUi"),
            fullTrust = flag("MsaFullTrust", "MSAFullTrust"),
        )
    }

    fun runtimeResponseXml(token: RuntimeMsaToken): String = LiveXml.serialize(
        XElement("", "MSATokenResponse").apply {
            el("", "Token", "") { text(token.token) }
            el("", "Expiry", "") { text(token.expiry.toString()) }
            el("", "DeviceRps", "") { text(token.deviceRps) }
            el("", "DeviceExpiry", "") { text(token.deviceExpiry.toString()) }
        },
    )

    /**
     * Compact user token for [address] under [policy], issued silently to [appId] with the stored user STS token.
     * This is the user RPS ticket that `user.auth.xboxlive.com/user/authenticate` takes (RpsTicket).
     */
    suspend fun userRpsToken(
        store: XboxTokenStore,
        cfg: LiveConfig,
        address: String,
        policy: String?,
        appId: String = cfg.appStore,
        sts: LiveSts = LiveSts(cfg),
    ): RpsToken {
        val deviceToken = LiveDeviceAuth.ensureDevice(store, cfg, sts)
        val userSts = store.userStsToken() ?: throw LiveException("The Xbox account sign-in has no STS token")
        val outcome = sts.exchangeUserToken(
            userToken = userSts,
            username = cfg.silentUsername,
            deviceToken = deviceToken,
            inlineFlowToken = null,
            uiMode = cfg.silentUx,
            clientId = appId,
            scopes = listOf(ScopePolicy(address, policy)),
        )
        val first = when (outcome) {
            is UserTokenOutcome.Issued -> outcome.tokens.firstOrNull() ?: throw LiveException("No token issued")
            is UserTokenOutcome.Fault -> throw LiveException("Failed to get exchange MS token (${outcome.code})")
        }
        val compact = first.toStsToken() as? StsToken.Compact ?: throw LiveException("Unsupported token")
        return RpsToken(compact.value, first.expiresEpochSeconds)
    }

    /** The RPS ticket for Xbox Live user authentication (`user.auth.xboxlive.com` with the MBI_SSL policy). */
    suspend fun xboxUserRpsTicket(store: XboxTokenStore, cfg: LiveConfig, sts: LiveSts = LiveSts(cfg)): RpsToken =
        userRpsToken(store, cfg, cfg.scopeXboxUserAuth, cfg.policyMbiSsl, cfg.appStore, sts)

    /** Compact device token for [address], issued to [appId]. */
    suspend fun deviceRpsToken(
        store: XboxTokenStore,
        cfg: LiveConfig,
        appId: String,
        address: String,
        policy: String?,
        sts: LiveSts = LiveSts(cfg),
    ): RpsToken {
        val deviceToken = LiveDeviceAuth.ensureDevice(store, cfg, sts)
        val response = sts.exchangeDeviceToken(deviceToken, appId, address, policy)
        val compact = response.toStsToken() as? StsToken.Compact ?: throw LiveException("Unsupported token")
        return RpsToken(compact.value, response.expiresEpochSeconds)
    }
}
