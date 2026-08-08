package app.gamenative.html5.input

import android.content.Context
import android.view.KeyEvent
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.DownloadService
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.InputControlsManager
import java.io.File
import timber.log.Timber

// each html5 container gets its OWN profile (a real on-disk preset managed by
// InputControlsManager, Wine parity) in the global pool. the prior global-by-name lookup made all
// html5 containers share one profile -- a remap in container A leaked into container B.
// Wine's model: profiles are stored globally for sharing/templating, but each container
// references a UNIQUE profile by id via container.controlsProfileId.

// Resolution:
// 1. container.controlsProfileId > 0L AND profile exists →
// sub-check: scan sibling WebViewContainer.json files for the SAME id. if any other
// container shares it (legacy from the global-by-name bug), fork-on-collision:
// duplicate the profile, persist the new id back to THIS container's JSON, return the
// clone. the OTHER container keeps the original profile until ITS launch hits the same
// check (lazy migration; no bulk pass).
// 2. otherwise → mint a fresh profile (++maxProfileId), populate, save
// Caller persists profile.id back into container.controlsProfileId on the bootstrap path.

// elements + controllers both live in the same ControlsProfile JSON, so per-container
// profiles also give per-container overlay layouts (single fix covers both dimensions).
object Html5DefaultControlsProfileFactory {
    // legacy name kept for migration breadcrumb. NOT used as a uniqueness key anymore --
    // first per-container profile inherits this name; subsequent containers get unique names.
    const val HTML5_DEFAULT_PROFILE_NAME = "HTML5 Default"

    // pack's gamepadKeySynthesisMap is consulted at fresh-profile populate time only.
    // when a pack declares GAMEPAD_*→KEY_* entries (e.g. Start→KEY_X for RMMV's stock-only
    // titles whose gamepadMapper has no Start binding), populateWithGamepadBindings binds
    // those physical keycodes directly to KEY_*. an empty/null map = pure GAMEPAD_* defaults.
    
    // existing profiles are NEVER auto-rewritten. user customizations win, and titles like
    // RMMZ + Mano_InputConfig keep their working gamepad path instead of getting yanked
    // onto keyboard synth. callers wanting to re-default a profile delete + recreate.
    fun getOrCreate(
        context: Context,
        container: WebViewContainer,
        packSynthMap: Map<Binding, Binding>? = null,
    ): ControlsProfile {
        val manager = InputControlsManager(context)
        val existing = if (container.controlsProfileId > 0L) {
            manager.getProfiles(false).firstOrNull { it.id.toLong() == container.controlsProfileId }
        } else {
            null
        }
        if (existing != null) {
            // lazy migration for shared profileIds:
            // if any sibling container references the same profileId, fork a unique clone
            // for THIS container and persist the new id. covers the case where two containers
            // were minted while the global-by-name lookup was active and ended up sharing one
            // profile id on disk.
            val migrated = forkOnCollision(context, container, existing, manager)
            return migrated ?: existing.also { it.loadControllers() }
        }
        // mint a new profile via Java-side ++maxProfileId. UNIQUE per container.
        val profile = manager.createProfile(profileNameFor(manager, container))
        populateWithGamepadBindings(profile, packSynthMap)
        profile.save()
        // re-read from disk so getController(deviceId) lookups in the first session see the
        // same wildcard "*" controller a 2nd-launch path would. populateWithGamepadBindings
        // sets controllersLoaded=true via addController, so without this call the in-memory
        // list is correct -- BUT we want symmetric state with the existing-profile branch
        // which always loads from disk. save() above just wrote the bindings, so the re-read
        // is non-destructive.
        profile.loadControllers()
        return profile
    }

    // returns the migrated (cloned) profile when a sibling collision is detected and forked,
    // null when no collision (caller continues with the original profile). package-private
    // for unit tests (forkOnCollisionForTest in the test file).
    
