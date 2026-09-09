package app.gamenative.data

/**
 * One selectable Steam Families library copy for an app.
 */
data class PreferredCopyOption(
    val lenderSteamId: Long,
    val accountId: Int,
    val displayName: String,
    val isSelf: Boolean,
    val packageId: Int?,
    val ownedDlcCount: Int? = null,
)
