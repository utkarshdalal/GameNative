package app.gamenative.theme

/**
 * Appearance values a theme can set. A null field keeps the app default.
 * Colors are ARGB ints so this file has no Android or Compose dependency.
 */
data class ThemeSpec(
    val background: Int? = null,
    val surface: Int? = null,
    val surfaceVariant: Int? = null,
    val primary: Int? = null,
    val onPrimary: Int? = null,
    /** Main text and setting row labels. */
    val text: Int? = null,
    /** Descriptions and secondary text. */
    val textMuted: Int? = null,
    val outline: Int? = null,
    val focus: Int? = null,
    /** Top and bottom bar color, alpha included; [header] / [footer] values beat it on their bar. */
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
    /** Fill for accent buttons, toggles and progress bars; replaces the flat primary color there. */
    val accentGradient: ThemeGradient? = null,
    /** Radius of a glow drawn around the focused item in the focus color. */
    val focusGlowDp: Float? = null,
    val layout: ThemeLayout? = null,
    /** Top bar; unset values fall back to [bar]. */
    val header: ThemeBar? = null,
    /** Bottom button-hint bar; unset values fall back to [bar]. */
    val footer: ThemeBar? = null,
    /** Text colors per role and text effects; unset roles fall back to [text] / [textMuted]. */
    val textRoles: ThemeTextRoles? = null,
    /** Switches, sliders and secondary buttons beyond the flat [toggle] / [button] colors. */
    val controls: ThemeControls? = null,
    /** Controller focus beyond [focus] and [focusGlowDp]. */
    val focusStyle: ThemeFocus? = null,
    /** Shape and effects of menus, dropdowns and dialogs beyond [menu] / [onMenu]. */
    val menuStyle: ThemeMenu? = null,
    /** Side menus and panels; when set it replaces the flat [surface] fill there. */
    val panelFill: ThemeFill? = null,
    /** Text size, weight, letter spacing and case beyond [fontFile]. */
    val typography: ThemeTypography? = null,
    /** The focused game's artwork behind the home carousel and on the game page. */
    val hero: ThemeHero? = null,
    /** Gradient fills that slide across their element. */
    val motion: ThemeMotion? = null,
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
        header = merge(header, top.header, ThemeBar::overlay),
        footer = merge(footer, top.footer, ThemeBar::overlay),
        textRoles = merge(textRoles, top.textRoles, ThemeTextRoles::overlay),
        controls = merge(controls, top.controls, ThemeControls::overlay),
        focusStyle = merge(focusStyle, top.focusStyle, ThemeFocus::overlay),
        menuStyle = merge(menuStyle, top.menuStyle, ThemeMenu::overlay),
        panelFill = top.panelFill ?: panelFill,
        typography = merge(typography, top.typography, ThemeTypography::overlay),
        hero = merge(hero, top.hero, ThemeHero::overlay),
        motion = merge(motion, top.motion, ThemeMotion::overlay),
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
        if (header != null && !header.isEmpty) add("header")
        if (footer != null && !footer.isEmpty) add("footer")
        if (textRoles != null && !textRoles.isEmpty) add("textRoles")
        if (controls != null && !controls.isEmpty) add("controls")
        if (focusStyle != null && !focusStyle.isEmpty) add("focusStyle")
        if (menuStyle != null && !menuStyle.isEmpty) add("menuStyle")
        if (panelFill != null && !panelFill.isEmpty) add("panelFill")
        if (typography != null && !typography.isEmpty) add("typography")
        if (hero != null && !hero.isEmpty) add("hero")
        if (motion != null && !motion.isEmpty) add("motion")
    }

    companion object {
        val EMPTY = ThemeSpec()

        private fun <T> merge(below: T?, top: T?, overlay: (T, T) -> T): T? = when {
            top == null -> below
            below == null -> top
            else -> overlay(below, top)
        }
    }
}

/**
 * A CSS background: [layers] painted over [color], the first layer on top as in CSS.
 * Layers may be translucent (e.g. radial halos that fade to transparent).
 */
