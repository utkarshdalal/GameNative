package app.gamenative.html5.input

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.runtime.WebViewContainer

// precedence: user override (container.inputMap) > pack default > schema default.
fun resolveInputMode(container: WebViewContainer, profile: EngineProfile?): String {
    if (container.inputMap.isNotBlank()) return container.inputMap
    val profileMode = profile?.input?.mode
    if (!profileMode.isNullOrBlank()) return profileMode
    return SCHEMA_DEFAULT_INPUT_MODE
}

// must match InputSpec.mode default (guarded by InputModeResolverTest).
const val SCHEMA_DEFAULT_INPUT_MODE: String = "pointer-with-tap-detection"
