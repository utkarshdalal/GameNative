package app.gamenative.html5.input

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.runtime.WebViewContainer

// single resolution function. called once per launch in WebViewScreen. tri-state rule:
// 1. container.inputMap (user override) wins when non-blank
// 2. profile.input.mode (pack default) when set
// 3. schema default "pointer-with-tap-detection" (matches InputSpec.mode default)
// pure kotlin -- no android deps, unit-testable in pure JVM.
// consumers: WebViewScreen.kt decides shim injection + down-wires the resolved mode.
fun resolveInputMode(container: WebViewContainer, profile: EngineProfile?): String {
    if (container.inputMap.isNotBlank()) return container.inputMap
    val profileMode = profile?.input?.mode
    if (!profileMode.isNullOrBlank()) return profileMode
    return SCHEMA_DEFAULT_INPUT_MODE
}

// exposed so tests and pack code can reference the same constant.
// matches InputSpec.mode default -- kept in sync by drift-guard test in InputModeResolverTest.
const val SCHEMA_DEFAULT_INPUT_MODE: String = "pointer-with-tap-detection"