data class ThemeFill(
    val layers: List<ThemeGradient> = emptyList(),
    val color: Int? = null,
) {
    val isEmpty: Boolean get() = layers.isEmpty() && color == null

    /** The color a flat-only surface uses: the base color, else the bottom layer's first stop. */
    val flatColor: Int? get() = color ?: layers.lastOrNull()?.colors?.firstOrNull()

    companion object {
        fun of(color: Int?): ThemeFill? = color?.let { ThemeFill(color = it) }
    }
}

/** Top or bottom bar. Lengths in dp; colors are ARGB with their alpha. */
data class ThemeBar(
    val color: Int? = null,
    /** Gradient layers drawn over [color]. */
    val fill: ThemeFill? = null,
    /** Line on the edge facing the content: the top edge of the footer, the bottom edge of the header. */
    val borderColor: Int? = null,
    val borderDp: Float? = null,
    /** Glow around the bar. */
    val glowColor: Int? = null,
    val glowDp: Float? = null,
    /** Blur of whatever is behind the bar. */
    val blurDp: Float? = null,
    /** Text and icons on the bar. */
    val textColor: Int? = null,
    /** Multiplier on the bar height (1 = default). */
    val heightScale: Float? = null,
) {
    val isEmpty: Boolean get() = this == ThemeBar()

    fun overlay(top: ThemeBar): ThemeBar = ThemeBar(
        color = top.color ?: color,
        fill = top.fill ?: fill,
        borderColor = top.borderColor ?: borderColor,
        borderDp = top.borderDp ?: borderDp,
        glowColor = top.glowColor ?: glowColor,
        glowDp = top.glowDp ?: glowDp,
        blurDp = top.blurDp ?: blurDp,
        textColor = top.textColor ?: textColor,
        heightScale = top.heightScale ?: heightScale,
    )
}

/** Text colors per role and effects on text. A null role falls back to [ThemeSpec.text] or [ThemeSpec.textMuted]. */
data class ThemeTextRoles(
    /** Section headings above groups of rows (row labels use [ThemeSpec.text]). */
    val heading: Int? = null,
    /** Line under a game title (play time, status). */
    val subtitle: Int? = null,
    /** Glow behind body text: color and blur radius in dp. */
    val glowColor: Int? = null,
    val glowDp: Float? = null,
) {
    val isEmpty: Boolean get() = this == ThemeTextRoles()

    fun overlay(top: ThemeTextRoles): ThemeTextRoles = ThemeTextRoles(
        heading = top.heading ?: heading,
        subtitle = top.subtitle ?: subtitle,
        glowColor = top.glowColor ?: glowColor,
        glowDp = top.glowDp ?: glowDp,
    )
}

/** Switch, slider and secondary button styling. */
data class ThemeControls(
    /** Switch track when on; may be a gradient. */
    val toggleOn: ThemeFill? = null,
    /** Switch track when off. */
    val toggleOff: Int? = null,
    /** Switch handle when on / off. */
    val thumbOn: Int? = null,
    val thumbOff: Int? = null,
    /** Slider and progress track outside the filled part. */
    val sliderTrack: Int? = null,
    val sliderThumb: Int? = null,
    /** Secondary button fill; may be a gradient. Its flat color is [ThemeSpec.button]. */
    val buttonFill: ThemeFill? = null,
    /** Secondary button fill and text while focused. */
    val buttonFocusFill: ThemeFill? = null,
    val onButtonFocus: Int? = null,
    /** Line under secondary buttons. */
    val buttonUnderlineColor: Int? = null,
    val buttonUnderlineDp: Float? = null,
    /** Fill and text of the selected tab (not the focused one). */
    val tabFill: Int? = null,
    val onTab: Int? = null,
) {
    val isEmpty: Boolean get() = this == ThemeControls()

    fun overlay(top: ThemeControls): ThemeControls = ThemeControls(
        toggleOn = top.toggleOn ?: toggleOn,
        toggleOff = top.toggleOff ?: toggleOff,
        thumbOn = top.thumbOn ?: thumbOn,
        thumbOff = top.thumbOff ?: thumbOff,
        sliderTrack = top.sliderTrack ?: sliderTrack,
        sliderThumb = top.sliderThumb ?: sliderThumb,
        buttonFill = top.buttonFill ?: buttonFill,
        buttonFocusFill = top.buttonFocusFill ?: buttonFocusFill,
        onButtonFocus = top.onButtonFocus ?: onButtonFocus,
        buttonUnderlineColor = top.buttonUnderlineColor ?: buttonUnderlineColor,
        buttonUnderlineDp = top.buttonUnderlineDp ?: buttonUnderlineDp,
        tabFill = top.tabFill ?: tabFill,
        onTab = top.onTab ?: onTab,
    )
}

