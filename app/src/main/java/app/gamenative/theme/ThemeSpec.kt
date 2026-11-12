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
    /** Library tiles and list rows. */
    val card: Int? = null,
    /** Secondary (non-accent) buttons. */
    val button: Int? = null,
    val onButton: Int? = null,
    /** Switches, sliders and progress bars in their "on" state. */
    val toggle: Int? = null,
    /** Context menus, dropdowns and dialogs. */
    val menu: Int? = null,
    val onMenu: Int? = null,
    /** Fill for accent buttons, toggles and progress bars; replaces the flat primary colour there. */
    val accentGradient: ThemeGradient? = null,
    /** Radius of a glow drawn around the focused item in the focus colour. */
    val focusGlowDp: Float? = null,
    val layout: ThemeLayout? = null,
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
        card = top.card ?: card,
        button = top.button ?: button,
        onButton = top.onButton ?: onButton,
        toggle = top.toggle ?: toggle,
        menu = top.menu ?: menu,
        onMenu = top.onMenu ?: onMenu,
        accentGradient = top.accentGradient ?: accentGradient,
        focusGlowDp = top.focusGlowDp ?: focusGlowDp,
        layout = when {
            top.layout == null -> layout
            layout == null -> top.layout
            else -> layout.overlay(top.layout)
        },
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
        if (card != null) add("card")
        if (button != null) add("button")
        if (onButton != null) add("onButton")
        if (toggle != null) add("toggle")
        if (menu != null) add("menu")
        if (onMenu != null) add("onMenu")
        if (accentGradient != null) add("accentGradient")
        if (focusGlowDp != null) add("focusGlow")
        if (layout != null && !layout.isEmpty) add("layout")
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

/** Layout values a theme can set. A null field keeps the app default. */
data class ThemeLayout(
    /** Multiplier on library tile size (1 = default). */
    val cardScale: Float? = null,
    /** "grid", "list" or "carousel"; used while the theme is on, the saved preference is not changed. */
    val libraryLayout: String? = null,
    /** "start" or "center", for game titles on tiles and on the game page. */
    val titleAlign: String? = null,
    val titleStrokeDp: Float? = null,
    val titleStrokeColor: Int? = null,
    /** Compatibility and status badges on tiles. */
    val hideBadges: Boolean? = null,
    /** The controller button hints bar at the bottom. */
    val hideFooterHints: Boolean? = null,
    /** Multiplier on the hero artwork height of the game page (1 = default). */
    val heroScale: Float? = null,
    val cardTiltDeg: Float? = null,
    /** Corner radius of library tiles only; beats [ThemeSpec.cornerRadiusDp] there. */
    val cardCornerRadiusDp: Float? = null,
) {
    val isEmpty: Boolean get() = this == ThemeLayout()

    fun overlay(top: ThemeLayout): ThemeLayout = ThemeLayout(
        cardScale = top.cardScale ?: cardScale,
        libraryLayout = top.libraryLayout ?: libraryLayout,
        titleAlign = top.titleAlign ?: titleAlign,
        titleStrokeDp = top.titleStrokeDp ?: titleStrokeDp,
        titleStrokeColor = top.titleStrokeColor ?: titleStrokeColor,
        hideBadges = top.hideBadges ?: hideBadges,
        hideFooterHints = top.hideFooterHints ?: hideFooterHints,
        heroScale = top.heroScale ?: heroScale,
        cardTiltDeg = top.cardTiltDeg ?: cardTiltDeg,
        cardCornerRadiusDp = top.cardCornerRadiusDp ?: cardCornerRadiusDp,
    )
}
