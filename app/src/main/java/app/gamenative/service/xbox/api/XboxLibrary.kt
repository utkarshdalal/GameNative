package app.gamenative.service.xbox.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient

/**
 * What can be listed today: products the caller already knows by Store id (added by the user or
 * found installed), looked up on displaycatalog, plus the Game Pass PC package family names to mark
 * which of them are on Game Pass. There is no owned-titles listing here.
 */
class XboxLibrary(
    client: OkHttpClient,
    config: XboxConfig,
    val catalog: XboxCatalog = XboxCatalog(client, config),
    val gamePass: GamePassCatalog = GamePassCatalog(client, config),
) {
    /** Products for [storeIds], in order; ids that fail to resolve are left out and reported through [onError]. */
    suspend fun products(
        storeIds: List<String>,
        market: String? = null,
        onError: (String, Throwable) -> Unit = { _, _ -> },
    ): List<XboxProduct> = coroutineScope {
        val gate = Semaphore(4)
        storeIds.distinct().map { id ->
            async {
                gate.withPermit {
                    runCatching { catalog.product(id, market) }.onFailure { onError(id, it) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** Package family names currently on PC Game Pass. */
    suspend fun gamePassFamilies(): Set<String> = gamePass.pcPackageFamilyNames()?.packageFamilyNames.orEmpty().toSet()

    companion object {
        fun isOnGamePass(product: XboxProduct, families: Set<String>): Boolean {
            val names = listOfNotNull(product.packageFamilyName, product.desktopPackage?.packageFamilyName)
            return names.any { it in families }
        }
    }
}