/** Controller focus. The ring color is [ThemeSpec.focus]. */
data class ThemeFocus(
    /** Ring stroke width in dp; 0 = no ring. */
    val ringDp: Float? = null,
    /** Ring color with its alpha, when the theme gives the ring its own; else [ThemeSpec.focus]. */
    val ringColor: Int? = null,
    /** Fill of the focused row or tile, and the text on it. */
    val fill: ThemeFill? = null,
    val onFill: Int? = null,
    /** Line under the focused item, and its height in dp. */
    val underline: ThemeGradient? = null,
    val underlineDp: Float? = null,
    /** The ring pulses while focused. */
    val blink: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == ThemeFocus()

    fun overlay(top: ThemeFocus): ThemeFocus = ThemeFocus(
        ringDp = top.ringDp ?: ringDp,
        ringColor = top.ringColor ?: ringColor,
        fill = top.fill ?: fill,
        onFill = top.onFill ?: onFill,
        underline = top.underline ?: underline,
        underlineDp = top.underlineDp ?: underlineDp,
        blink = top.blink ?: blink,
    )
}

/** Menus, dropdowns and dialogs. Their flat fill and text are [ThemeSpec.menu] / [ThemeSpec.onMenu]. */
data class ThemeMenu(
    val cornerRadiusDp: Float? = null,
    /** Gradient layers over the menu color. */
    val fill: ThemeFill? = null,
    /** 0..1 opacity of the menu fill. */
    val opacity: Float? = null,
    /** Blur of whatever is behind the menu. */
    val blurDp: Float? = null,
    val borderColor: Int? = null,
    val borderDp: Float? = null,
    /** Glow around the menu in [borderColor] (or the focus color). */
    val glowDp: Float? = null,
    /** Fill and text of the focused menu item. */
    val focusFill: Int? = null,
    val onFocus: Int? = null,
) {
    val isEmpty: Boolean get() = this == ThemeMenu()

    fun overlay(top: ThemeMenu): ThemeMenu = ThemeMenu(
        cornerRadiusDp = top.cornerRadiusDp ?: cornerRadiusDp,
        fill = top.fill ?: fill,
        opacity = top.opacity ?: opacity,
        blurDp = top.blurDp ?: blurDp,
        borderColor = top.borderColor ?: borderColor,
        borderDp = top.borderDp ?: borderDp,
        glowDp = top.glowDp ?: glowDp,
        focusFill = top.focusFill ?: focusFill,
        onFocus = top.onFocus ?: onFocus,
    )
}

/** Text beyond the font file. Sizes are multipliers on the app's own (1 = default), kept within [MIN_SCALE]..[MAX_SCALE]. */
data class ThemeTypography(
    /** All text. */
    val scale: Float? = null,
    /** Game titles on tiles, the carousel and the game page, on top of [scale]. */
    val titleScale: Float? = null,
    /** Button-hint labels in the footer, on top of [scale]. */
    val footerScale: Float? = null,
    /** CSS font weight (100..900) of section headings. */
    val headingWeight: Int? = null,
    /** CSS font weight of the selected tab or menu item. */
    val selectedWeight: Int? = null,
    /** Extra space between letters, in em. */
    val letterSpacingEm: Float? = null,
    /** Upper-case section headings, tab labels and footer labels; false keeps their case. */
    val headingUppercase: Boolean? = null,
    val tabUppercase: Boolean? = null,
    val footerUppercase: Boolean? = null,
    /** Upper-case all labels, headings, tabs, footer labels and game titles (never text the user types). */
    val allUppercase: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == ThemeTypography()

    fun overlay(top: ThemeTypography): ThemeTypography = ThemeTypography(
        scale = top.scale ?: scale,
        titleScale = top.titleScale ?: titleScale,
        footerScale = top.footerScale ?: footerScale,
        headingWeight = top.headingWeight ?: headingWeight,
        selectedWeight = top.selectedWeight ?: selectedWeight,
        letterSpacingEm = top.letterSpacingEm ?: letterSpacingEm,
        headingUppercase = top.headingUppercase ?: headingUppercase,
        tabUppercase = top.tabUppercase ?: tabUppercase,
        footerUppercase = top.footerUppercase ?: footerUppercase,
        allUppercase = top.allUppercase ?: allUppercase,
    )

    companion object {
        const val MIN_SCALE = 0.8f
        const val MAX_SCALE = 1.3f
        const val MAX_LETTER_SPACING_EM = 0.3f

        fun weight(w: Int): Int = ((w + 50) / 100 * 100).coerceIn(100, 900)
    }
}

