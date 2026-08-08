package app.gamenative.html5.input

import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.InputSpec
import app.gamenative.runtime.WebViewContainer
import org.junit.Assert.assertEquals
import org.junit.Test

// pure-JVM — WebViewContainer is @Serializable data class (no android <clinit> on construction).
// Precedent: WebViewContainerTest also runs pure-jvm. Robolectric only kicks in when code paths
// reach Container.java or Environment (see WebViewContainerJsonTest comments).
class InputModeResolverTest {

    private fun container(mode: String) = WebViewContainer(
        id = "test", installPath = "/tmp/test", engineProfile = "pack:rmmv",
        inputMap = mode,
    )

    private fun profile(mode: String?) = EngineProfile(
        engine = "pack:rmmv",
        input = mode?.let { InputSpec(mode = it) },
    )

    @Test fun container_nonblank_wins_over_profile() {
        // rule 1: user override wins over pack default
        assertEquals(
            "native-controller",
            resolveInputMode(container("native-controller"), profile("pointer-with-tap-detection")),
        )
    }

    @Test fun container_blank_falls_through_to_profile_mode() {
        // rule 2: unset container → profile default drives
        assertEquals(
            "native-controller",
            resolveInputMode(container(""), profile("native-controller")),
        )
    }

    @Test fun container_whitespace_treated_as_blank() {
        // isNotBlank() means " " falls through (defensive — not a spec requirement, but
        // the obvious behavior if somebody ever persists whitespace by accident)
        assertEquals(
            "native-controller",
            resolveInputMode(container("   "), profile("native-controller")),
        )
    }

    @Test fun both_blank_returns_schema_default() {
        // rule 3: schema default when nothing is set
        assertEquals(
            "pointer-with-tap-detection",
            resolveInputMode(container(""), EngineProfile(engine = "pack:rmmv", input = null)),
        )
    }

    @Test fun null_profile_returns_schema_default() {
        assertEquals(
            "pointer-with-tap-detection",
            resolveInputMode(container(""), null),
        )
    }

    @Test fun schema_default_constant_matches_InputSpec_default() {
        // drift guard — if InputSpec.mode default changes, this must update or SCHEMA_DEFAULT_INPUT_MODE
        // diverges from the actual default seen by profile parsing.
        assertEquals(InputSpec().mode, SCHEMA_DEFAULT_INPUT_MODE)
    }
}
