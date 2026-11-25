// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

/** Key names in xboxhost.properties read by the Windows Live layer. */
object LiveConfigKeys {
    const val RST2_URL = "xbox.live.rst2.url"
    const val RST2_TO = "xbox.live.rst2.to"
    const val DEVICE_ADD_URL = "xbox.live.deviceadd.url"
    const val USER_AGENT = "xbox.live.user.agent"
    const val CLIENT_NAME = "xbox.live.client.name"
    const val CLIENT_VERSION = "xbox.live.client.version"
    const val BINARY_VERSION = "xbox.live.binary.version"

    const val AUTH_HOSTING_APP = "xbox.live.authinfo.hosting.app"
    const val AUTH_UI_VERSION = "xbox.live.authinfo.ui.version"
    const val AUTH_INLINE_UX = "xbox.live.authinfo.inline.ux"
    const val AUTH_IS_ADMIN = "xbox.live.authinfo.is.admin"
    const val AUTH_REQUEST_PARAMS = "xbox.live.authinfo.request.params"
    const val AUTH_WINDOWS_CLIENT_STRING = "xbox.live.authinfo.windows.client.string"
    const val AUTH_LICENSE_SIGNATURE_KEY_VERSION = "xbox.live.authinfo.license.signature.key.version"
    const val AUTH_CLIENT_CAPABILITIES = "xbox.live.authinfo.client.capabilities"
    const val SSO_FLAGS = "xbox.live.sso.flags"

    const val POLICY_TOKEN_BROKER = "xbox.live.policy.token.broker"
    const val POLICY_MBI_SSL = "xbox.live.policy.mbi.ssl"
    const val SCOPE_DEVICE_STS = "xbox.live.scope.device.sts"
    const val SCOPE_STORE = "xbox.live.scope.store"
    const val SCOPE_XBOX_USER_AUTH = "xbox.live.scope.xbox.user.auth"
    const val APP_STORE = "xbox.live.app.store"
    const val APP_TOKEN_BROKER = "xbox.live.app.token.broker"
    const val SILENT_UX = "xbox.live.silent.ux"
    const val SILENT_USERNAME = "xbox.live.silent.username"

    const val RUNTIME_DEVICE_SCOPE = "xbox.live.runtime.device.scope"
    const val RUNTIME_USER_SCOPE = "xbox.live.runtime.user.scope"
    const val RUNTIME_FULL_TRUST_SCOPE = "xbox.live.runtime.full.trust.scope"
    const val RUNTIME_SIGNIN_SCOPE = "xbox.live.runtime.signin.scope"

    const val SIGNIN_URL = "xbox.live.signin.url"
    const val SIGNIN_CLIENT_ID = "xbox.live.signin.client.id"
    const val SIGNIN_MARKET = "xbox.live.signin.market"
    const val SIGNIN_USER_SCOPE = "xbox.live.signin.user.scope"
    const val SIGNIN_USER_AGENT = "xbox.live.signin.user.agent"
    const val SIGNIN_POST_URL = "xbox.live.signin.post.url"
    const val SIGNIN_CXH_CAPABILITIES = "xbox.live.signin.cxh.capabilities"
    const val SIGNIN_CXH_MSA_BINARY_VERSION = "xbox.live.signin.cxh.msa.binary.version"
    const val SIGNIN_CXH_IDENTITY_BINARY_VERSION = "xbox.live.signin.cxh.identity.binary.version"
    const val SIGNIN_CXH_OS_VERSION_INFO = "xbox.live.signin.cxh.os.version.info"
    const val SIGNIN_CXH_PLATFORM = "xbox.live.signin.cxh.platform"
    const val SIGNIN_CXH_PROTOCOL = "xbox.live.signin.cxh.protocol"
    const val SIGNIN_CXH_SOURCE = "xbox.live.signin.cxh.source"
    const val SIGNIN_HOST_APP = "xbox.live.signin.host.app"

    const val LICENSING_URL = "xbox.live.licensing.url"
    const val LICENSING_FROM = "xbox.live.licensing.from"
    const val LICENSING_USER_AGENT = "xbox.live.licensing.user.agent"
    const val LICENSING_CLIENT_CHALLENGE = "xbox.live.licensing.client.challenge"
    const val LICENSING_CONCURRENCY_MODE = "xbox.live.licensing.concurrency.mode"
    const val LICENSING_LICENSE_VERSION = "xbox.live.licensing.license.version"
    const val LICENSING_IDENTITY_TYPE = "xbox.live.licensing.identity.type"
    const val LICENSING_DEVICE_CONTEXT = "xbox.live.licensing.device.context"
    const val LICENSING_USER_SID = "xbox.live.licensing.user.sid"
}