/** The focused game's artwork drawn behind the home carousel; [overlay] and [fadeBottom] also apply on the game page. */
data class ThemeHero(
    /** 0..1 opacity of the artwork. */
    val opacity: Float? = null,
    val blurDp: Float? = null,
    /** 0..1 color saturation, 1 = full color. */
    val saturation: Float? = null,
    /** Darkening over the artwork: 0 = none, 1 = the app default, up to [MAX_OVERLAY]. */
    val overlay: Float? = null,
    /** 0..1 share of the artwork's height, at its bottom, over which it fades out. */
    val fadeBottom: Float? = null,
) {
    val isEmpty: Boolean get() = this == ThemeHero()

    fun overlay(top: ThemeHero): ThemeHero = ThemeHero(
        opacity = top.opacity ?: opacity,
        blurDp = top.blurDp ?: blurDp,
        saturation = top.saturation ?: saturation,
        overlay = top.overlay ?: overlay,
        fadeBottom = top.fadeBottom ?: fadeBottom,
    )

    companion object {
        const val MAX_OVERLAY = 1.5f
    }
}

/** Seconds for one pass of a gradient sliding back and forth across its element; null or 0 = still. */
data class ThemeMotion(
    /** Accent fills: switches when on, slider and progress fills, accent buttons. */
    val fillSlideSec: Float? = null,
    /** Gradient layers of side panels ([ThemeSpec.panelFill]). */
    val panelSlideSec: Float? = null,
    /** The line under the focused item ([ThemeFocus.underline]). */
    val underlineSlideSec: Float? = null,
) {
    val isEmpty: Boolean get() = this == ThemeMotion()

    fun overlay(top: ThemeMotion): ThemeMotion = ThemeMotion(
        fillSlideSec = top.fillSlideSec ?: fillSlideSec,
        panelSlideSec = top.panelSlideSec ?: panelSlideSec,
        underlineSlideSec = top.underlineSlideSec ?: underlineSlideSec,
    )

    companion object {
        const val MIN_SEC = 0.5f
        const val MAX_SEC = 60f
    }
}

/**
 * Linear gradient: [angleDeg] follows CSS (0 = to top, 90 = to right, 180 = to bottom).
 * With [radial] set it is a radial gradient and [angleDeg] is unused.
 */
data class ThemeGradient(
    val angleDeg: Float,
    val colors: List<Int>,
    /** 0..1 positions, same size as [colors], or empty for even spacing. */
    val stops: List<Float> = emptyList(),
    val radial: ThemeRadial? = null,
)

/** A frame image a cover is drawn inside. Insets are fractions of the frame's width/height. */
data class ThemeTileFrame(
    val image: String,
    val mask: String? = null,
    val insetLeft: Float = 0f,
    val insetTop: Float = 0f,
    val insetWidth: Float = 1f,
    val insetHeight: Float = 1f,
)

/** A mirror image under a cover. */
data class ThemeReflection(
    /** Unfocused covers. */
    val opacity: Float,
    val focusedOpacity: Float? = null,
    val blurDp: Float = 0f,
    /** Fraction of the mirrored cover that is visible before it fades out. */
    val fadeStop: Float = 1f,
    val gapDp: Float = 0f,
)

/**
 * Geometry of a CSS radial-gradient. The center is [centerX] x width + [offsetXDp] (same for y), so
 * `at 20% 0%` is (0.2, 0) and `at left 35px top -155px` is (0, 0) with offsets 35 and -155.
 * The ending shape is a circle or an ellipse sized by [extent], unless [radiusXDp] gives it in dp.
 */
