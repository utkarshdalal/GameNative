// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** XML-DSig over [XElement] trees: enveloping documents whose references point at elements by Id. */
object XmlDsig {
    const val NS = "http://www.w3.org/2000/09/xmldsig#"
    const val METHOD_HMAC_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#hmac-sha256"
    const val METHOD_RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"
    private const val METHOD_HMAC_SHA1 = "http://www.w3.org/2000/09/xmldsig#hmac-sha1"
    private const val METHOD_RSA_SHA1 = "http://www.w3.org/2000/09/xmldsig#rsa-sha1"
    const val TRANSFORM_EXC_C14N = "http://www.w3.org/2001/10/xml-exc-c14n#"
    private const val TRANSFORM_ENVELOPED = "http://www.w3.org/2000/09/xmldsig#enveloped-signature"
    const val DIGEST_SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256"
    private const val DIGEST_SHA1 = "http://www.w3.org/2000/09/xmldsig#sha1"

    sealed class Key {
        class Hmac(val secret: ByteArray) : Key()
        class RsaPrivate(val key: PrivateKey) : Key()
        class RsaPublic(val key: PublicKey) : Key()
    }

    /** Fills every DigestValue and the SignatureValue of [signature], a template already placed inside [root]. */
    fun sign(root: XElement, signature: XElement, key: Key) {
        val signedInfo = signature.child("SignedInfo") ?: throw LiveException("Signature template has no SignedInfo")
        for (reference in signedInfo.childrenNamed("Reference")) {
            val digest = digestReference(root, signature, reference)
            val value = reference.child("DigestValue") ?: throw LiveException("Reference has no DigestValue")
            value.setText(Base64.getEncoder().encodeToString(digest))
        }
        val method = signedInfo.child("SignatureMethod")?.attribute("Algorithm")
        val bytes = ExcC14n.canonicalize(signedInfo)
        val raw = when (key) {
            is Key.Hmac -> mac(method, key.secret, bytes)
            is Key.RsaPrivate -> java.security.Signature.getInstance(rsaAlgorithm(method)).run {
                initSign(key.key)
                update(bytes)
                sign()
            }
            is Key.RsaPublic -> throw LiveException("A public key cannot sign")
        }
        val value = signature.child("SignatureValue") ?: throw LiveException("Signature template has no SignatureValue")
        value.setText(Base64.getEncoder().encodeToString(raw))
    }

    /** Returns null when [signature] verifies against [key], otherwise the reason it does not. */
    fun verify(root: XElement, signature: XElement, key: Key): String? {
        val signedInfo = signature.child("SignedInfo") ?: return "Signature has no SignedInfo"
        val c14n = signedInfo.child("CanonicalizationMethod")?.attribute("Algorithm")
        if (c14n != TRANSFORM_EXC_C14N) return "Unsupported canonicalization $c14n"
        val references = signedInfo.childrenNamed("Reference")
        if (references.isEmpty()) return "Signature has no references"
        for (reference in references) {
            val expected = runCatching { Base64.getMimeDecoder().decode(reference.childText("DigestValue")?.trim().orEmpty()) }
                .getOrNull() ?: return "Malformed DigestValue"
            val actual = try {
                digestReference(root, signature, reference)
            } catch (e: LiveException) {
                return e.message
            }
            if (!MessageDigest.isEqual(expected, actual)) return "Digest mismatch for ${reference.attribute("URI")}"
        }
        val method = signedInfo.child("SignatureMethod")?.attribute("Algorithm")
        val signatureValue = runCatching { Base64.getMimeDecoder().decode(signature.childText("SignatureValue")?.trim().orEmpty()) }
            .getOrNull() ?: return "Malformed SignatureValue"
        val bytes = ExcC14n.canonicalize(signedInfo, inclusivePrefixes(signedInfo.child("CanonicalizationMethod")))
        return try {
            val ok = when (key) {
                is Key.Hmac -> MessageDigest.isEqual(mac(method, key.secret, bytes), signatureValue)
                is Key.RsaPublic -> java.security.Signature.getInstance(rsaAlgorithm(method)).run {
                    initVerify(key.key)
                    update(bytes)
                    verify(signatureValue)
                }
                is Key.RsaPrivate -> return "A private key cannot verify"
            }
            if (ok) null else "SignatureValue mismatch"
        } catch (e: LiveException) {
            e.message
        } catch (e: java.security.GeneralSecurityException) {
            "Signature check failed: ${e.message}"
        }
    }

    private fun digestReference(root: XElement, signature: XElement, reference: XElement): ByteArray {
        val uri = reference.attribute("URI") ?: ""
        val target: XElement = when {
            uri.isEmpty() -> root
            uri.startsWith("#") -> root.findById(uri.substring(1)) ?: throw LiveException("Reference $uri not found")
            else -> throw LiveException("Unsupported reference $uri")
        }
        var excluded: XElement? = null
        var inclusive = emptySet<String>()
        var canonicalized = false
        for (transform in reference.child("Transforms")?.childrenNamed("Transform").orEmpty()) {
            when (val algorithm = transform.attribute("Algorithm")) {
                TRANSFORM_ENVELOPED -> excluded = signature
                TRANSFORM_EXC_C14N -> {
                    inclusive = inclusivePrefixes(transform)
                    canonicalized = true
                }
                else -> throw LiveException("Unsupported transform $algorithm")
            }
        }
        // Without an explicit transform the node set would go through inclusive canonicalization, which is not implemented.
        if (!canonicalized) throw LiveException("Reference $uri has no exclusive canonicalization")
        val bytes = ExcC14n.canonicalize(target, inclusive, excluded)
        val algorithm = when (reference.child("DigestMethod")?.attribute("Algorithm")) {
            DIGEST_SHA256 -> "SHA-256"
            DIGEST_SHA1 -> "SHA-1"
            else -> throw LiveException("Unsupported digest method")
        }
        return MessageDigest.getInstance(algorithm).digest(bytes)
    }

    private fun inclusivePrefixes(method: XElement?): Set<String> =
        method?.child("InclusiveNamespaces")?.attribute("PrefixList")
            ?.split(' ', '\t', '\n', '\r')?.filter { it.isNotEmpty() }
            ?.map { if (it == "#default") "" else it }?.toSet()
            ?: emptySet()

    private fun mac(method: String?, secret: ByteArray, data: ByteArray): ByteArray {
        val algorithm = when (method) {
            METHOD_HMAC_SHA256 -> "HmacSHA256"
            METHOD_HMAC_SHA1 -> "HmacSHA1"
            else -> throw LiveException("Unsupported signature method $method")
        }
        return Mac.getInstance(algorithm).run {
            init(SecretKeySpec(secret, algorithm))
            doFinal(data)
        }
    }

    private fun rsaAlgorithm(method: String?): String = when (method) {
        METHOD_RSA_SHA256 -> "SHA256withRSA"
        METHOD_RSA_SHA1 -> "SHA1withRSA"
        else -> throw LiveException("Unsupported signature method $method")
    }
}
