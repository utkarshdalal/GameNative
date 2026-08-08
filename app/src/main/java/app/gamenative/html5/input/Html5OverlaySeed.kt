package app.gamenative.html5.input

import android.content.Context
import com.winlator.inputcontrols.ControlsProfile
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// one-shot per-pack overlay seed. on first launch
// (container.controlsProfileId == 0L bootstrap), copy a pack's default touch overlay
// elements into the freshly minted ControlsProfile's on-disk JSON. ICV's normal
// loadElements path picks them up -- no in-memory addElement / ICV dimension coupling.

// idempotency: only writes when profile JSON's "elements" array is empty. user edits
// live in the same array → seed never overwrites them.
object Html5OverlaySeed {

    /**
     * Seeds [profile]'s on-disk JSON with the asset overlay's `elements` array if (a) the
     * asset exists and (b) the profile currently has no elements on disk. Returns true if
     * a seed write happened, false otherwise.
     */
    fun seedIfEmpty(context: Context, profile: ControlsProfile, overlayAssetName: String): Boolean {
        val profileFile = ControlsProfile.getProfileFile(context, profile.id)
        if (!profileFile.isFile) {
            Timber.tag("Html5OverlaySeed").w("profile file missing for id=%d", profile.id)
            return false
        }
        val profileJson = runCatching { JSONObject(profileFile.readText()) }
            .onFailure { Timber.tag("Html5OverlaySeed").w(it, "profile JSON parse failed") }
            .getOrNull() ?: return false

        // skip if user already has elements (preserves edits)
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
