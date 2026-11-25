// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import app.gamenative.service.xbox.StsToken
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

internal object Ns {
    const val S = "http://www.w3.org/2003/05/soap-envelope"
    const val PS = "http://schemas.microsoft.com/Passport/SoapServices/PPCRL"
    const val WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
    const val WSP = "http://schemas.xmlsoap.org/ws/2004/09/policy"
    const val WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
    const val WSA = "http://www.w3.org/2005/08/addressing"
    const val WSSC = "http://schemas.xmlsoap.org/ws/2005/02/sc"
    const val WST = "http://schemas.xmlsoap.org/ws/2005/02/trust"
    const val XENC = "http://www.w3.org/2001/04/xmlenc#"
    const val DS = XmlDsig.NS

    const val ACTION_ISSUE = "http://schemas.xmlsoap.org/ws/2005/02/trust/RST/Issue"
    const val REQUEST_ISSUE = "http://schemas.xmlsoap.org/ws/2005/02/trust/Issue"
    const val DERIVED_KEY_ALGORITHM = "urn:liveid:SP800108_CTR_HMAC_SHA256_DOUBLEDERIVED"
    const val SAML_ASSERTION_ID = "http://docs.oasis-open.org/wss/2004/XX/oasis-2004XX-wss-saml-token-profile-1.0#SAMLAssertionID"
    const val DEVICE_VALUE_TYPE = "urn:liveid:device"
    const val TRIPLEDES = "http://www.w3.org/2001/04/xmlenc#tripledes-cbc"
    const val XENC_ELEMENT = "http://www.w3.org/2001/04/xmlenc#Element"
    const val PASSPORT_STS = "http://Passport.NET/STS"
}

/** One RST2 request entry: the address the token applies to and the optional policy reference URI. */
data class ScopePolicy(val address: String, val policy: String? = null)

/** How an RST2 request is signed: with the device RSA key, or with the device token's HMAC secret. */
sealed class RstSignature {
    class Rsa(val key: RSAPrivateCrtKey) : RstSignature()
    class Hmac(val clepSecret: ByteArray, val tpmSecret: ByteArray = ByteArray(0)) : RstSignature()

    val method: String get() = if (this is Hmac) XmlDsig.METHOD_HMAC_SHA256 else XmlDsig.METHOD_RSA_SHA256

    fun hmacKey(nonce: ByteArray): ByteArray? = (this as? Hmac)?.let { LiveCrypto.sessionKey(it.clepSecret, it.tpmSecret, nonce) }

    fun signingKey(nonce: ByteArray): XmlDsig.Key = when (this) {
        is Hmac -> XmlDsig.Key.Hmac(LiveCrypto.sessionKey(clepSecret, tpmSecret, nonce))
        is Rsa -> XmlDsig.Key.RsaPrivate(key)
    }

    fun verifyingKey(nonce: ByteArray): XmlDsig.Key = when (this) {
        is Hmac -> XmlDsig.Key.Hmac(LiveCrypto.sessionKey(clepSecret, tpmSecret, nonce))
        is Rsa -> XmlDsig.Key.RsaPublic(
            java.security.KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(key.modulus, key.publicExponent)),
        )
    }
}

