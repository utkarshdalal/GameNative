package app.gamenative.service.gog

/**
 * How a DLC reaches the disk: build depots, a bonus-content archive, an offline installer, or not
 * at all; [UNKNOWN] when its product downloads could not be fetched.
 */
enum class GogDlcKind { DEPOT, BONUS, INSTALLER, NONE, UNKNOWN }

data class GogDlcOption(
    val productId: String,
    val title: String,
    val downloadSize: Long,   // compressed bytes of its language-filtered depots, or of its bonus/installer files; 0 if unknown
    val installSize: Long,    // uncompressed bytes, 0 if unknown
    val installed: Boolean,
    val kind: GogDlcKind,
) {
    val installable: Boolean get() = kind != GogDlcKind.NONE && kind != GogDlcKind.UNKNOWN
}
