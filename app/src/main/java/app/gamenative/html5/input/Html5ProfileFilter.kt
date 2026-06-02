package app.gamenative.html5.input

import com.winlator.inputcontrols.ControlsProfile

// per-container html5 ControlsProfiles are owned by ONE container --
// they bind that container's overlay layout + physical-controller remap. they are NOT
// templates and should not appear in cross-container pickers (Wine "Copy From" dropdown,
// any future global profile list).

// users edit per-container html5 profiles via the html5 container's QuickMenu →
// "Edit Physical Controller" UI which routes through activeControlsProfile directly
// (WebViewScreen.PhysicalControllerDialog), NOT through the global picker.

// matching strategy: profile NAME prefix. Html5DefaultControlsProfileFactory mints names
// "HTML5 Default" (first per-container profile) + "HTML5: <containerId>" (rest). this is
// the cleanest discriminator without adding a new field to ControlsProfile (Java upstream).
// the strings are duplicated from Html5DefaultControlsProfileFactory by intent -- name-based
// filtering is the matching API; a refactor that changes the names there must also update
// this filter's HTML5_DEFAULT_NAME / HTML5_PREFIX constants.

object Html5ProfileFilter {
    private const val HTML5_DEFAULT_NAME = "HTML5 Default"
    private const val HTML5_PREFIX = "HTML5: "

    fun isHtml5PerContainerProfile(profile: ControlsProfile): Boolean {
        val name = profile.name ?: return false
        return name == HTML5_DEFAULT_NAME || name.startsWith(HTML5_PREFIX)
    }

    // remove all html5 per-container profiles from a profile list. used by Wine-context pickers
    // (XServerScreen EditModeToolbar "Copy From" dropdown).
    fun excludeHtml5(profiles: List<ControlsProfile>): List<ControlsProfile> =
        profiles.filterNot { isHtml5PerContainerProfile(it) }
}