/** `<EncryptedData>` as login.live.com issues it for legacy tokens; its serialized form is what the token store keeps. */
data class EncryptedData(
    val id: String,
    val namespace: String,
    val type: String,
    val encryptionAlgorithm: String,
    val keyName: String?,
    val securityTokenReference: String?,
    val cipherValue: String,
) {
    fun toElement(): XElement = XElement("", "EncryptedData", namespace).apply {
        attr("Id", id)
        attr("Type", type)
        el("", "EncryptionMethod", namespace) { attr("Algorithm", encryptionAlgorithm) }
        el("ds", "KeyInfo", Ns.DS) {
            keyName?.let { el("ds", "KeyName", Ns.DS) { text(it) } }
            securityTokenReference?.let { uri ->
                el("wsse", "SecurityTokenReference", Ns.WSSE) { el("wsse", "Reference", Ns.WSSE) { attr("URI", uri) } }
            }
        }
        el("", "CipherData", namespace) { el("", "CipherValue", namespace) { text(cipherValue) } }
    }

    /** Self-contained XML string with its namespace declarations. */
    fun serialize(): String = ExcC14n.canonicalString(toElement())

    companion object {
        fun from(e: XElement): EncryptedData {
            val keyInfo = e.child("KeyInfo")
            return EncryptedData(
                id = e.attribute("Id") ?: throw LiveException("EncryptedData has no Id"),
                namespace = e.declarations[""]?.takeIf { it.isNotEmpty() } ?: e.namespace.ifEmpty { Ns.XENC },
                type = e.attribute("Type") ?: Ns.XENC_ELEMENT,
                encryptionAlgorithm = e.child("EncryptionMethod")?.attribute("Algorithm") ?: Ns.TRIPLEDES,
                keyName = keyInfo?.childText("KeyName"),
                securityTokenReference = keyInfo?.path("SecurityTokenReference", "Reference")?.attribute("URI"),
                cipherValue = e.path("CipherData", "CipherValue")?.textContent()
                    ?: throw LiveException("EncryptedData has no CipherValue"),
            )
        }

        fun parse(xml: String): EncryptedData = from(LiveXml.parse(xml, namespaceAware = false))
    }
}

