package app.gamenative.theme

/**
 * Appearance values a theme can set. A null field keeps the app default.
 * Colours are ARGB ints so this file has no Android or Compose dependency.
 */
data class ThemeSpec(
    val background: Int? = null,
    val surface: Int? = null,
    val surfaceVariant: Int? = null,
    val primary: Int? = null,
    val onPrimary: Int? = null,
    val text: Int? = null,
    val textMuted: Int? = null,
    val outline: Int? = null,
    val focus: Int? = null,
    /** Top and bottom bar colour, alpha included. */
    val bar: Int? = null,
    val cornerRadiusDp: Float? = null,
    /** Absolute path of a .ttf/.otf file. */
    val fontFile: String? = null,
    /** Absolute path of an image drawn behind the main screens. */
    val backgroundImage: String? = null,
    val backgroundGradient: ThemeGradient? = null,
    /** 0..1 opacity of panels, dialogs and side menus. */
    val panelOpacity: Float? = null,
    val blurDp: Float? = null,
) {
    val isEmpty: Boolean get() = this == EMPTY

    /** Fields set in [top] replace the fields of this spec. */
    fun overlay(top: ThemeSpec): ThemeSpec = ThemeSpec(
        background = top.background ?: background,
        surface = top.surface ?: surface,
        surfaceVariant = top.surfaceVariant ?: surfaceVariant,
        primary = top.primary ?: primary,
        onPrimary = top.onPrimary ?: onPrimary,
        text = top.text ?: text,
        textMuted = top.textMuted ?: textMuted,
        outline = top.outline ?: outline,
        focus = top.focus ?: focus,
        bar = top.bar ?: bar,
        cornerRadiusDp = top.cornerRadiusDp ?: cornerRadiusDp,
        fontFile = top.fontFile ?: fontFile,
        backgroundImage = top.backgroundImage ?: backgroundImage,
        backgroundGradient = top.backgroundGradient ?: backgroundGradient,
        panelOpacity = top.panelOpacity ?: panelOpacity,
        blurDp = top.blurDp ?: blurDp,
    )

    /** Names of the fields that are set, for display. */
    fun setFields(): List<String> = buildList {
        if (background != null) add("background")
        if (surface != null) add("surface")
        if (surfaceVariant != null) add("surfaceVariant")
        if (primary != null) add("primary")
        if (onPrimary != null) add("onPrimary")
        if (text != null) add("text")
        if (textMuted != null) add("textMuted")
        if (outline != null) add("outline")
        if (focus != null) add("focus")
        if (bar != null) add("bar")
        if (cornerRadiusDp != null) add("cornerRadius")
        if (fontFile != null) add("font")
        if (backgroundImage != null) add("backgroundImage")
        if (backgroundGradient != null) add("backgroundGradient")
        if (panelOpacity != null) add("panelOpacity")
        if (blurDp != null) add("blur")
    }

    companion object {
        val EMPTY = ThemeSpec()
    }
}

/** Linear gradient: [angleDeg] follows CSS (0 = to top, 90 = to right, 180 = to bottom). */
data class ThemeGradient(
    val angleDeg: Float,
    val colors: List<Int>,
    /** 0..1 positions, same size as [colors], or empty for even spacing. */
    val stops: List<Float> = emptyList(),
)
