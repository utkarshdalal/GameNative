package app.gamenative.html5.profile

import kotlinx.serialization.Serializable

// "pointer-with-tap-detection" or "native-controller" -- consumed by InputModeResolver.
@Serializable
data class InputSpec(
    val mode: String = "pointer-with-tap-detection",
)