/** Builds and signs a login.live.com RST2 envelope. */
class RstRequestBuilder(
    private val cfg: LiveConfig,
    private val now: Instant = Instant.now(),
    private val nonce: ByteArray = LiveCrypto.nonce(),
) {
    private var ssoFlags = ""
    private var hostingApp = cfg.hostingApp
    private var inlineUx = cfg.inlineUx
    private var inlineFt: String? = null
    private var licenseSignatureKeyVersion: String? = cfg.licenseSignatureKeyVersion
    private var username: String? = null
    private var usernameHint: String? = null
    private var deviceToken: StsToken.Legacy? = null
    private var userToken: StsToken.Legacy? = null
    private var signature: RstSignature? = null
    private val scopes = mutableListOf<ScopePolicy>()

    fun ssoFlags(value: String) = apply { ssoFlags = value }
    fun hostingApp(value: String) = apply { hostingApp = value }
    fun inlineUx(value: String) = apply { inlineUx = value }
    fun inlineFt(value: String) = apply { inlineFt = value }
    fun licenseSignatureKeyVersion(value: String?) = apply { licenseSignatureKeyVersion = value }

    /** `wsse:UsernameToken` naming the device account. */
    fun deviceUsername(value: String) = apply { username = value }

    /** `wsse:UsernameToken` carrying a user hint with LoginOption 1. */
    fun userHint(value: String) = apply { usernameHint = value }
    fun deviceToken(value: StsToken.Legacy) = apply { deviceToken = value }
    fun userToken(value: StsToken.Legacy) = apply { userToken = value }
    fun signature(value: RstSignature) = apply { signature = value }
    fun scope(value: ScopePolicy) = apply { scopes.add(value) }

    fun build(): RstRequest {
        require(scopes.isNotEmpty()) { "RST2 request needs at least one scope" }
        val created = now.truncatedTo(ChronoUnit.SECONDS)
        val envelope = XElement("s", "Envelope", Ns.S)
        val header = envelope.el("s", "Header", Ns.S)
        header.el("wsa", "Action", Ns.WSA) {
            attr("s", "mustUnderstand", Ns.S, "1")
            text(Ns.ACTION_ISSUE)
        }
        header.el("wsa", "To", Ns.WSA) {
            attr("s", "mustUnderstand", Ns.S, "1")
            text(cfg.rst2To)
        }
        header.el("wsa", "MessageID", Ns.WSA) { text(created.epochSecond.toString()) }
        header.el("ps", "AuthInfo", Ns.PS) {
            attr("Id", "PPAuthInfo")
            el("ps", "SSOFlags", Ns.PS) { text(ssoFlags) }
            el("ps", "HostingApp", Ns.PS) { text(hostingApp) }
            el("ps", "BinaryVersion", Ns.PS) { text(cfg.binaryVersion) }
            el("ps", "UIVersion", Ns.PS) { text(cfg.uiVersion) }
            el("ps", "InlineUX", Ns.PS) { text(inlineUx) }
            el("ps", "IsAdmin", Ns.PS) { text(cfg.isAdmin) }
            el("ps", "Cookies", Ns.PS)
            el("ps", "RequestParams", Ns.PS) { text(cfg.requestParams) }
            el("ps", "WindowsClientString", Ns.PS) { text(cfg.windowsClientString) }
            licenseSignatureKeyVersion?.let { v -> el("ps", "LicenseSignatureKeyVersion", Ns.PS) { text(v) } }
            el("ps", "ClientCapabilities", Ns.PS) { text(cfg.clientCapabilities) }
            inlineFt?.let { v -> el("ps", "InlineFT", Ns.PS) { text(v) } }
        }
        val security = header.el("wsse", "Security", Ns.WSSE)
        if (username != null || usernameHint != null) {
            security.el("wsse", "UsernameToken", Ns.WSSE) {
                attr("wsu", "Id", Ns.WSU, if (username != null) "devicesoftware" else "user")
                username?.let { v -> el("wsse", "Username", Ns.WSSE) { text(v) } }
                usernameHint?.let { v ->
                    el("wsse", "UsernameHint", Ns.WSSE) { text(v) }
                    el("wsse", "LoginOption", Ns.WSSE) { text("1") }
                }
            }
        }
        var binaryTokenId: String? = null
        val device = deviceToken
        val user = userToken
        when {
            device != null && user != null -> {
                security.add(EncryptedData.parse(user.token).toElement())
                binaryTokenId = "DeviceDAToken"
                security.el("wsse", "BinarySecurityToken", Ns.WSSE) {
                    attr("ValueType", Ns.DEVICE_VALUE_TYPE)
                    attr("id", "DeviceDAToken")
                    text(device.token)
                }
            }
            device != null -> security.add(EncryptedData.parse(device.token).toElement())
            user != null -> throw LiveException("Builder was provided with invalid set of tokens")
        }
        val signature = signature
        if (signature is RstSignature.Hmac) {
            security.el("wssc", "DerivedKeyToken", Ns.WSSC) {
                attr("wsu", "Id", Ns.WSU, "SignKey")
                attr("Algorithm", Ns.DERIVED_KEY_ALGORITHM)
                el("wsse", "RequestedTokenReference", Ns.WSSE) {
                    el("wsse", "KeyIdentifier", Ns.WSSE) { attr("ValueType", Ns.SAML_ASSERTION_ID) }
                    el("wsse", "Reference", Ns.WSSE) { attr("URI", binaryTokenId?.let { "#$it" } ?: "") }
                }
                el("wssc", "Nonce", Ns.WSSC) { text(Base64.getEncoder().encodeToString(nonce)) }
            }
        }
        security.el("wsu", "Timestamp", Ns.WSU) {
            attr("wsu", "Id", Ns.WSU, "Timestamp")
            el("wsu", "Created", Ns.WSU) { text(created.toString()) }
            el("wsu", "Expires", Ns.WSU) { text(created.plus(5, ChronoUnit.MINUTES).toString()) }
        }
        val bodyId = if (scopes.size > 1) "RSTS" else "RST0"
        val signatureElement = signature?.let { sig ->
            security.el("", "Signature", Ns.DS) {
                el("", "SignedInfo", Ns.DS) {
                    el("", "CanonicalizationMethod", Ns.DS) { attr("Algorithm", XmlDsig.TRANSFORM_EXC_C14N) }
                    el("", "SignatureMethod", Ns.DS) { attr("Algorithm", sig.method) }
                    for (uri in listOf("#$bodyId", "#Timestamp", "#PPAuthInfo")) {
                        el("", "Reference", Ns.DS) {
                            attr("URI", uri)
                            el("", "Transforms", Ns.DS) {
                                el("", "Transform", Ns.DS) { attr("Algorithm", XmlDsig.TRANSFORM_EXC_C14N) }
                            }
                            el("", "DigestMethod", Ns.DS) { attr("Algorithm", XmlDsig.DIGEST_SHA256) }
                            el("", "DigestValue", Ns.DS)
                        }
                    }
                }
                el("", "SignatureValue", Ns.DS)
                if (sig is RstSignature.Hmac) {
                    el("", "KeyInfo", Ns.DS) {
                        el("wsse", "SecurityTokenReference", Ns.WSSE) {
                            el("wsse", "Reference", Ns.WSSE) { attr("URI", "#SignKey") }
                        }
                    }
                }
            }
        }
        val body = envelope.el("s", "Body", Ns.S)
        val tokens = scopes.mapIndexed { i, scope ->
            XElement("wst", "RequestSecurityToken", Ns.WST).apply {
                attr("Id", "RST$i")
                el("wst", "RequestType", Ns.WST) { text(Ns.REQUEST_ISSUE) }
                el("wsp", "AppliesTo", Ns.WSP) {
                    el("wsa", "EndpointReference", Ns.WSA) { el("wsa", "Address", Ns.WSA) { text(scope.address) } }
                }
                scope.policy?.let { uri -> el("wsp", "PolicyReference", Ns.WSP) { attr("URI", uri) } }
            }
        }
        if (tokens.size > 1) {
            body.el("ps", "RequestMultipleSecurityTokens", Ns.PS) {
                attr("Id", "RSTS")
                tokens.forEach { add(it) }
            }
        } else {
            body.add(tokens[0])
        }
        if (signature != null && signatureElement != null) {
            XmlDsig.sign(envelope, signatureElement, signature.signingKey(nonce))
        }
        return RstRequest(ExcC14n.canonicalString(envelope), signature)
    }
}

