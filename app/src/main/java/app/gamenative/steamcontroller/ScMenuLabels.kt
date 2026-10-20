package app.gamenative.steamcontroller

import kotlinx.serialization.Serializable

/**
 * Custom labels for radial/touch-menu slots, authored in-app and layered over a resolved [ScConfig].
 *
 * Menus (their [MenuSlot.label]s) live inside the *analog* pad/stick modes, which the digital binding editor
 * ([ScEditableProfile]) doesn't touch — they come from an imported `.vdf` or the built-in default. So custom
 * labels are stored separately ([ScConfigStore] keys them by config key) and applied in [ScConfigStore.forKey],
 * so the live driver renders them regardless of where the underlying config came from. The render side already
 * honors [MenuSlot.label] (overlay shows it over the binding-derived default), so this is purely authoring +
 * persistence + an apply pass.
 */

/** Where a radial/touch menu can live on the controller (the four analog sources that can host a menu). */
enum class ScMenuLocation(val label: String) {
    LEFT_PAD("Left Pad"), RIGHT_PAD("Right Pad"), LEFT_STICK("Left Stick"), RIGHT_STICK("Right Stick")
}

/** One label override, keyed by action set + menu location + ring-slot index. */
@Serializable
data class MenuLabelOverride(val setId: String, val location: String, val slot: Int, val label: String)

/** Persisted label overrides for one config (a flat list keeps the JSON trivially serializable). */
@Serializable
data class ScMenuLabels(val overrides: List<MenuLabelOverride> = emptyList()) {
    fun labelFor(setId: String, location: ScMenuLocation, slot: Int): String? =
        overrides.firstOrNull { it.setId == setId && it.location == location.name && it.slot == slot }
            ?.label?.takeIf { it.isNotBlank() }
}

/** A menu found in a config, with each ring slot's default display label — what the editor lists. */
data class MenuDescriptor(val setId: String, val location: ScMenuLocation, val kind: String, val slotDefaults: List<String>)

/** Enumerate the menus in a config and apply label overrides back onto it. Pure (no Android) → unit-testable. */
object ScMenuLabelTool {

    private fun padSlots(m: PadMode): List<MenuSlot>? = when (m) {
        is PadMode.RadialMenu -> m.slots
        is PadMode.TouchMenu -> m.slots
        else -> null
    }

    private fun stickSlots(m: StickMode): List<MenuSlot>? = when (m) {
        is StickMode.RadialMenu -> m.slots
        is StickMode.TouchMenu -> m.slots
        else -> null
    }

    private fun padKind(m: PadMode): String = if (m is PadMode.RadialMenu) "Radial" else "Grid"
    private fun stickKind(m: StickMode): String = if (m is StickMode.RadialMenu) "Radial" else "Grid"

    /** Default display label for a slot: its existing label, else a binding-derived name, else "Slot N". */
    fun defaultLabel(slot: MenuSlot, index: Int): String =
        slot.label.ifBlank { bindingLabel(slot.binding.output) }.ifBlank { "Slot ${index + 1}" }

    private fun bindingLabel(out: ScOutput?): String = when (out) {
        is ScOutput.Key -> out.keys.joinToString("+") { it.name.removePrefix("KEY_") }
        is ScOutput.MouseButton -> out.button.name.removePrefix("BUTTON_")
        is ScOutput.GamepadButton -> "Pad #${out.idx}"
        else -> ""
    }

    /** All menus in [cfg], across action sets, in a stable order (set → location), for the editor to list. */
    fun enumerate(cfg: ScConfig): List<MenuDescriptor> {
        val out = ArrayList<MenuDescriptor>()
        for ((setId, p) in cfg.sets) {
            fun add(loc: ScMenuLocation, slots: List<MenuSlot>?, kind: String) {
                if (slots != null && slots.isNotEmpty()) {
                    out.add(MenuDescriptor(setId, loc, kind, slots.mapIndexed { i, s -> defaultLabel(s, i) }))
                }
            }
            add(ScMenuLocation.LEFT_PAD, padSlots(p.leftPad), padKind(p.leftPad))
            add(ScMenuLocation.RIGHT_PAD, padSlots(p.rightPad), padKind(p.rightPad))
            add(ScMenuLocation.LEFT_STICK, stickSlots(p.leftStick), stickKind(p.leftStick))
            add(ScMenuLocation.RIGHT_STICK, stickSlots(p.rightStick), stickKind(p.rightStick))
        }
        return out
    }

    /** Return a copy of [cfg] with menu-slot labels overridden per [labels]. Untouched if [labels] is empty. */
    fun apply(cfg: ScConfig, labels: ScMenuLabels): ScConfig {
        if (labels.overrides.isEmpty()) return cfg
        val newSets = cfg.sets.mapValues { (setId, p) ->
            // ScProfile isn't a data class, so rebuild it explicitly, swapping only the four menu-hosting fields.
            ScProfile(
                name = p.name,
                buttons = p.buttons,
                leftStick = relabelStick(p.leftStick) { labels.labelFor(setId, ScMenuLocation.LEFT_STICK, it) },
                rightStick = relabelStick(p.rightStick) { labels.labelFor(setId, ScMenuLocation.RIGHT_STICK, it) },
                leftPad = relabelPad(p.leftPad) { labels.labelFor(setId, ScMenuLocation.LEFT_PAD, it) },
                rightPad = relabelPad(p.rightPad) { labels.labelFor(setId, ScMenuLocation.RIGHT_PAD, it) },
                leftTrigger = p.leftTrigger,
                rightTrigger = p.rightTrigger,
                gyro = p.gyro,
                haptics = p.haptics,
            )
        }
        return ScConfig(newSets, cfg.defaultSetId, cfg.setSources, cfg.shiftOverlays)
    }

    private fun relabel(slots: List<MenuSlot>, labelOf: (Int) -> String?): List<MenuSlot> =
        slots.mapIndexed { i, s -> labelOf(i)?.let { s.copy(label = it) } ?: s }

    private fun relabelPad(m: PadMode, labelOf: (Int) -> String?): PadMode = when (m) {
        is PadMode.RadialMenu -> m.copy(slots = relabel(m.slots, labelOf))
        is PadMode.TouchMenu -> m.copy(slots = relabel(m.slots, labelOf))
        else -> m
    }

    private fun relabelStick(m: StickMode, labelOf: (Int) -> String?): StickMode = when (m) {
        is StickMode.RadialMenu -> m.copy(slots = relabel(m.slots, labelOf))
        is StickMode.TouchMenu -> m.copy(slots = relabel(m.slots, labelOf))
        else -> m
    }
}
