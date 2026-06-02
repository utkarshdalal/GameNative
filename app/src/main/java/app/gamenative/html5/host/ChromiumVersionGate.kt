package app.gamenative.html5.host

import android.content.Context
import androidx.webkit.WebViewCompat
import timber.log.Timber

// boot gate: blocks HTML5 only when chromium is too old. per-container isolation comes from the synthetic
// origin (http://<safeId>.localhost:<port>), so the multi-profile API (firmware-locked on some devices) isn't needed.
object ChromiumVersionGate {
    const val MIN_MAJOR: Int = 100

    // android webview OPFS sync access handles shipped in 109, NOT 108 (108 was desktop only).
    const val MIN_OPFS_SAH_MAJOR: Int = 109

    // the android ANGLE override only affects webview when GL goes through chromium's bundled ANGLE, i.e. the
    // passthrough command decoder, default-on for android since 118 (ui/gl/gl_features.cc). below 118 the
    // validating decoder talks straight to the vendor GLES driver and the override is inert.
    const val MIN_ANGLE_PASSTHROUGH_MAJOR: Int = 118

    // "108.0.5359.79" -> 108.
    fun parseMajor(versionName: String?): Int? {
        if (versionName.isNullOrBlank()) return null
        return versionName.substringBefore('.').toIntOrNull()
    }

    fun getMajor(context: Context): Int? =
        runCatching {
            val pkg = WebViewCompat.getCurrentWebViewPackage(context)
            if (pkg == null) {
                Timber.tag("ChromiumVersionGate").w("WebView package null — treating as unsupported")
                return@runCatching null
            }
            parseMajor(pkg.versionName)
        }.onFailure {
            Timber.tag("ChromiumVersionGate").e(it, "gate lookup failed")
        }.getOrNull()

    // reported as PostHog person-properties (PluviaApp $set). provider matters as much as version:
    // OEM-locked providers can't be updated.
    data class WebViewInfo(val packageName: String?, val versionName: String?, val major: Int?)

    fun getWebViewInfo(context: Context): WebViewInfo =
        runCatching {
            val pkg = WebViewCompat.getCurrentWebViewPackage(context)
            WebViewInfo(pkg?.packageName, pkg?.versionName, parseMajor(pkg?.versionName))
        }.getOrElse { WebViewInfo(null, null, null) }

    // narrower than isSupported(): pack:c3 worker-shim titles need OPFS createSyncAccessHandle.
    // callers fall back to wine on false.
    fun isOpfsSahSupported(context: Context): Boolean {
        val major = getMajor(context) ?: return false
        val ok = major >= MIN_OPFS_SAH_MAJOR
        if (!ok) {
            Timber.tag("ChromiumVersionGate").w(
                "OPFS-SAH gate fail major=%d (below MIN_OPFS_SAH_MAJOR=%d) — caller will fall back to Wine",
                major, MIN_OPFS_SAH_MAJOR,
            )
        }
        return ok
    }

    fun isSupported(context: Context): Boolean {
        val major = getMajor(context)
        val majorOk = (major ?: 0) >= MIN_MAJOR
        if (!majorOk) {
            Timber.tag("ChromiumVersionGate").w(
                "gate fail major=%s (below MIN_MAJOR=%d)",
                major?.toString() ?: "unknown",
                MIN_MAJOR,
            )
        }
        return majorOk
    }
}