class RstRequest(val xml: String, val signature: RstSignature?)

/** Passport properties (`psf:pp`) carried in RST2 response headers. */
data class PassportProperties(
    val serverVersion: String?,
    val reqStatus: String?,
    val inlineAuthUrl: String?,
    val inlineEndAuthUrl: String?,
    val authStateInternal: String?,
    val userSessionKey: String?,
) {
    companion object {
        fun from(e: XElement) = PassportProperties(
            serverVersion = e.childText("serverVersion"),
            reqStatus = e.childText("reqstatus"),
            inlineAuthUrl = e.childText("inlineauthurl"),
            inlineEndAuthUrl = e.childText("inlineendauthurl"),
            authStateInternal = e.childText("authstateinternal"),
            userSessionKey = e.childText("UserSessionKey"),
        )
    }
}

/** One `wst:RequestSecurityTokenResponse`. */
data class SecurityTokenResponse(
    val tokenType: String,
    val address: String,
    val created: String,
    val expires: String,
    val encryptedData: EncryptedData?,
    val binarySecurityToken: String?,
    val binarySecret: String?,
    val encryptedKey: String?,
) {
    val expiresEpochSeconds: Long get() = parseLiveTime(expires).epochSecond

    fun toStsToken(): StsToken = when (tokenType) {
        "urn:passport:legacy" -> {
            val data = encryptedData ?: throw LiveException("Legacy token has no EncryptedData")
            StsToken.Legacy(
                keyName = data.keyName,
                token = data.serialize(),
                binarySecret = binarySecret,
                tpmKey = encryptedKey,
                created = created,
                expires = expires,
            )
        }
        "urn:passport:compact" -> StsToken.Compact(binarySecurityToken ?: throw LiveException("Compact token is empty"))
        "urn:passport:delegationcompact" ->
            StsToken.Compact("d=" + (binarySecurityToken ?: throw LiveException("Delegation token is empty")))
        else -> throw LiveException("Unsupported token type $tokenType")
    }

    /** Store key: the legacy token's key name, else the address it applies to. */
    fun storeAddress(token: StsToken = toStsToken()): String = (token as? StsToken.Legacy)?.keyName ?: address

    companion object {
        fun from(e: XElement): SecurityTokenResponse {
            val requested = e.child("RequestedSecurityToken")
            val proof = e.child("RequestedProofToken")
            val lifetime = e.child("Lifetime") ?: throw LiveException("Token response has no Lifetime")
            return SecurityTokenResponse(
                tokenType = e.childText("TokenType") ?: throw LiveException("Token response has no TokenType"),
                address = e.path("AppliesTo", "EndpointReference", "Address")?.textContent()
                    ?: throw LiveException("Token response has no AppliesTo"),
                created = lifetime.childText("Created") ?: throw LiveException("Token lifetime has no Created"),
                expires = lifetime.childText("Expires") ?: throw LiveException("Token lifetime has no Expires"),
                encryptedData = requested?.child("EncryptedData")?.let(EncryptedData::from),
                binarySecurityToken = requested?.child("BinarySecurityToken")?.textContent(),
                binarySecret = proof?.childText("BinarySecret"),
                encryptedKey = proof?.path("EncryptedKey", "CipherData", "CipherValue")?.textContent(),
            )
        }
    }
}

