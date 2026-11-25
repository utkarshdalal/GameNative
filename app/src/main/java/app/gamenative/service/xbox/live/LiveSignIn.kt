// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import app.gamenative.service.xbox.StsToken
import app.gamenative.service.xbox.XboxTokenStore
import app.gamenative.service.xbox.XboxUser
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/** Sign-in result the login page hands over (`ServerData` / the DA property bag). */
data class DaProperty(
    val daToken: String,
    val daSessionKey: String,
    val daStartTime: String,
    val daExpires: String,
    val stsInlineFlowToken: String,
    val username: String,
    val puid: String,
)

/** A message the login page posted through `window.external.notify`. */
sealed class BridgeMessage {
    data class Token(val property: DaProperty) : BridgeMessage()

    /** The page asked its host for context; answer with [LiveSignIn.contextReplyScript]. */
    data class GetContext(val context: String) : BridgeMessage()
    object Unsupported : BridgeMessage()
}

sealed class SignInResult {
    data class SignedIn(val user: XboxUser) : SignInResult()

    /** Open a new web session at [url] (no extra headers), then call completeSignIn again with finish = true. */
    data class NeedsApproval(val url: String) : SignInResult()
    data class Failed(val reason: String) : SignInResult()
}

/**
 * The login page is the one Windows hosts in its CloudExperienceHost: it is loaded with host headers, talks to
 * the host through `window.external.notify`, and is asked for `ServerData` once post.srf has loaded.
 */
object LiveSignIn {
    /** Name of the JavascriptInterface object the Activity registers; it must expose `postMessage(String)`. */
    const val BRIDGE_NAME = "XboxLiveHost"

    fun loginUrl(cfg: LiveConfig): String =
        cfg.signInUrl.replace("{market}", cfg.signInMarket).replace("{clientId}", cfg.signInClientId)

    /** Headers for the first session only; the approval session opened from [SignInResult.NeedsApproval] sends none. */
    fun loginHeaders(cfg: LiveConfig, correlationId: UUID = UUID.randomUUID()): Map<String, String> = linkedMapOf(
        "cxh-capabilities" to cfg.cxhCapabilities,
        "cxh-correlationId" to correlationId.toString(),
        "cxh-msaBinaryVersion" to cfg.cxhMsaBinaryVersion,
        "cxh-identityClientBinaryVersion" to cfg.cxhIdentityBinaryVersion,
        "cxh-osVersionInfo" to cfg.cxhOsVersionInfo,
        "cxh-platform" to cfg.cxhPlatform,
        "cxh-protocol" to cfg.cxhProtocol,
        "cxh-source" to cfg.cxhSource,
        "hostApp" to cfg.signInHostApp,
    )

    fun userAgent(cfg: LiveConfig): String = cfg.signInUserAgent

    /** Must run before the page's own scripts (document start) in every session. */
    fun initScript(bridgeName: String = BRIDGE_NAME): String =
        "window.ipc = {postMessage: function (m) { $bridgeName.postMessage(String(m)); }};" +
            "window.external = {notify: window.ipc.postMessage};"

    /** Reply to a `CloudExperienceHost.getContext` invoke, evaluated in the page that asked. */
    fun contextReplyScript(cfg: LiveConfig, context: String): String {
        val args = listOf(cfg.signInHostApp, cfg.cxhProtocol, cfg.cxhSource, cfg.cxhCapabilities)
        val message = JSONObject()
            .put("type", "callback")
            .put(
                "value",
                JSONObject()
                    .put("name", GET_CONTEXT)
                    .put("args", org.json.JSONArray(args))
                    .put("context", context),
            )
        return "window[\"CloudExperienceHost.Bridge.dispatchMessage\"](${JSONObject.quote(message.toString())})"
    }

    /** Evaluate after a page under [isPostPage] finishes loading to pull the sign-in result over the bridge. */
    const val POST_SCRIPT = "window.ipc.postMessage(JSON.stringify(ServerData))"

    fun isPostPage(cfg: LiveConfig, url: String): Boolean = url.startsWith(cfg.signInPostUrl)

    private const val GET_CONTEXT = "CloudExperienceHost.getContext"

    fun parseBridgeMessage(body: String): BridgeMessage {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return BridgeMessage.Unsupported
        }
        parseDaProperty(json)?.let { return BridgeMessage.Token(it) }
        val value = json.optJSONObject("value")
        if (json.optString("type") == "invoke" && value?.optString("name") == GET_CONTEXT) {
            val context = value.opt("context")
            if (context is String) return BridgeMessage.GetContext(context)
        }
        return BridgeMessage.Unsupported
    }

    fun parseDaProperty(json: JSONObject): DaProperty? {
        fun s(key: String) = json.opt(key) as? String
        return DaProperty(
            daToken = s("sDAToken") ?: return null,
            daSessionKey = s("sDASessionKey") ?: return null,
            daStartTime = s("sDAStartTime") ?: return null,
            daExpires = s("sDAExpires") ?: return null,
            stsInlineFlowToken = s("sSTSInlineFlowToken") ?: return null,
            username = s("sSigninName") ?: return null,
            puid = s("K") ?: return null,
        )
    }

    /**
     * Exchanges the page's DA token for the user's tokens. The first call uses finish = false; when it returns
     * [SignInResult.NeedsApproval], the next DA property from the approval session is passed with finish = true,
     * which also requests the user's STS token.
     */
    suspend fun completeSignIn(
        store: XboxTokenStore,
        cfg: LiveConfig,
        property: DaProperty,
        finish: Boolean,
        sts: LiveSts = LiveSts(cfg),
    ): SignInResult {
        val device = LiveDeviceAuth.ensureDevice(store, cfg, sts)
        val userToken = StsToken.Legacy(
            keyName = null,
            token = property.daToken,
            binarySecret = null,
            tpmKey = null,
            created = property.daStartTime,
            expires = property.daExpires,
        )
        val scopes = buildList {
            add(ScopePolicy(cfg.signInUserScope, cfg.policyTokenBroker))
            if (finish) add(ScopePolicy(cfg.scopeDeviceSts))
        }
        val outcome = sts.exchangeUserToken(
            userToken = userToken,
            username = property.username,
            deviceToken = device,
            inlineFlowToken = property.stsInlineFlowToken,
            uiMode = null,
            clientId = cfg.signInClientId,
            scopes = scopes,
        )
        return when (outcome) {
            is UserTokenOutcome.Fault -> outcome.inlineAuthUrl?.let { SignInResult.NeedsApproval(it) }
                ?: SignInResult.Failed("User token exchange returned a fault without inline auth (${outcome.code})")
            is UserTokenOutcome.Issued -> {
                val user = XboxUser(puid = property.puid, username = property.username)
                store.saveUser(user)
                for (response in outcome.tokens) {
                    val token = response.toStsToken()
                    store.saveUserToken(response.storeAddress(token), token)
                }
                SignInResult.SignedIn(user)
            }
        }
    }
}