/** Values the helper archive supplies; none of them are part of the app. */
data class LiveConfig(
    val rst2Url: String,
    val rst2To: String,
    val deviceAddUrl: String,
    val userAgent: String,
    val clientName: String,
    val clientVersion: String,
    val binaryVersion: String,
    val hostingApp: String,
    val uiVersion: String,
    val inlineUx: String,
    val isAdmin: String,
    val requestParams: String,
    val windowsClientString: String,
    val licenseSignatureKeyVersion: String,
    val clientCapabilities: String,
    val ssoFlags: String,
    val policyTokenBroker: String,
    val policyMbiSsl: String,
    val scopeDeviceSts: String,
    val scopeStore: String,
    val scopeXboxUserAuth: String,
    val appStore: String,
    val appTokenBroker: String,
    val silentUx: String,
    val silentUsername: String,
    val runtimeDeviceScope: String,
    /** Template with `{scope}` and `{clientId}`. */
    val runtimeUserScope: String,
    val runtimeFullTrustScope: String,
    val runtimeSignInScope: String,
    /** Template with `{market}` and `{clientId}`. */
    val signInUrl: String,
    val signInClientId: String,
    val signInMarket: String,
    val signInUserScope: String,
    val signInUserAgent: String,
    val signInPostUrl: String,
    val cxhCapabilities: String,
    val cxhMsaBinaryVersion: String,
    val cxhIdentityBinaryVersion: String,
    val cxhOsVersionInfo: String,
    val cxhPlatform: String,
    val cxhProtocol: String,
    val cxhSource: String,
    val signInHostApp: String,
    val licensingUrl: String,
    val licensingFrom: String,
    val licensingUserAgent: String,
    val licensingClientChallenge: String,
    val licensingConcurrencyMode: String,
    val licensingLicenseVersion: Int,
    val licensingIdentityType: String,
    val licensingDeviceContext: String,
    val licensingUserSid: String,
) {
    companion object {
        /** Builds the config from a key lookup; a missing key fails with its name. */
        fun from(lookup: (String) -> String?): LiveConfig {
            fun v(key: String) = lookup(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Xbox helper configuration is missing $key")
            return with(LiveConfigKeys) {
                LiveConfig(
                    rst2Url = v(RST2_URL),
                    rst2To = v(RST2_TO),
                    deviceAddUrl = v(DEVICE_ADD_URL),
                    userAgent = v(USER_AGENT),
                    clientName = v(CLIENT_NAME),
                    clientVersion = v(CLIENT_VERSION),
                    binaryVersion = v(BINARY_VERSION),
                    hostingApp = v(AUTH_HOSTING_APP),
                    uiVersion = v(AUTH_UI_VERSION),
                    inlineUx = v(AUTH_INLINE_UX),
                    isAdmin = v(AUTH_IS_ADMIN),
                    requestParams = v(AUTH_REQUEST_PARAMS),
                    windowsClientString = v(AUTH_WINDOWS_CLIENT_STRING),
                    licenseSignatureKeyVersion = v(AUTH_LICENSE_SIGNATURE_KEY_VERSION),
                    clientCapabilities = v(AUTH_CLIENT_CAPABILITIES),
                    ssoFlags = v(SSO_FLAGS),
                    policyTokenBroker = v(POLICY_TOKEN_BROKER),
                    policyMbiSsl = v(POLICY_MBI_SSL),
                    scopeDeviceSts = v(SCOPE_DEVICE_STS),
                    scopeStore = v(SCOPE_STORE),
                    scopeXboxUserAuth = v(SCOPE_XBOX_USER_AUTH),
                    appStore = v(APP_STORE),
                    appTokenBroker = v(APP_TOKEN_BROKER),
                    silentUx = v(SILENT_UX),
                    silentUsername = v(SILENT_USERNAME),
                    runtimeDeviceScope = v(RUNTIME_DEVICE_SCOPE),
                    runtimeUserScope = v(RUNTIME_USER_SCOPE),
                    runtimeFullTrustScope = v(RUNTIME_FULL_TRUST_SCOPE),
                    runtimeSignInScope = v(RUNTIME_SIGNIN_SCOPE),
                    signInUrl = v(SIGNIN_URL),
                    signInClientId = v(SIGNIN_CLIENT_ID),
                    signInMarket = v(SIGNIN_MARKET),
                    signInUserScope = v(SIGNIN_USER_SCOPE),
                    signInUserAgent = v(SIGNIN_USER_AGENT),
                    signInPostUrl = v(SIGNIN_POST_URL),
                    cxhCapabilities = v(SIGNIN_CXH_CAPABILITIES),
                    cxhMsaBinaryVersion = v(SIGNIN_CXH_MSA_BINARY_VERSION),
                    cxhIdentityBinaryVersion = v(SIGNIN_CXH_IDENTITY_BINARY_VERSION),
                    cxhOsVersionInfo = v(SIGNIN_CXH_OS_VERSION_INFO),
                    cxhPlatform = v(SIGNIN_CXH_PLATFORM),
                    cxhProtocol = v(SIGNIN_CXH_PROTOCOL),
                    cxhSource = v(SIGNIN_CXH_SOURCE),
                    signInHostApp = v(SIGNIN_HOST_APP),
                    licensingUrl = v(LICENSING_URL),
                    licensingFrom = v(LICENSING_FROM),
                    licensingUserAgent = v(LICENSING_USER_AGENT),
                    licensingClientChallenge = v(LICENSING_CLIENT_CHALLENGE),
                    licensingConcurrencyMode = v(LICENSING_CONCURRENCY_MODE),
                    licensingLicenseVersion = v(LICENSING_LICENSE_VERSION).toInt(),
                    licensingIdentityType = v(LICENSING_IDENTITY_TYPE),
                    licensingDeviceContext = v(LICENSING_DEVICE_CONTEXT),
                    licensingUserSid = v(LICENSING_USER_SID),
                )
            }
        }
    }
}
