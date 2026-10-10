package app.gamenative.steamcontroller

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Locks the per-menu placement fallback chain in [ScOverlayStore.forMenu] (the #8 money path). */
@RunWith(RobolectricTestRunner::class)
class ScOverlayStoreTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val game = "GAME_1"
    private val menu = "LEFT_PAD"

    @Before
    fun clean() {
        ctx.getSharedPreferences("sc_overlay", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun `forMenu falls back per-menu-game to per-menu-global to whole-HUD to built-in`() {
        // Nothing stored → built-in default.
        assertEquals(ScOverlayLayout(), ScOverlayStore.forMenu(ctx, game, menu))

        // Whole-HUD per-game placement applies to every menu until a per-menu override exists.
        ScOverlayStore.save(ctx, game, ScOverlayLayout(scale = 1.5f, cx = 0.2f, cy = 0.3f))
        assertEquals(0.2f, ScOverlayStore.forMenu(ctx, game, menu).cx, 0.001f)

        // A per-menu-global override beats the whole-HUD placement.
        ScOverlayStore.saveMenu(ctx, ScOverlayStore.DEFAULT_KEY, menu, ScOverlayLayout(cx = 0.6f))
        assertEquals(0.6f, ScOverlayStore.forMenu(ctx, game, menu).cx, 0.001f)

        // A per-menu-per-game override wins outright.
        ScOverlayStore.saveMenu(ctx, game, menu, ScOverlayLayout(cx = 0.9f))
        assertTrue(ScOverlayStore.hasMenu(ctx, game, menu))
        assertEquals(0.9f, ScOverlayStore.forMenu(ctx, game, menu).cx, 0.001f)

        // A different menu on the same game still gets the whole-HUD placement (not the LEFT_PAD override).
        assertEquals(0.2f, ScOverlayStore.forMenu(ctx, game, "RIGHT_PAD").cx, 0.001f)

        // Clearing the per-menu override reverts to the per-menu-global (0.6), not built-in.
        ScOverlayStore.clearMenu(ctx, game, menu)
        assertFalse(ScOverlayStore.hasMenu(ctx, game, menu))
        assertEquals(0.6f, ScOverlayStore.forMenu(ctx, game, menu).cx, 0.001f)
    }
}
