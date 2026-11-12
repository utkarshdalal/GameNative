package app.gamenative.ui.theme

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import app.gamenative.theme.ThemeManager
import app.gamenative.theme.ThemeSpec

/**
 * Theme colours for the in-game overlays drawn with Android views. Every getter returns its [default] without a
 * theme, and keeps the default's alpha so overlays stay as translucent as they are today.
 * Read when a view is created or shown, not per frame.
 */
object ThemeViewColors {

    private const val MIN_TEXT_CONTRAST = 4.5
    private const val DARK_PANEL_LUMINANCE = 0.5

    private val spec: ThemeSpec get() = ThemeManager.activeSpec.value

    private fun Int.withAlphaOf(source: Int): Int = ColorUtils.setAlphaComponent(this, Color.alpha(source))

    private fun ThemeSpec.panelSource(): Int? = menu ?: surface

    private fun ThemeSpec.accentSource(): Int? = primary ?: accentGradient?.colors?.firstOrNull()

    /** Selected slot / highlighted key fill. */
    fun accent(default: Int): Int = spec.accentSource()?.withAlphaOf(default) ?: default

    /** Slot, key and toast backgrounds. */
    fun panel(default: Int): Int = spec.panelSource()?.withAlphaOf(default) ?: default

    /** Like [panel], but only a dark theme panel replaces [default], for overlays whose content is bright. */
    fun darkPanel(default: Int): Int {
        val themed = spec.panelSource() ?: return default
        val opaque = ColorUtils.setAlphaComponent(themed, 255)
        return if (ColorUtils.calculateLuminance(opaque) < DARK_PANEL_LUMINANCE) themed.withAlphaOf(default) else default
    }

    /** Label text, kept readable on the themed panel. */
    fun text(default: Int): Int {
        val s = spec
        val themedText = s.onMenu ?: s.text
        val panel = s.panelSource()
        val fg = themedText?.withAlphaOf(default) ?: default
        if (panel == null) return fg
        val back = ColorUtils.setAlphaComponent(panel, 255)
        val opaqueFg = ColorUtils.setAlphaComponent(fg, 255)
        if (ColorUtils.calculateContrast(opaqueFg, back) >= MIN_TEXT_CONTRAST) return fg
        val white = ColorUtils.calculateContrast(Color.WHITE, back)
        val black = ColorUtils.calculateContrast(Color.BLACK, back)
        return (if (white >= black) Color.WHITE else Color.BLACK).withAlphaOf(default)
    }

    /** Cursor and focus marker. */
    fun focus(default: Int): Int = (spec.focus ?: spec.accentSource())?.withAlphaOf(default) ?: default
}
