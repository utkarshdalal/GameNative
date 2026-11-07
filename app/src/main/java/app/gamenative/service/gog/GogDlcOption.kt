package app.gamenative.service.gog

data class GogDlcOption(
    val productId: String,
    val title: String,
    val downloadSize: Long,   // compressed bytes of its language-filtered depots, 0 if unknown
    val installSize: Long,    // uncompressed bytes, 0 if unknown
    val installed: Boolean,
    val installable: Boolean, // false when the DLC has no depots in the base game's build (bonus-content-only)
)
