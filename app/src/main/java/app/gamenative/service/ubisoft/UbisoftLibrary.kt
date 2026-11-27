package app.gamenative.service.ubisoft

/** An owned product as the ownership service lists it. The account's activation keys are never kept. */
data class UbisoftOwnedGame(
    val productId: Long,
    val uplayId: Long,
    val owned: Boolean,
    val productType: Int,
    val state: Int,
    val configuration: String,
    val latestManifest: String,
    val associations: List<Long>,
    val suspended: Boolean,
    val spaceId: String,
    val appId: String = "",
)

/** A game the store can install: one row of the user's Ubisoft library. */
data class UbisoftStoreGame(
    val productId: Long,
    val uplayId: Long,
    val title: String,
    val coverUrl: String,
    val iconUrl: String,
    val heroUrl: String,
    /** The current build; it changes when the game is updated. */
    val latestManifest: String,
    /** The product configuration, kept with the install for launches. */
    val configuration: String,
    /** The title on a transparent background; Ubisoft's landscape art carries no title. */
    val logoUrl: String = "",
    /** The application id the ownership service lists for the product; a configuration may carry its own. */
    val appId: String = "",
)

object UbisoftLibrary {
    private const val PRODUCT_GAME = 0
    private const val STATE_EXPIRED = 4

    /**
     * Owned, usable base games with a downloadable build that Ubisoft's own launcher installs.
     * Games another store starts (Steam copies) stay with that store.
     */
    fun storeGames(owned: List<UbisoftOwnedGame>, assetUrl: (String) -> String): List<UbisoftStoreGame> =
        owned.asSequence()
            .filter { it.owned && !it.suspended && it.productType == PRODUCT_GAME && it.state != STATE_EXPIRED && it.latestManifest.isNotBlank() }
            .mapNotNull { game ->
                val config = UbisoftProductConfig.parse(game.configuration)
                if (config?.thirdPartyPlatform != null) return@mapNotNull null
                UbisoftStoreGame(
                    productId = game.productId,
                    uplayId = game.uplayId,
                    title = config?.name ?: "Ubisoft ${game.productId}",
                    coverUrl = config?.thumbImage?.let(assetUrl).orEmpty(),
                    iconUrl = config?.iconImage?.takeUnless { it.endsWith(".ico", true) }?.let(assetUrl).orEmpty(),
                    heroUrl = config?.backgroundImage?.let(assetUrl).orEmpty(),
                    latestManifest = game.latestManifest.trim(),
                    configuration = game.configuration,
                    logoUrl = config?.logoImage?.let(assetUrl).orEmpty(),
                    appId = game.appId,
                )
            }
            .distinctBy { it.productId }
            .sortedBy { it.title.lowercase() }
            .toList()

    /** Products whose content chunks an install may include: the owned ones, add-ons too. */
    fun ownedIds(owned: List<UbisoftOwnedGame>): Set<Long> =
        owned.filter { it.owned && !it.suspended }.flatMap { listOf(it.productId, it.uplayId) }.toSet()
}
