package app.gamenative.html5.input

import android.content.Context
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// seeds the on-screen layout the wine path falls back to (XServerScreen: profile 0, else the third, else the
// first) into the profile's on-disk JSON so ICV's normal loadElements picks it up, with the pack's gamepad->key
// synthesis applied to the copied bindings like the physical controller gets it. only writes when "elements"
// is empty, so user edits are never overwritten.
object Html5OverlaySeed {

    fun seedIfEmpty(
        context: Context,
        profile: ControlsProfile,
        manager: InputControlsManager,
        packSynthMap: Map<Binding, Binding>,
    ): Boolean {
        val profileFile = ControlsProfile.getProfileFile(context, profile.id)
        if (!profileFile.isFile) {
            Timber.tag("Html5OverlaySeed").w("profile file missing for id=%d", profile.id)
            return false
        }
        val profileJson = runCatching { JSONObject(profileFile.readText()) }
            .onFailure { Timber.tag("Html5OverlaySeed").w(it, "profile JSON parse failed") }
            .getOrNull() ?: return false

        val existing = profileJson.optJSONArray("elements")
        if (existing != null && existing.length() > 0) return false

        val profiles = manager.getProfiles(false)
        val source = manager.getProfile(0) ?: profiles.getOrNull(2) ?: profiles.firstOrNull() ?: return false
        if (source.id == profile.id) return false
        val sourceJson = runCatching { JSONObject(ControlsProfile.getProfileFile(context, source.id).readText()) }
            .onFailure { Timber.tag("Html5OverlaySeed").w(it, "default profile id=%d unreadable", source.id) }
            .getOrNull() ?: return false

        val elements = layoutFor(sourceJson.optJSONArray("elements") ?: JSONArray(), packSynthMap)
        if (elements.length() == 0) return false

        profileJson.put("elements", elements)
        return runCatching {
            profileFile.writeText(profileJson.toString())
            Timber.tag("Html5OverlaySeed").i(
                "seeded %d elements from profile id=%d into profile id=%d",
                elements.length(), source.id, profile.id,
            )
            true
        }.onFailure {
            Timber.tag("Html5OverlaySeed").e(it, "failed to write seeded profile")
        }.getOrDefault(false)
    }

    private val SUPPORTED_TYPES = setOf("BUTTON", "D_PAD", "STICK")
    private val MOUSE_BUTTONS = setOf(Binding.MOUSE_LEFT_BUTTON, Binding.MOUSE_RIGHT_BUTTON, Binding.MOUSE_MIDDLE_BUTTON)

    // what Html5InputController.dispatchBinding can deliver without an xServer.
    internal fun isSupported(binding: Binding): Boolean =
        binding == Binding.NONE ||
            binding == Binding.OPEN_NAVIGATION_MENU ||
            binding.isGamepad ||
            binding.name.startsWith("MOUSE_MOVE_") ||
            binding in MOUSE_BUTTONS ||
            Html5KeyMapping.specFor(binding) != null

    // elements of another type, or with a binding the html5 input path can't deliver, are left out.
    internal fun layoutFor(sourceElements: JSONArray, packSynthMap: Map<Binding, Binding>): JSONArray {
        val out = JSONArray()
        for (i in 0 until sourceElements.length()) {
            val element = JSONObject(sourceElements.optJSONObject(i)?.toString() ?: continue)
            val bindings = element.optJSONArray("bindings") ?: JSONArray()
            val resolved = (0 until bindings.length()).map { j ->
                val b = runCatching { Binding.valueOf(bindings.optString(j)) }.getOrNull()
                b?.let { packSynthMap[it] ?: it }
            }
            if (element.optString("type") !in SUPPORTED_TYPES || resolved.any { it == null || !isSupported(it) }) {
                Timber.tag("Html5OverlaySeed").w("skipping element type=%s bindings=%s", element.optString("type"), bindings)
                continue
            }
            element.put("bindings", JSONArray(resolved.map { it!!.name }))
            out.put(element)
        }
        return out
    }
}