data class ThemeRadial(
    val circle: Boolean = false,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    /** closest-side, closest-corner, farthest-side or farthest-corner. */
    val extent: String = FARTHEST_CORNER,
    val radiusXDp: Float? = null,
    /** Vertical radius of an ellipse; a circle uses [radiusXDp]. */
    val radiusYDp: Float? = null,
) {
    companion object {
        const val CLOSEST_SIDE = "closest-side"
        const val CLOSEST_CORNER = "closest-corner"
        const val FARTHEST_SIDE = "farthest-side"
        const val FARTHEST_CORNER = "farthest-corner"
        val EXTENTS = setOf(CLOSEST_SIDE, CLOSEST_CORNER, FARTHEST_SIDE, FARTHEST_CORNER)
    }
}

/** Layout values a theme can set. A null field keeps the app default. */
data class ThemeLayout(
    /** Multiplier on library tile size (1 = default). */
    val cardScale: Float? = null,
    /** "grid", "list", "carousel" or "row" (a shelf); used while the theme is on, the saved preference is not changed. */
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
    /** Corner radius of small game icons (not tiles). */
    val iconCornerRadiusDp: Float? = null,
    /** Blur of the title outline in dp; above 0 it is a soft shadow rather than a hard stroke. */
    val titleStrokeBlurDp: Float? = null,
    /** Outline of the line under a game title, as for the title. */
    val subtitleStrokeDp: Float? = null,
    val subtitleStrokeColor: Int? = null,
    val subtitleStrokeBlurDp: Float? = null,
    /** The first tile of the "row" layout as a wide landscape capsule (the default), or the same shape as the rest. */
    val shelfWideFirst: Boolean? = null,
    /** Button glyphs on the footer drawn inverted (dark discs with light marks), for a light footer. */
    val footerGlyphInvert: Boolean? = null,
    /**
     * Where the "row" layout's covers sit in the area between the bars: 0 hangs them from its top (as on Steam's home),
     * 1 sets them on its bottom, in between shares the room left; null = Steam's own.
     */
    val shelfRowAnchor: Float? = null,
    /** Steam CSS px (screen height / 533.33) the covers sit below the place [shelfRowAnchor] gives; null = Steam's own. */
    val shelfRowOffsetPx: Float? = null,
    /**
     * The "row" layout's game title against the covers, in Steam CSS px: negative = its box ends that far above the
     * covers, positive = its text starts that far under them; null = Steam's own, under the covers.
     */
    val shelfTitleOffsetPx: Float? = null,
    /** The "row" layout without the game title and the line under it. */
    val shelfHideTitle: Boolean? = null,
    /** The "row" layout without the line under the game title (status and play time). */
    val shelfHideSubtitle: Boolean? = null,
    /** Shear of the covers after the focused one; null = [cardTiltDeg], which then leans the covers before it. */
    val cardTiltAfterDeg: Float? = null,
    /** 3D turn of the covers about their vertical axis (CSS rotateY, degrees, CSS sign); before the focused one when [cardTurnAfterDeg] is set. */
    val cardTurnDeg: Float? = null,
    /** 3D turn of the covers after the focused one; null = [cardTurnDeg]. */
    val cardTurnAfterDeg: Float? = null,
    /** Viewer distance of the turn (CSS perspective()). */
    val cardTurnPerspectiveDp: Float? = null,
    /** The focused cover drawn without the tilt or turn of the others. */
    val cardFocusedFlat: Boolean? = null,
    /** Portrait covers of the home row, cycled by cover index. */
    val tileFrames: List<ThemeTileFrame>? = null,
    /** The wide (landscape) cover. */
    val tileFrameWide: ThemeTileFrame? = null,
    val coverReflection: ThemeReflection? = null,
    /** CSS filter brightness() on covers at rest. */
    val cardRestBrightness: Float? = null,
    /** CSS filter saturate() / grayscale() on covers at rest. */
    val cardRestSaturation: Float? = null,
    /** Scale of what holds or fills the focused cover, from the theme's focused rule; multiplies Steam's own lift. */
    val cardFocusedScale: Float? = null,
    val gridColumns: Int? = null,
    /** Width / height of library grid tiles. */
    val gridTileAspect: Float? = null,
    /** Covers of games that are not installed. */
    val uninstalledSaturation: Float? = null,
    val uninstalledOpacity: Float? = null,
    val hideTabCounts: Boolean? = null,
    /** Static image in place of the home artwork. */
    val heroImage: String? = null,
    /** [heroImage] repeated instead of covering. */
    val heroImageTile: Boolean? = null,
    /** CSS background-size of [heroImage] as a fraction of the width (1.5 = 150%). */
    val heroImageScale: Float? = null,
    /** Home artwork limited to the top fraction of the screen. */
    val heroHeightFraction: Float? = null,
    /** On-screen size of the focused portrait cover when the theme replaces Steam's own lift of it (1 = no lift). */
    val cardFocusPop: Float? = null,
    /** The same for the wide cover. */
    val cardFocusPopWide: Float? = null,
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
        iconCornerRadiusDp = top.iconCornerRadiusDp ?: iconCornerRadiusDp,
        titleStrokeBlurDp = top.titleStrokeBlurDp ?: titleStrokeBlurDp,
        subtitleStrokeDp = top.subtitleStrokeDp ?: subtitleStrokeDp,
        subtitleStrokeColor = top.subtitleStrokeColor ?: subtitleStrokeColor,
        subtitleStrokeBlurDp = top.subtitleStrokeBlurDp ?: subtitleStrokeBlurDp,
        shelfWideFirst = top.shelfWideFirst ?: shelfWideFirst,
        footerGlyphInvert = top.footerGlyphInvert ?: footerGlyphInvert,
        shelfRowAnchor = top.shelfRowAnchor ?: shelfRowAnchor,
        shelfRowOffsetPx = top.shelfRowOffsetPx ?: shelfRowOffsetPx,
        shelfTitleOffsetPx = top.shelfTitleOffsetPx ?: shelfTitleOffsetPx,
        shelfHideTitle = top.shelfHideTitle ?: shelfHideTitle,
        shelfHideSubtitle = top.shelfHideSubtitle ?: shelfHideSubtitle,
        cardTiltAfterDeg = top.cardTiltAfterDeg ?: cardTiltAfterDeg,
        cardTurnDeg = top.cardTurnDeg ?: cardTurnDeg,
        cardTurnAfterDeg = top.cardTurnAfterDeg ?: cardTurnAfterDeg,
        cardTurnPerspectiveDp = top.cardTurnPerspectiveDp ?: cardTurnPerspectiveDp,
        cardFocusedFlat = top.cardFocusedFlat ?: cardFocusedFlat,
        tileFrames = top.tileFrames ?: tileFrames,
        tileFrameWide = top.tileFrameWide ?: tileFrameWide,
        coverReflection = top.coverReflection ?: coverReflection,
        cardRestBrightness = top.cardRestBrightness ?: cardRestBrightness,
        cardRestSaturation = top.cardRestSaturation ?: cardRestSaturation,
        cardFocusedScale = top.cardFocusedScale ?: cardFocusedScale,
        gridColumns = top.gridColumns ?: gridColumns,
        gridTileAspect = top.gridTileAspect ?: gridTileAspect,
        uninstalledSaturation = top.uninstalledSaturation ?: uninstalledSaturation,
        uninstalledOpacity = top.uninstalledOpacity ?: uninstalledOpacity,
        hideTabCounts = top.hideTabCounts ?: hideTabCounts,
        heroImage = top.heroImage ?: heroImage,
        heroImageTile = top.heroImageTile ?: heroImageTile,
        heroImageScale = top.heroImageScale ?: heroImageScale,
        heroHeightFraction = top.heroHeightFraction ?: heroHeightFraction,
        cardFocusPop = top.cardFocusPop ?: cardFocusPop,
        cardFocusPopWide = top.cardFocusPopWide ?: cardFocusPopWide,
    )

    /** Image files the layout names, for checks that they still exist. */
    val files: List<String>
        get() = listOfNotNull(heroImage, tileFrameWide?.image, tileFrameWide?.mask) +
            tileFrames.orEmpty().flatMap { listOfNotNull(it.image, it.mask) }
}