/** Body of a decoded RST2 response. */
sealed class RstBody {
    data class Responses(val tokens: List<SecurityTokenResponse>, val collection: Boolean) : RstBody()
    data class Fault(val code: String?, val reason: String?) : RstBody()
    data class Other(val name: String) : RstBody()
}

class RstResponse(val body: RstBody, val properties: PassportProperties?)

fun parseLiveTime(value: String): Instant =
    try {
        java.time.OffsetDateTime.parse(value.trim()).toInstant()
    } catch (e: java.time.format.DateTimeParseException) {
        throw LiveException("Invalid timestamp $value", e)
    }

object RstResponseDecoder {
    fun decode(xml: String, signature: RstSignature?): RstResponse {
        val envelope = LiveXml.parse(xml)
        val header = envelope.child("Header")
        val security = header?.child("Security")
        var properties = header?.child("pp")?.let(PassportProperties::from)
        var bodyElement = envelope.child("Body")?.elements()?.firstOrNull()
            ?: throw LiveException("RST2 response has no body")
        if (signature != null) {
            val nonces = security?.childrenNamed("DerivedKeyToken").orEmpty().associate { token ->
                (token.attribute("Id") ?: "") to (token.childText("Nonce") ?: "")
            }
            val sig = security?.childrenNamed("Signature")?.firstOrNull { it.namespace == Ns.DS }
            val keyReference = sig?.path("KeyInfo", "SecurityTokenReference", "Reference")?.attribute("URI")
            if (sig != null && keyReference != null) {
                val nonce = decodeNonce(nonces, keyReference)
                XmlDsig.verify(envelope, sig, signature.verifyingKey(nonce))?.let {
                    throw LiveException("The signature verification failed - $it")
                }
            }
            if (properties == null) {
                header?.child("EncryptedPP")?.child("EncryptedData")?.let { data ->
                    properties = PassportProperties.from(decrypt(data, signature, nonces))
                }
            }
            if (bodyElement.localName == "EncryptedData") bodyElement = decrypt(bodyElement, signature, nonces)
        }
        val body = when (bodyElement.localName) {
            "RequestSecurityTokenResponseCollection" -> RstBody.Responses(
                bodyElement.childrenNamed("RequestSecurityTokenResponse").map(SecurityTokenResponse::from),
                collection = true,
            )
            "RequestSecurityTokenResponse" -> RstBody.Responses(listOf(SecurityTokenResponse.from(bodyElement)), collection = false)
            "Fault" -> RstBody.Fault(
                code = bodyElement.descendants().firstOrNull { it.localName == "Value" }?.textContent(),
                reason = bodyElement.descendants().firstOrNull { it.localName == "Text" }?.textContent(),
            )
            else -> RstBody.Other(bodyElement.localName)
        }
        return RstResponse(body, properties)
    }

    private fun decodeNonce(nonces: Map<String, String>, reference: String): ByteArray {
        val nonce = nonces[reference.removePrefix("#")]
            ?: throw LiveException("Response is malformed, unable to find nonce for decryption")
        return Base64.getMimeDecoder().decode(nonce)
    }

    private fun decrypt(data: XElement, signature: RstSignature, nonces: Map<String, String>): XElement {
        val reference = data.path("KeyInfo", "SecurityTokenReference", "Reference")?.attribute("URI")
            ?: throw LiveException("Encrypted data has no key reference")
        val key = signature.hmacKey(decodeNonce(nonces, reference)) ?: throw LiveException("Unexpected error deriving hmac key")
        val cipher = Base64.getMimeDecoder().decode(data.path("CipherData", "CipherValue")?.textContent()?.trim().orEmpty())
        val plain = String(LiveCrypto.decryptCipherValue(cipher, key), Charsets.UTF_8)
        return LiveXml.parse(plain, namespaceAware = false)
    }
}
