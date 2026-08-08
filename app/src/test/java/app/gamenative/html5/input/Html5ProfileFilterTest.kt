package app.gamenative.html5.input

import androidx.test.core.app.ApplicationProvider
import com.winlator.inputcontrols.ControlsProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// drift-locks Html5ProfileFilter against
// Html5DefaultControlsProfileFactory's naming scheme. if either side renames profiles
// without the other, this test fails — picker UI silently de-syncs from the actual
// per-container profiles otherwise.
@RunWith(RobolectricTestRunner::class)
class Html5ProfileFilterTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun profile(id: Int, name: String): ControlsProfile {
        val p = ControlsProfile(ctx, id)
        p.name = name
        return p
    }

    @Test fun canonical_html5_default_name_is_filtered() {
        val p = profile(1, Html5DefaultControlsProfileFactory.HTML5_DEFAULT_PROFILE_NAME)
        assertTrue(Html5ProfileFilter.isHtml5PerContainerProfile(p))
    }

    @Test fun html5_prefixed_per_container_name_is_filtered() {
        val p = profile(2, "HTML5: STEAM_3373660")
        assertTrue(Html5ProfileFilter.isHtml5PerContainerProfile(p))
    }

    @Test fun html5_suffixed_collision_name_is_filtered() {
        // Html5DefaultControlsProfileFactory.profileNameFor adds "(2)", "(3)", ... on collision.
        val p = profile(3, "HTML5: STEAM_3373660 (2)")
        assertTrue(Html5ProfileFilter.isHtml5PerContainerProfile(p))
    }

    @Test fun wine_default_profile_passes_through() {
        val p = profile(0, "Profile 0")
        assertFalse(Html5ProfileFilter.isHtml5PerContainerProfile(p))
    }

    @Test fun wine_per_game_profile_passes_through() {
        // XServerScreen auto-create names a Wine per-game profile "<gameName> - Physical Controller"
        val p = profile(4, "Half-Life 2 - Physical Controller")
        assertFalse(Html5ProfileFilter.isHtml5PerContainerProfile(p))
    }

    @Test fun excludeHtml5_drops_only_html5_entries() {
        val list = listOf(
            profile(0, "Profile 0"),
            profile(1, Html5DefaultControlsProfileFactory.HTML5_DEFAULT_PROFILE_NAME),
            profile(2, "HTML5: STEAM_111"),
            profile(3, "Half-Life 2 - Physical Controller"),
            profile(4, "HTML5: STEAM_222 (3)"),
        )
        val kept = Html5ProfileFilter.excludeHtml5(list)
        assertEquals(2, kept.size)
        assertEquals(setOf("Profile 0", "Half-Life 2 - Physical Controller"), kept.map { it.name }.toSet())
    }

    @Test fun excludeHtml5_on_empty_list_is_empty() {
        assertTrue(Html5ProfileFilter.excludeHtml5(emptyList()).isEmpty())
    }
}
