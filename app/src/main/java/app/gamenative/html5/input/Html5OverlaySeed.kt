package app.gamenative.html5.input

import android.content.Context
import com.winlator.inputcontrols.ControlsProfile
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// seeds a pack's default touch overlay into the profile's on-disk JSON so ICV's normal
// loadElements picks it up. only writes when "elements" is empty, so user edits are never overwritten.
object Html5OverlaySeed {

    fun seedIfEmpty(context: Context, profile: ControlsProfile, overlayAssetName: String): Boolean {
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

        val overlayJson = runCatching {
            context.assets.open("html5/packs/$overlayAssetName.json").bufferedReader()
                .use { it.readText() }
        }.onFailure {
            Timber.tag("Html5OverlaySeed").w(it, "overlay asset missing: %s", overlayAssetName)
        }.getOrNull() ?: return false

        val overlayElements = runCatching {
            JSONObject(overlayJson).optJSONArray("elements") ?: JSONArray()
        }.onFailure {
            Timber.tag("Html5OverlaySeed").w(it, "overlay JSON parse failed: %s", overlayAssetName)
        }.getOrNull() ?: return false

        if (overlayElements.length() == 0) return false

        profileJson.put("elements", overlayElements)
        return runCatching {
            profileFile.writeText(profileJson.toString())
            Timber.tag("Html5OverlaySeed").i(
                "seeded %d elements from %s into profile id=%d",
                overlayElements.length(), overlayAssetName, profile.id,
            )
            true
        }.onFailure {
            Timber.tag("Html5OverlaySeed").e(it, "failed to write seeded profile")
        }.getOrDefault(false)
    }
}
