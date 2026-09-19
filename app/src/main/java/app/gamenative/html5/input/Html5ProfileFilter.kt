package app.gamenative.html5.input

import com.winlator.inputcontrols.ControlsProfile

// html5 ControlsProfiles are owned by ONE container and must not show up in cross-container
// pickers (e.g. Wine "Copy From"). matched by name to avoid a new field on upstream ControlsProfile;
// names MUST stay in sync with Html5DefaultControlsProfileFactory.
object Html5ProfileFilter {
    private const val HTML5_DEFAULT_NAME = "HTML5 Default"
    private const val HTML5_PREFIX = "HTML5: "

    fun isHtml5PerContainerProfile(profile: ControlsProfile): Boolean {
        val name = profile.name ?: return false
        return name == HTML5_DEFAULT_NAME || name.startsWith(HTML5_PREFIX)
    }

    fun excludeHtml5(profiles: List<ControlsProfile>): List<ControlsProfile> =
        profiles.filterNot { isHtml5PerContainerProfile(it) }
}
