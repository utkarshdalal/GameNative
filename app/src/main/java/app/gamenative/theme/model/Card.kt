package app.gamenative.theme.model

/**
 * Defines how a single content item (e.g., a game card) is rendered.
 * Cards are used in grids and carousels to display individual items.
 */
data class Card(
    /** Unique identifier to reference this card from layouts. */
    val id: String,
    /** Canvas size for the card; layers are positioned within this box. */
    val canvas: DimSize,
    /** Ordered list of layers, back-to-front. */
    val layers: List<Layer> = emptyList(),
)

