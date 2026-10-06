package app.gamenative.api

import app.gamenative.utils.DebugRunParams
import org.json.JSONObject

data class SupportComponent(
    val componentId: String,
    val summary: String?,
    val type: Type,
    val packageFormat: PackageFormat,
    val versionName: String,
    val artifactKey: String?,
    val artifactUrl: String,
    val sha256: String,
    val size: Long,
    val sourceRepo: String?,
    val sourceRef: String?,
    val sourceRun: String?,
    val applyKey: String,
    val applyValue: String,
    val rerun: Boolean,
    val run: DebugRunParams?,
) {
    enum class Type(val id: String, val format: PackageFormat, val key: String) {
        TURNIP("turnip", PackageFormat.ADRENOTOOLS_ZIP, KEY_GRAPHICS_DRIVER_VERSION),
        WRAPPER("wrapper", PackageFormat.ADRENOTOOLS_ZIP, KEY_GRAPHICS_DRIVER_VERSION),
        FEXCORE("fexcore", PackageFormat.CONTENT_PACKAGE, KEY_FEXCORE_VERSION),
        BOX64("box64", PackageFormat.CONTENT_PACKAGE, KEY_BOX64_VERSION),
        DXVK("dxvk", PackageFormat.CONTENT_PACKAGE, KEY_DXVK_VERSION),
        VKD3D("vkd3d", PackageFormat.CONTENT_PACKAGE, KEY_VKD3D_VERSION),
        PROTON("proton", PackageFormat.CONTENT_PACKAGE, KEY_WINE_VERSION),
        ;

        companion object {
            fun from(value: String?): Type? = entries.firstOrNull { it.id == value }
        }
    }

    enum class PackageFormat(val id: String) {
        ADRENOTOOLS_ZIP("adrenotools-zip"),
        CONTENT_PACKAGE("content-package"),
        ;

        companion object {
            fun from(value: String?): PackageFormat? = entries.firstOrNull { it.id == value }
        }
    }

    companion object {
        const val KEY_GRAPHICS_DRIVER_VERSION = "graphicsDriverConfig.version"
        const val KEY_FEXCORE_VERSION = "fexcoreVersion"
        const val KEY_BOX64_VERSION = "box64Version"
        const val KEY_DXVK_VERSION = "dxwrapperConfig.version"
        const val KEY_VKD3D_VERSION = "dxwrapperConfig.vkd3dVersion"
        const val KEY_WINE_VERSION = "wineVersion"

        val APPLY_KEYS = setOf(
            KEY_GRAPHICS_DRIVER_VERSION,
            KEY_FEXCORE_VERSION,
            KEY_BOX64_VERSION,
            KEY_DXVK_VERSION,
            KEY_VKD3D_VERSION,
            KEY_WINE_VERSION,
        )

        const val MAX_SIZE = 2L * 1024 * 1024 * 1024
        private const val MAX_SUMMARY = 300
        private const val MAX_SOURCE = 200

        private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$")

        fun isValidVersion(value: String?): Boolean = value != null && VERSION.matches(value)

        fun parse(json: JSONObject?): SupportComponent? {
            if (json == null) return null
            if (json.optInt("v", 0) != 1) return null
            val componentId = json.cardText("componentId")
            if (!SupportFilesRequest.isValidId(componentId)) return null
            val component = json.optJSONObject("component") ?: return null
            val apply = json.optJSONObject("apply") ?: return null

            val type = Type.from(component.cardText("type")) ?: return null
            val format = PackageFormat.from(component.cardText("packageFormat")) ?: return null
            if (format != type.format) return null

            val versionName = component.cardText("versionName")
            if (!isValidVersion(versionName)) return null
            val applyKey = apply.cardText("key")
            if (applyKey !in APPLY_KEYS || applyKey != type.key) return null
            val applyValue = apply.cardText("value")
            if (applyValue != versionName) return null

            val sha256 = component.cardText("sha256")?.lowercase()
            if (!SupportPatch.isSha256(sha256)) return null
            val size = if (component.has("size") && !component.isNull("size")) component.optLong("size", -1L) else -1L
            if (size !in 1..MAX_SIZE) return null
            val artifactUrl = component.cardText("artifactUrl")?.takeIf { it.startsWith("https://") } ?: return null

            val source = component.optJSONObject("source")
            return SupportComponent(
                componentId = componentId!!,
                summary = cardClean(json.cardText("summary"), MAX_SUMMARY),
                type = type,
                packageFormat = format,
                versionName = versionName!!,
                artifactKey = component.cardText("artifactKey"),
                artifactUrl = artifactUrl,
                sha256 = sha256!!,
                size = size,
                sourceRepo = cardClean(source?.cardText("repo"), MAX_SOURCE),
                sourceRef = cardClean(source?.cardText("ref"), MAX_SOURCE),
                sourceRun = cardClean(source?.cardText("run"), MAX_SOURCE)?.takeIf { it.startsWith("https://") },
                applyKey = applyKey!!,
                applyValue = applyValue!!,
                rerun = json.optBoolean("rerun", false),
                run = DebugRunParams.fromJson(json.optJSONObject("run")),
            )
        }
    }
}
