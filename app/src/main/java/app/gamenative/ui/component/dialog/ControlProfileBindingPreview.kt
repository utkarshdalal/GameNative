package app.gamenative.ui.component.dialog

import app.gamenative.inputcontrols.ControlProfileService
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ExternalControllerBinding
import org.json.JSONObject

internal data class ProfileBindingRow(val label: String, val binding: BindingCombo)

/** Mirrors the wildcard mapping edited by the physical-controller screen. */
internal fun physicalProfileBindingRows(profile: JSONObject): List<ProfileBindingRow> {
    val bindings = ControlProfileService.activeControllerJson(profile)
        ?.optJSONArray("controllerBindings") ?: return emptyList()
    return buildList {
        for (index in 0 until bindings.length()) {
            val item = bindings.optJSONObject(index) ?: continue
            if (!item.has("keyCode")) continue
            val source = ExternalControllerBinding().apply { setKeyCode(item.optInt("keyCode")) }
            add(ProfileBindingRow(source.toString().removePrefix("BUTTON "), BindingCombo.fromJsonValue(item)))
        }
    }
}

/** Only the first/default radial menu is used by the runtime and editor. */
internal fun radialProfileBindingRows(profile: JSONObject): List<ProfileBindingRow> {
    val slots = ControlProfileService.activeRadialMenuJson(profile)
        ?.optJSONArray("slots") ?: return emptyList()
    return buildList {
        for (index in 0 until slots.length()) {
            val slot = slots.optJSONObject(index) ?: continue
            add(ProfileBindingRow(slot.optString("label"), BindingCombo.fromJsonValue(slot)))
        }
    }
}
