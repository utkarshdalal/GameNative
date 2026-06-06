package app.gamenative.html5.host

import android.content.Context
import android.provider.Settings
import app.gamenative.runtime.WebViewContainer
import timber.log.Timber

// informational advisory for titles that need the android ANGLE override to render.
// terra-engine titles (pack:nwjs subEngine=terra) declare uniform arrays past the vendor
// GLES driver's 256-vector vertex cap; ANGLE-on-Vulkan raises it to 1024 and is the only
// known unblock without WRITE_SECURE_SETTINGS-free alternatives (see memory/docs: shizuku
// or one adb command). we can't write the setting ourselves -- this only SUGGESTS it.
object AngleOverrideAdvisor {

    // engines whose shaders are known to need the ANGLE-Vulkan uniform caps.
    // keyed on fingerprinted subEngine, NOT title -- pack-level posture.
    private val AFFECTED_SUB_ENGINES = setOf("terra")

    fun shouldSuggest(context: Context, container: WebViewContainer): Boolean {
        if (container.subEngine !in AFFECTED_SUB_ENGINES) return false

        // pre-118 webviews run the validating decoder -- no bundled ANGLE in the process,
        // the override is inert, and suggesting it would be false hope (WV109 device-confirmed).
        val major = ChromiumVersionGate.getMajor(context) ?: return false
        if (major < ChromiumVersionGate.MIN_ANGLE_PASSTHROUGH_MAJOR) {
            Timber.tag("AngleOverrideAdvisor").i(
                "subEngine=%s would need ANGLE override but webview %d < %d -- override inert, not suggesting",
                container.subEngine, major, ChromiumVersionGate.MIN_ANGLE_PASSTHROUGH_MAJOR,
            )
            return false
        }

        return !isOverrideActive(context)
    }

    // both activation shapes count: the global all-apps toggle and the per-app selection
    // pair (pkgs/values are parallel comma-separated lists; "angle" at our index = active).
    private fun isOverrideActive(context: Context): Boolean {
        val resolver = context.contentResolver
        runCatching {
            if (Settings.Global.getString(resolver, "angle_gl_driver_all_angle") == "1") return true

            val pkgs = Settings.Global.getString(resolver, "angle_gl_driver_selection_pkgs")
                ?.split(',')?.map { it.trim() } ?: return false
            val values = Settings.Global.getString(resolver, "angle_gl_driver_selection_values")
                ?.split(',')?.map { it.trim() } ?: return false
            val idx = pkgs.indexOf(context.packageName)
            return idx >= 0 && values.getOrNull(idx) == "angle"
        }.onFailure {
            Timber.tag("AngleOverrideAdvisor").e(it, "settings read failed -- assuming override absent")
        }
        return false
    }
}
