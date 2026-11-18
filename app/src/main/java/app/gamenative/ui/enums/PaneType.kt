package app.gamenative.ui.enums

enum class PaneType {
    UNDECIDED,
    LIST,
    GRID_HERO,
    GRID_CAPSULE,
    CAROUSEL,
    ROW,
    ;

    /** Drawn by the carousel pane: one horizontal row of covers over the focused game's artwork. */
    val isCarouselPane: Boolean get() = this == CAROUSEL || this == ROW
}
