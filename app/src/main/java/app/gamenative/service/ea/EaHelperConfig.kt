package app.gamenative.service.ea

import android.content.Context
import java.io.File
import java.util.Properties

/** Values the EA helper archive supplies; they are not part of the app. */
object EaHelperConfig {
    private const val CLIENT_SECRET = "ea.client.secret"
    private const val LICENSE_KEY = "ea.license.key"
    @Volatile private var cached: Properties? = null

    private fun properties(filesDir: File): Properties =
        cached ?: EaHelperArchive.loadConfig(filesDir).also { cached = it }

    fun clientSecret(context: Context): String = value(context.filesDir, CLIENT_SECRET)

    /** AES-128 key for the licence blobs, 16 bytes as hex. */
    fun licenseKey(context: Context): ByteArray = licenseKey(context.filesDir)

    internal fun licenseKey(filesDir: File): ByteArray {
        val key = EaCrypto.unhex(value(filesDir, LICENSE_KEY))
        check(key.size == 16) { "EA helper configuration has a malformed licence key" }
        return key
    }

    internal fun value(filesDir: File, name: String): String =
        properties(filesDir).getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("EA helper configuration is missing $name")

    internal fun reset() { cached = null }
}