    // collision = ANY other WebViewContainer.json in html5-containers/ has the same
    // controlsProfileId AND a different container.id (excludes this container's own JSON).
    // when found: duplicate via InputControlsManager.duplicateProfile (preserves bindings),
    // persist the new id back to THIS container's JSON. THIS container then owns the clone;
    // the colliding sibling continues to use the original until ITS next launch.
    private fun forkOnCollision(
        context: Context,
        container: WebViewContainer,
        existingProfile: ControlsProfile,
        manager: InputControlsManager,
    ): ControlsProfile? {
        val rootDir = File(DownloadService.baseExternalAppDirPath, "html5-containers")
        if (!rootDir.exists()) return null
        // resolve THIS container's slug + scan siblings in one pass -- both keyed on dir name.
        var thisSlug: String? = null
        val collidingSiblings = mutableListOf<String>()
        rootDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val sibling = WebViewContainer.load(dir.name) ?: return@forEach
            if (sibling.id == container.id) {
                thisSlug = dir.name
            } else if (sibling.controlsProfileId == container.controlsProfileId) {
                collidingSiblings.add(sibling.id)
            }
        }
        if (collidingSiblings.isEmpty()) return null
        val slug = thisSlug ?: run {
            Timber.tag("Html5DefaultControlsProfileFactory")
                .w("collision detected for %s but no slug match — cannot persist fork", container.id)
            return null
        }
        existingProfile.loadControllers()
        val clone = manager.duplicateProfile(existingProfile)
        val newId = clone.id.toLong()
        runCatching {
            WebViewContainer.save(slug, container.copy(controlsProfileId = newId))
        }.onFailure {
            Timber.tag("Html5DefaultControlsProfileFactory")
                .w(it, "fork-on-collision: failed to persist new profileId=%d for slug=%s", newId, slug)
            // leave clone in the manager pool -- orphan profile is harmless; next launch retries.
            return null
        }
        clone.loadControllers()
        Timber.tag("Html5DefaultControlsProfileFactory").i(
            "html5 profile migration: container %s detected shared profileId=%d (siblings=%s), cloned to fresh profileId=%d",
            container.id, container.controlsProfileId, collidingSiblings, newId,
        )
        return clone
    }

    // first per-container profile keeps the canonical "HTML5 Default" name (back-compat
    // with the global-by-name data shape -- that profile already exists for users who hit
    // the bug). subsequent containers get unique-suffixed names so the picker UI can
    // distinguish them. uniqueness check across the existing pool, not just html5 names.
    private fun profileNameFor(manager: InputControlsManager, container: WebViewContainer): String {
        val taken = manager.getProfiles(false).map { it.name }.toSet()
        if (HTML5_DEFAULT_PROFILE_NAME !in taken) return HTML5_DEFAULT_PROFILE_NAME
        // try slug-based name first ("HTML5: <id>"); fall back to numeric suffix on collision.
        val base = "HTML5: ${container.id}"
        if (base !in taken) return base
        var i = 2
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    private fun populateWithGamepadBindings(
        profile: ControlsProfile,
        packSynthMap: Map<Binding, Binding>? = null,
    ) {
        val controller = profile.addController("*")
        // standard gamepad button keycodes → W3C gamepad indices via GAMEPAD_BUTTON_*
        val keycodeMap: List<Pair<Int, Binding>> = listOf(
            KeyEvent.KEYCODE_BUTTON_A to Binding.GAMEPAD_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B to Binding.GAMEPAD_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X to Binding.GAMEPAD_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y to Binding.GAMEPAD_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1 to Binding.GAMEPAD_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1 to Binding.GAMEPAD_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2 to Binding.GAMEPAD_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2 to Binding.GAMEPAD_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL to Binding.GAMEPAD_BUTTON_L3,
            KeyEvent.KEYCODE_BUTTON_THUMBR to Binding.GAMEPAD_BUTTON_R3,
            KeyEvent.KEYCODE_BUTTON_START to Binding.GAMEPAD_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT to Binding.GAMEPAD_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP to Binding.GAMEPAD_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to Binding.GAMEPAD_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to Binding.GAMEPAD_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to Binding.GAMEPAD_DPAD_RIGHT,
        )
        // analog sticks: virtual axis keycodes from ExternalControllerBinding.AXIS_*_*.
        // Y / RZ ENTRIES ARE FLIPPED relative to X / Z: ExternalControllerBinding.getKeyCodeForAxis
        // flips sign on Y and RZ (sign>0 → AXIS_Y_NEGATIVE) -- Android's Y axis points down so
        // the AXIS_Y_NEGATIVE keycode is fired when Android value is POSITIVE (= stick pushed
        // DOWN). bind direction-named binding to the user-perceived direction the keycode
        // represents (not to its constant name) so synth maps GAMEPAD_LEFT_THUMB_UP → KEY_W
        // actually fire on stick-up. only matters for the synth (KEY_*) dispatch path --
        // analog values go to GamepadState.thumbLY/RY independently via processJoystickInput.
        val axisMap: List<Pair<Int, Binding>> = listOf(
            ExternalControllerBinding.AXIS_X_NEGATIVE.toInt() to Binding.GAMEPAD_LEFT_THUMB_LEFT,
            ExternalControllerBinding.AXIS_X_POSITIVE.toInt() to Binding.GAMEPAD_LEFT_THUMB_RIGHT,
            ExternalControllerBinding.AXIS_Y_NEGATIVE.toInt() to Binding.GAMEPAD_LEFT_THUMB_DOWN,
            ExternalControllerBinding.AXIS_Y_POSITIVE.toInt() to Binding.GAMEPAD_LEFT_THUMB_UP,
            ExternalControllerBinding.AXIS_Z_NEGATIVE.toInt() to Binding.GAMEPAD_RIGHT_THUMB_LEFT,
            ExternalControllerBinding.AXIS_Z_POSITIVE.toInt() to Binding.GAMEPAD_RIGHT_THUMB_RIGHT,
            ExternalControllerBinding.AXIS_RZ_NEGATIVE.toInt() to Binding.GAMEPAD_RIGHT_THUMB_DOWN,
            ExternalControllerBinding.AXIS_RZ_POSITIVE.toInt() to Binding.GAMEPAD_RIGHT_THUMB_UP,
        )
        (keycodeMap + axisMap).forEach { (keyCode, defaultBinding) ->
            // pack synth map overrides only the buttons whose stock gamepadMapper has no
            // entry (Start/Select for RMMV) so they hit a useful keyboard action by default.
            // everything else stays GAMEPAD_* and dispatches via the virtual gamepad bridge.
            val effective = packSynthMap?.get(defaultBinding) ?: defaultBinding
            val b = ExternalControllerBinding()
            b.setKeyCode(keyCode)
            b.setBinding(effective)
            controller.addControllerBinding(b)
        }
    }
}
