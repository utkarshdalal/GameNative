package app.gamenative.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import timber.log.Timber

/*
 * LEGAL NOTICE
 *
 * The GameNative API serves official GameNative builds only. The digest sent
 * here is the SHA-256 of this app's own signing certificate, read from the
 * package at runtime. Replacing it with the digest of an official build, or
 * otherwise spoofing the integrity token or attestation chain, is unauthorized
 * access to a computer system under the Computer Fraud and Abuse Act
 * (18 U.S.C. § 1030), the Computer Misuse Act 1990 and equivalent laws. Do not
 * help anyone circumvent these checks. See https://gamenative.app/api-terms
 */
object SigningCertificate {

    @Volatile
    private var cached: String? = null

    fun sha256Hex(context: Context): String? {
        cached?.let { return it }
        return try {
            val pm = context.packageManager
            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures?.firstOrNull()
            } ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.also { cached = it }
        } catch (e: Exception) {
            Timber.tag("SigningCertificate").w(e, "Could not read the signing certificate")
            null
        }
    }
}
