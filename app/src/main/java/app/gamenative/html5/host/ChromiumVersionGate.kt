package app.gamenative.html5.host

import android.content.Context
import androidx.webkit.WebViewCompat
import timber.log.Timber

// boot gate -- only blocks HTML5 when Chromium is TOO OLD to run WebView reliably.
// per-container isolation comes from the synthetic origin (http://<safeId>.localhost:<port>)
// so we no longer require the multi-profile API (which is firmware-locked-out on some devices).
object ChromiumVersionGate {
    const val MIN_MAJOR: Int = 100

    // errata: an earlier note said "Chromium 108+", but android webview OPFS+SAH
    // shipped in chromium 109, NOT 108. chromium 108 synchronized SAH methods on desktop only.
    // verified via mdn/browser-compat-data + chromestatus 5079634203377664. literal=109.
    const val MIN_OPFS_SAH_MAJOR: Int = 109

    // the android ANGLE override (settings put global angle_gl_driver_all_angle 1) only
    // affects webview when chromium routes GL through its bundled ANGLE -- i.e. the
    // passthrough command decoder. kDefaultPassthroughCommandDecoder flipped to
    // FEATURE_ENABLED_BY_DEFAULT on IS_ANDROID in chromium 118 (ui/gl/gl_features.cc,
    // bisected across branch-heads 5414/109=off, 5938/117=off, 5993/118=ON). below 118 the
    // validating decoder talks straight to the vendor GLES driver and the override is inert
    // (device-confirmed: WV109 ignores it, WV124 flips to ANGLE-Vulkan).
    const val MIN_ANGLE_PASSTHROUGH_MAJOR: Int = 118

    // pure fn -- versionName format "108.0.5359.79" -> 108.
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

    // boot snapshot of the device's WebView -- the single biggest html5 compat variable.
    // provider (packageName) matters as much as version: OEM-locked providers are the ones
    // that can't be updated. captured as PostHog person-properties (see PluviaApp $set) so the
    // install-base version/provider spread is a single histogram -- turns "old WebView" from
    // an open-ended worry into a number that says whether it's a real population or a tail.
    data class WebViewInfo(val packageName: String?, val versionName: String?, val major: Int?)

    fun getWebViewInfo(context: Context): WebViewInfo =
        runCatching {
            val pkg = WebViewCompat.getCurrentWebViewPackage(context)
            WebViewInfo(pkg?.packageName, pkg?.versionName, parseMajor(pkg?.versionName))
        }.getOrElse { WebViewInfo(null, null, null) }

    // narrower gate than isSupported(). pack:c3+workerShim titles depend on
    // OPFS createSyncAccessHandle (android webview chromium 109+). callers fall back to wine
    // when this returns false; the boot gate above stays at MIN_MAJOR=100 unchanged.
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
