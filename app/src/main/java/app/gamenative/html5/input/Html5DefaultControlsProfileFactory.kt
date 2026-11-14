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

// like Wine, each container references a UNIQUE profile in the global pool, so a remap (and the
// overlay layout, stored in the same JSON) never leaks across containers. caller persists
// profile.id into container.controlsProfileId on the bootstrap path.
object Html5DefaultControlsProfileFactory {
    // NOT a uniqueness key; only the first html5 profile gets this name.
    const val HTML5_DEFAULT_PROFILE_NAME = "HTML5 Default"

    // packSynthMap only applies when minting a fresh profile. existing profiles are NEVER
    // auto-rewritten: user customizations win. to re-default, delete + recreate.
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
            // older installs may have containers sharing one profile id; fork lazily on launch.
            val migrated = forkOnCollision(context, container, existing, manager)
            return migrated ?: existing.also { it.loadControllers() }
        }
        val profile = manager.createProfile(profileNameFor(manager, container))
        populateWithGamepadBindings(profile, packSynthMap)
        profile.save()
        // re-read so first-session state matches the existing-profile branch, which loads from disk.
        profile.loadControllers()
        return profile
    }

    // null = no collision. on collision THIS container takes a clone; the sibling keeps the
    // original, so there is no bulk migration pass.
    private fun forkOnCollision(
        context: Context,
        container: WebViewContainer,
        existingProfile: ControlsProfile,
        manager: InputControlsManager,
    ): ControlsProfile? {
        val rootDir = File(DownloadService.baseExternalAppDirPath, "html5-containers")
        if (!rootDir.exists()) return null
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
            // orphan clone is harmless; next launch retries.
            return null
        }
        clone.loadControllers()
        Timber.tag("Html5DefaultControlsProfileFactory").i(
            "html5 profile migration: container %s detected shared profileId=%d (siblings=%s), cloned to fresh profileId=%d",
            container.id, container.controlsProfileId, collidingSiblings, newId,
        )
        return clone
    }

    // name prefixes are matched by Html5ProfileFilter -- keep in sync.
    private fun profileNameFor(manager: InputControlsManager, container: WebViewContainer): String {
        val taken = manager.getProfiles(false).map { it.name }.toSet()
        if (HTML5_DEFAULT_PROFILE_NAME !in taken) return HTML5_DEFAULT_PROFILE_NAME
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
        // Y / RZ entries look FLIPPED on purpose: getKeyCodeForAxis flips their sign (Android Y
        // points down), so AXIS_Y_NEGATIVE fires on stick-DOWN. bind to the perceived direction so
        // synth maps like LEFT_THUMB_UP -> KEY_W fire on stick-up. analog values are unaffected.
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
            val effective = packSynthMap?.get(defaultBinding) ?: defaultBinding
            val b = ExternalControllerBinding()
            b.setKeyCode(keyCode)
            b.setBinding(effective)
            controller.addControllerBinding(b)
        }
    }
}
