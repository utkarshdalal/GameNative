package app.gamenative.steamcontroller

import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScMenuLabelToolTest {
    private fun slot(key: XKeycode, label: String = "") = MenuSlot(Binding(ScOutput.Key(key)), label)

    private fun cfg(): ScConfig {
        val p = ScProfile(
            rightPad = PadMode.RadialMenu(
                slots = listOf(slot(XKeycode.KEY_1), slot(XKeycode.KEY_2, "Steam Label"), slot(XKeycode.KEY_3)),
            ),
            leftStick = StickMode.RadialMenu(slots = listOf(slot(XKeycode.KEY_W), slot(XKeycode.KEY_A))),
        )
        return ScConfig(sets = mapOf("0" to p), defaultSetId = "0")
    }

    @Test
    fun `enumerate lists each menu with binding-derived or existing defaults`() {
        val menus = ScMenuLabelTool.enumerate(cfg())
        assertEquals(2, menus.size)
        val pad = menus.first { it.location == ScMenuLocation.RIGHT_PAD }
        assertEquals("Radial", pad.kind)
        // slot 0 -> binding name; slot 1 -> its existing label wins; slot 2 -> binding name
        assertEquals(listOf("1", "Steam Label", "3"), pad.slotDefaults)
        val stick = menus.first { it.location == ScMenuLocation.LEFT_STICK }
        assertEquals(listOf("W", "A"), stick.slotDefaults)
    }

    @Test
    fun `apply overrides only the targeted slot, leaving others intact`() {
        val labels = ScMenuLabels(listOf(MenuLabelOverride("0", "RIGHT_PAD", 0, "Heal")))
        val out = ScMenuLabelTool.apply(cfg(), labels)
        val pad = out.sets.getValue("0").rightPad as PadMode.RadialMenu
        assertEquals("Heal", pad.slots[0].label)        // overridden
        assertEquals("Steam Label", pad.slots[1].label)  // untouched existing label
        assertEquals("", pad.slots[2].label)             // still default (blank)
        // a different menu is unaffected
        val stick = out.sets.getValue("0").leftStick as StickMode.RadialMenu
        assertEquals("", stick.slots[0].label)
    }

    @Test
    fun `apply with no overrides returns the same config unchanged`() {
        val c = cfg()
        assertSame(c, ScMenuLabelTool.apply(c, ScMenuLabels()))
    }

    @Test
    fun `labelFor matches set, location and slot exactly`() {
        val labels = ScMenuLabels(listOf(MenuLabelOverride("0", "RIGHT_PAD", 1, "Mount")))
        assertEquals("Mount", labels.labelFor("0", ScMenuLocation.RIGHT_PAD, 1))
        assertNull(labels.labelFor("0", ScMenuLocation.RIGHT_PAD, 0))
        assertNull(labels.labelFor("1", ScMenuLocation.RIGHT_PAD, 1))
        assertNull(labels.labelFor("0", ScMenuLocation.LEFT_PAD, 1))
    }
}
