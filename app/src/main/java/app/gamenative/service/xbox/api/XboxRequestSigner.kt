// Based on xal-rs (https://github.com/xodus-gaming/xal-rs), MIT.
package app.gamenative.service.xbox.api

import java.math.BigInteger
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import org.json.JSONObject

/** JWK of the signing key, sent as ProofKey so the service can check later signatures. */
data class XboxProofKey(val x: String, val y: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("alg", "ES256")
        .put("crv", "P-256")
        .put("kty", "EC")
        .put("use", "sig")
        .put("x", x)
        .put("y", y)
}

/**
 * Computes the `Signature` header for Xbox Live requests: base64 of the 4-byte policy version,
 * the 8-byte FILETIME timestamp and the raw P-256 r||s over SHA-256 of
 * version, timestamp, method, path and query, Authorization and the body (each followed by a NUL).
 * Device and title tokens are bound to this key, so one signer must serve the whole chain.
 */
class XboxRequestSigner(val privateKey: ECPrivateKey, val publicKey: ECPublicKey) {
    val proofKey: XboxProofKey
        get() = XboxProofKey(
            x = base64Url(fixed32(publicKey.w.affineX)),
            y = base64Url(fixed32(publicKey.w.affineY)),
        )

    val encodedPrivateKey: ByteArray get() = privateKey.encoded
    val encodedPublicKey: ByteArray get() = publicKey.encoded

    fun sign(
        policy: XboxSigningPolicy,
        method: String,
        url: String,
        authorization: String?,
        body: ByteArray,
        timestamp: Instant = Instant.now(),
    ): String {
        val uri = URI(url)
        val pathAndQuery = (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
        val signedBody = if (method.equals("POST", ignoreCase = true)) body else ByteArray(0)
        return signRaw(policy.version, timestamp, method, pathAndQuery, authorization ?: "", signedBody, policy.maxBodyBytes)
    }

    fun signRaw(
        policyVersion: Int,
        timestamp: Instant,
        method: String,
        pathAndQuery: String,
        authorization: String,
        body: ByteArray,
        maxBodyBytes: Long,
    ): String {
        val version = int32(policyVersion)
        val filetime = int64(fileTime(timestamp))
        val message = messageData(version, filetime, method, pathAndQuery, authorization, body, maxBodyBytes)
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(message)
            sign()
        }
        return Base64.getEncoder().encodeToString(version + filetime + derToRaw(der))
    }

    fun verify(
        header: String,
        method: String,
        pathAndQuery: String,
        authorization: String,
        body: ByteArray,
        maxBodyBytes: Long,
    ): Boolean {
        val bytes = Base64.getDecoder().decode(header)
        if (bytes.size != 76) return false
        val message = messageData(
            bytes.copyOfRange(0, 4), bytes.copyOfRange(4, 12), method, pathAndQuery, authorization, body, maxBodyBytes,
        )
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(message)
            verify(rawToDer(bytes.copyOfRange(12, 76)))
        }
    }

    companion object {
        private const val FILETIME_UNIX_OFFSET_SECONDS = 11_644_473_600L

        fun generate(): XboxRequestSigner {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return XboxRequestSigner(pair.private as ECPrivateKey, pair.public as ECPublicKey)
        }

        /** Restores a signer from [encodedPrivateKey] (PKCS#8) and [encodedPublicKey] (X.509). */
        fun fromEncoded(pkcs8: ByteArray, x509: ByteArray): XboxRequestSigner {
            val factory = KeyFactory.getInstance("EC")
            return XboxRequestSigner(
                factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as ECPrivateKey,
                factory.generatePublic(X509EncodedKeySpec(x509)) as ECPublicKey,
            )
        }

        fun fileTime(timestamp: Instant): Long =
            (timestamp.epochSecond + FILETIME_UNIX_OFFSET_SECONDS) * 10_000_000L + timestamp.nano / 100

        /** SHA-256 of the signed data, as xal-rs computes it before signing. */
        fun prehash(
            policyVersion: ByteArray,
            timestamp: ByteArray,
            method: String,
            pathAndQuery: String,
            authorization: String,
            body: ByteArray,
            maxBodyBytes: Long,
        ): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(messageData(policyVersion, timestamp, method, pathAndQuery, authorization, body, maxBodyBytes))

        private fun messageData(
            policyVersion: ByteArray,
            timestamp: ByteArray,
            method: String,
            pathAndQuery: String,
            authorization: String,
            body: ByteArray,
            maxBodyBytes: Long,
        ): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            fun part(bytes: ByteArray) { out.write(bytes); out.write(0) }
            part(policyVersion)
            part(timestamp)
            part(method.uppercase().toByteArray(Charsets.UTF_8))
            part(pathAndQuery.toByteArray(Charsets.UTF_8))
            part(authorization.toByteArray(Charsets.UTF_8))
            part(body.copyOf(minOf(maxBodyBytes, body.size.toLong()).toInt()))
            return out.toByteArray()
        }

        fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

        fun int64(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

        fun fixed32(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }

        private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** DER SEQUENCE { INTEGER r, INTEGER s } to 64 bytes r||s. */
        fun derToRaw(der: ByteArray): ByteArray {
            var i = 0
            require(der[i++].toInt() == 0x30) { "Not a DER signature" }
            i += derLengthSize(der, i)
            fun readInt(): BigInteger {
                require(der[i++].toInt() == 0x02) { "Not a DER integer" }
                val len = der[i].toInt() and 0xff
                i += 1
                val value = BigInteger(1, der.copyOfRange(i, i + len))
                i += len
                return value
            }
            val r = readInt()
            val s = readInt()
            return fixed32(r) + fixed32(s)
        }

        fun rawToDer(raw: ByteArray): ByteArray {
            fun integer(bytes: ByteArray): ByteArray {
                val v = BigInteger(1, bytes).toByteArray()
                return byteArrayOf(0x02, v.size.toByte()) + v
            }
            val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
            return byteArrayOf(0x30, body.size.toByte()) + body
        }

        private fun derLengthSize(der: ByteArray, at: Int): Int {
            val first = der[at].toInt() and 0xff
            return if (first < 0x80) 1 else 1 + (first and 0x7f)
        }
    }
}

