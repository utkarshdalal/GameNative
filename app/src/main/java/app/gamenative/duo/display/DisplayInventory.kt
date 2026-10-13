package app.gamenative.duo.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject

/** One display as Android reports it. Used to work out how a dual-screen device is laid out. */
data class DisplaySnapshot(
    val id: Int,
    val name: String,
    val isDefault: Boolean,
    val inPresentationCategory: Boolean,
    val state: String,
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Float,
    val supportedRefreshRatesHz: List<Float>,
    val densityDpi: Int,
    val rotation: Int,
    val flags: List<String>,
) {
    /** The fields worth logging again when they change at runtime (screen turned off, resolution switch). */
    fun changeKey(): String = "$state ${widthPx}x$heightPx ${"%.1f".format(refreshRateHz)} $rotation"

    fun summary(): String = buildString {
        append("#$id \"$name\" ${widthPx}x$heightPx @ ${"%.1f".format(refreshRateHz)}Hz, ${densityDpi}dpi, state=$state")
        if (isDefault) append(", default")
        if (inPresentationCategory) append(", presentation")
        if (flags.isNotEmpty()) append(", flags=${flags.joinToString("|")}")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("isDefault", isDefault)
        put("inPresentationCategory", inPresentationCategory)
        put("state", state)
        put("widthPx", widthPx)
        put("heightPx", heightPx)
        put("refreshRateHz", refreshRateHz.toDouble())
        put("supportedRefreshRatesHz", JSONArray(supportedRefreshRatesHz.map { it.toDouble() }))
        put("densityDpi", densityDpi)
        put("rotation", rotation)
        put("flags", JSONArray(flags))
    }
}

object DisplayInventory {
    fun snapshot(context: Context): List<DisplaySnapshot> {
        val displayManager = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        val presentationIds = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { it.displayId }
            .toSet()
        return displayManager.displays.map { it.toSnapshot(context, it.displayId in presentationIds) }
    }

    fun toJson(displays: List<DisplaySnapshot>, reason: String): JSONObject = JSONObject().apply {
        put("reason", reason)
        put("timestampMs", System.currentTimeMillis())
        put(
            "device",
            JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("androidRelease", Build.VERSION.RELEASE)
                put("sdkInt", Build.VERSION.SDK_INT)
            },
        )
        put("displays", JSONArray(displays.map { it.toJson() }))
    }

    private fun Display.toSnapshot(context: Context, inPresentationCategory: Boolean): DisplaySnapshot {
        val currentMode = this.mode
        val densityDpi = runCatching {
            context.createDisplayContext(this).resources.displayMetrics.densityDpi
        }.getOrDefault(0)
        return DisplaySnapshot(
            id = displayId,
            name = name,
            isDefault = displayId == Display.DEFAULT_DISPLAY,
            inPresentationCategory = inPresentationCategory,
            state = stateName(state),
            widthPx = currentMode.physicalWidth,
            heightPx = currentMode.physicalHeight,
            refreshRateHz = refreshRate,
            supportedRefreshRatesHz = supportedModes.map { it.refreshRate }.distinct().sorted(),
            densityDpi = densityDpi,
            rotation = rotation,
            flags = flagNames(flags),
        )
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "UNKNOWN($state)"
    }

    private fun flagNames(flags: Int): List<String> = buildList {
        if (flags and Display.FLAG_PRESENTATION != 0) add("PRESENTATION")
        if (flags and Display.FLAG_PRIVATE != 0) add("PRIVATE")
        if (flags and Display.FLAG_SECURE != 0) add("SECURE")
        if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) add("PROTECTED_BUFFERS")
        if (flags and Display.FLAG_ROUND != 0) add("ROUND")
    }
}
