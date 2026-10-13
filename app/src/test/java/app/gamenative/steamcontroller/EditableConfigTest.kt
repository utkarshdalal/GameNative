package app.gamenative.steamcontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phase 5d multi-action-set editor model: [ScEditableConfig] ↔ runtime [ScConfig], switch-set binds, migration. */
@RunWith(RobolectricTestRunner::class)
class EditableConfigTest {

    @Test
    fun `switch-action-set binding round-trips through EditBinding`() {
        val eb = EditBinding(kind = OutputKind.SWITCH_ACTION_SET, targetSetId = "2")
        val out = eb.toOutput()
        assertTrue(out is ScOutput.SwitchActionSet)
        assertEquals("2", (out as ScOutput.SwitchActionSet).targetSetId)
        // and back
        val back = EditBinding.fromOutput(out)
        assertEquals(OutputKind.SWITCH_ACTION_SET, back.kind)
        assertEquals("2", back.targetSetId)
    }

    @Test
    fun `blank target set id yields no output (None)`() {
        assertTrue(EditBinding(OutputKind.SWITCH_ACTION_SET, targetSetId = "").toOutput() is ScOutput.None)
    }

    @Test
    fun `toScConfig maps every set by id with the chosen default`() {
        val cfg = ScEditableConfig(
            sets = listOf(
                ScEditableSet(id = "0", name = "Default"),
                ScEditableSet(
                    id = "1", name = "Menu",
                    profile = ScEditableProfile(
                        buttons = mapOf("RIGHT_BUMPER" to EditBinding(OutputKind.SWITCH_ACTION_SET, targetSetId = "0")),
                    ),
                ),
            ),
            defaultSetId = "1",
        )
        val rc = cfg.toScConfig()
        assertEquals(setOf("0", "1"), rc.sets.keys)
        assertEquals("1", rc.defaultSetId)
        // The set-1 right-bumper bind became a runtime SwitchActionSet to set 0.
        val out = rc.sets["1"]!!.buttons[TritonProtocol.BTN_RBUMPER]?.output
        assertTrue(out is ScOutput.SwitchActionSet)
        assertEquals("0", (out as ScOutput.SwitchActionSet).targetSetId)
    }

    @Test
    fun `defaultSetId falls back to first set when the id is unknown`() {
        val cfg = ScEditableConfig(sets = listOf(ScEditableSet(id = "a"), ScEditableSet(id = "b")), defaultSetId = "zzz")
        assertEquals("a", cfg.toScConfig().defaultSetId)
    }

    @Test
    fun `fromSingle wraps a legacy profile as one set keyed 0`() {
        val p = ScEditableProfile(name = "Legacy")
        val cfg = ScEditableConfig.fromSingle(p)
        assertEquals(1, cfg.sets.size)
        assertEquals("0", cfg.sets[0].id)
        assertEquals("0", cfg.defaultSetId)
        assertEquals("Legacy", cfg.sets[0].name)
    }

    @Test
    fun `layer ops and controller actions round-trip through EditBinding`() {
        // Hold-layer
        val hold = EditBinding(OutputKind.LAYER_OP, layerId = "L", layerOp = "HOLD").toOutput()
        assertTrue(hold is ScOutput.LayerOp)
        assertEquals("L", (hold as ScOutput.LayerOp).layerId)
        assertEquals(LayerOpType.HOLD, hold.op)
        assertEquals(OutputKind.LAYER_OP, EditBinding.fromOutput(hold).kind)
        assertEquals("L", EditBinding.fromOutput(hold).layerId)
        // Show keyboard / open quick menu
        assertTrue(EditBinding(OutputKind.SHOW_KEYBOARD).toOutput() is ScOutput.ShowKeyboard)
        assertTrue(EditBinding(OutputKind.OPEN_QUICK_MENU).toOutput() is ScOutput.OpenQuickMenu)
        assertEquals(OutputKind.SHOW_KEYBOARD, EditBinding.fromOutput(ScOutput.ShowKeyboard).kind)
        assertEquals(OutputKind.OPEN_QUICK_MENU, EditBinding.fromOutput(ScOutput.OpenQuickMenu).kind)
        // A blank layer target yields no output.
        assertTrue(EditBinding(OutputKind.LAYER_OP, layerId = "").toOutput() is ScOutput.None)
    }

    @Test
    fun `an authored layer derives its setSources and a hold-layer bind drives the merge`() {
        // Base set: hold Left Bumper -> push the "1" layer. Layer "1": rebinds A -> key 9 AND overrides the right pad.
        val cfg = ScEditableConfig(
            sets = listOf(
                ScEditableSet(
                    id = "0", name = "Base",
                    profile = ScEditableProfile(buttons = mapOf("LEFT_BUMPER" to EditBinding(OutputKind.LAYER_OP, layerId = "1", layerOp = "HOLD"))),
                ),
                ScEditableSet(
                    id = "1", name = "Overlay", isLayer = true,
                    profile = ScEditableProfile(
                        buttons = mapOf("A" to EditBinding(OutputKind.KEY, keys = listOf("KEY_9"))),
                        rightPad = EditAnalog(AnalogMode.SCROLL_WHEEL), // an analog override -> right_trackpad source
                    ),
                ),
            ),
            defaultSetId = "0",
        )
        val rc = cfg.toScConfig()
        // The layer's derived sources include the right pad it overrides (buttons always merge, so they're not listed).
        assertEquals(setOf("right_trackpad"), rc.setSources["1"])
        // Base has no source list (it's switched-to, not merged).
        assertTrue(rc.setSources["0"] == null)
        // The hold-layer bind is a runtime LayerOp(HOLD) on the bumper.
        val out = rc.sets["0"]!!.buttons[TritonProtocol.BTN_LBUMPER]?.output
        assertTrue(out is ScOutput.LayerOp && (out as ScOutput.LayerOp).op == LayerOpType.HOLD && out.layerId == "1")
        // mergeProfiles applies the layer: A -> key 9 (button merge) and the right pad becomes a scroll wheel.
        val merged = mergeProfiles(rc.sets["0"]!!, rc.sets["1"]!!, rc.setSources["1"]!!)
        assertEquals(listOf(com.winlator.xserver.XKeycode.KEY_9), (merged.buttons[TritonProtocol.BTN_A]!!.output as ScOutput.Key).keys)
        assertTrue(merged.rightPad is PadMode.ScrollWheel)
    }

    @Test
    fun `nextSetId skips ids already in use`() {
        val cfg = ScEditableConfig(sets = listOf(ScEditableSet(id = "0"), ScEditableSet(id = "1")))
        assertEquals("2", cfg.nextSetId())
        val gap = ScEditableConfig(sets = listOf(ScEditableSet(id = "0"), ScEditableSet(id = "2")))
        assertEquals("1", gap.nextSetId())
    }
}