/** A signature policy from the title endpoints list. */
data class XboxSigningPolicy(val version: Int, val maxBodyBytes: Long, val algorithms: List<String>)

data class XboxTitleEndpoint(
    val protocol: String,
    val host: String,
    val hostType: String,
    val path: String?,
    val relyingParty: String?,
    val tokenType: String?,
    val signaturePolicyIndex: Int?,
)

/** title.mgt endpoints: which hosts take signed requests, with which policy, and their relying party. */
class XboxTitleEndpoints(val endpoints: List<XboxTitleEndpoint>, val policies: List<XboxSigningPolicy>) {
    fun policyFor(url: String): XboxSigningPolicy? =
        match(url, requirePolicy = true)?.let { policies.getOrNull(it.signaturePolicyIndex!!) }

    fun relyingPartyFor(url: String): String? = match(url, requirePolicy = false)?.relyingParty

    private fun match(url: String, requirePolicy: Boolean): XboxTitleEndpoint? {
        val uri = URI(url)
        val scheme = when (uri.scheme?.lowercase()) {
            "wss" -> "https"
            "ws" -> "http"
            "http", "https" -> uri.scheme.lowercase()
            else -> throw IllegalArgumentException("Url with invalid protocol passed, expected http or https")
        }
        val host = uri.host ?: return null
        val path = uri.rawPath ?: ""
        return endpoints
            .filter { e ->
                e.protocol.equals(scheme, ignoreCase = true) &&
                    when (e.hostType) {
                        "fqdn" -> if (requirePolicy) host == e.host else host.equals(e.host, ignoreCase = true)
                        "wildcard" -> host.endsWith(e.host.trimStart('*'))
                        else -> false
                    } &&
                    (e.path == null || e.path == path) &&
                    (!requirePolicy || e.signaturePolicyIndex != null)
            }
            .maxByOrNull { it.host.length }
    }

    companion object {
        fun parse(json: String): XboxTitleEndpoints {
            val root = JSONObject(json)
            val eps = root.getJSONArray("EndPoints")
            val endpoints = (0 until eps.length()).map { i ->
                val e = eps.getJSONObject(i)
                XboxTitleEndpoint(
                    protocol = e.getString("Protocol"),
                    host = e.getString("Host"),
                    hostType = e.getString("HostType"),
                    path = e.str("Path"),
                    relyingParty = e.str("RelyingParty"),
                    tokenType = e.str("TokenType"),
                    signaturePolicyIndex = if (e.has("SignaturePolicyIndex") && !e.isNull("SignaturePolicyIndex")) e.getInt("SignaturePolicyIndex") else null,
                )
            }
            val pols = root.optJSONArray("SignaturePolicies")
            val policies = (0 until (pols?.length() ?: 0)).map { i ->
                val p = pols!!.getJSONObject(i)
                val algs = p.optJSONArray("SupportedAlgorithms")
                XboxSigningPolicy(
                    version = p.getInt("Version"),
                    maxBodyBytes = p.getLong("MaxBodyBytes"),
                    algorithms = (0 until (algs?.length() ?: 0)).map { algs!!.getString(it) },
                )
            }
            return XboxTitleEndpoints(endpoints, policies)
        }
    }
}

/** MS-CV header value: a random base and a counter bumped before each request. */
class XboxCorrelationVector(private val base: String = randomBase()) {
    private var counter = 0

    @Synchronized
    fun next(): String {
        counter++
        return "$base.$counter"
    }

    companion object {
        private fun randomBase(): String {
            val bytes = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            return Base64.getEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
