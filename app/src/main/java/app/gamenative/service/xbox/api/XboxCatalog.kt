// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class XboxImage(val purpose: String, val uri: String, val width: Int, val height: Int)

/** One package of a SKU as displaycatalog lists it. */
data class XboxCatalogPackage(
    val contentId: String?,
    val platforms: List<String>,
    val packageFormat: String?,
    val packageFamilyName: String?,
    val packageFullName: String?,
    val maxDownloadSizeInBytes: Long?,
)

/** How a product resolves to something packagespc can serve. */
sealed class XboxContentResolution {
    /** A Windows.Desktop package with this content id. */
    data class ContentId(val contentId: String, val desktopPackage: XboxCatalogPackage) : XboxContentResolution()

    /** No desktop package of its own, but its licensing names these sub-products (a bundle); pick one and look it up. */
    data class Bundle(val subProducts: List<String>) : XboxContentResolution()

    data object NoDesktopPackage : XboxContentResolution()

    data object MissingContentId : XboxContentResolution()
}

data class XboxProduct(
    val storeId: String,
    val title: String?,
    val publisher: String?,
    val productKind: String?,
    val images: List<XboxImage>,
    val packageFamilyName: String?,
    val packages: List<XboxCatalogPackage>,
    val resolution: XboxContentResolution,
) {
    val contentId: String? get() = (resolution as? XboxContentResolution.ContentId)?.contentId
    val isBundle: Boolean get() = resolution is XboxContentResolution.Bundle
    val subProducts: List<String> get() = (resolution as? XboxContentResolution.Bundle)?.subProducts.orEmpty()
    val desktopPackage: XboxCatalogPackage? get() = (resolution as? XboxContentResolution.ContentId)?.desktopPackage
    val downloadSizeInBytes: Long? get() = desktopPackage?.maxDownloadSizeInBytes

    /** True when the desktop package is an MSIXVC (GDK) package. */
    val isMsixvc: Boolean get() = desktopPackage?.packageFormat.equals("MSIXVC", ignoreCase = true)

    fun image(vararg purposes: String): XboxImage? =
        purposes.firstNotNullOfOrNull { p -> images.filter { it.purpose.equals(p, ignoreCase = true) }.maxByOrNull { it.width } }
}

/** Product lookup by Store id on displaycatalog (no sign-in needed). */
class XboxCatalog(private val client: OkHttpClient, private val config: XboxConfig) {
    suspend fun product(storeId: String, market: String? = null, languages: List<String>? = null): XboxProduct {
        val url = config.fill(
            XboxApiConfig.DISPLAY_CATALOG_URL,
            mapOf(
                "productId" to storeId,
                "market" to (market ?: config.value(XboxApiConfig.DISPLAY_CATALOG_MARKET)),
                "languages" to (languages ?: config.list(XboxApiConfig.DISPLAY_CATALOG_LANGUAGES)).joinToString(","),
            ),
        )
        val body = XboxHttp.send(client, Request.Builder().url(url).get().build()).body
        return parse(
            storeId,
            body,
            desktopPlatform = config.value(XboxApiConfig.DESKTOP_PLATFORM),
            bundlePrefix = config.value(XboxApiConfig.BUNDLE_ENTITLEMENT_PREFIX),
        )
    }

    companion object {
        fun parse(storeId: String, json: String, desktopPlatform: String, bundlePrefix: String): XboxProduct {
            val product = JSONObject(json).getJSONObject("Product")
            val availabilities = product.getJSONArray("DisplaySkuAvailabilities")
            val localized = product.optJSONArray("LocalizedProperties")?.optJSONObject(0)
            val packages = mutableListOf<XboxCatalogPackage>()
            for (i in 0 until availabilities.length()) {
                val sku = availabilities.getJSONObject(i).getJSONObject("Sku")
                val pkgs = sku.getJSONObject("Properties").getJSONArray("Packages")
                for (j in 0 until pkgs.length()) packages += parsePackage(pkgs.getJSONObject(j))
            }
            return XboxProduct(
                storeId = storeId,
                title = localized?.str("ProductTitle"),
                publisher = localized?.str("PublisherName"),
                productKind = product.str("ProductKind"),
                images = parseImages(localized?.optJSONArray("Images")),
                packageFamilyName = product.optJSONObject("Properties")?.str("PackageFamilyName"),
                packages = packages,
                resolution = resolve(availabilities, desktopPlatform, bundlePrefix),
            )
        }

        /**
         * The first package with a [desktopPlatform] dependency gives the content id. Until one is found,
         * satisfying entitlement keys of the form `{prefix}:{productId}:{sku}` collect the bundle's sub-products.
         */
        fun resolve(availabilities: JSONArray, desktopPlatform: String, bundlePrefix: String): XboxContentResolution {
            var found: XboxCatalogPackage? = null
            val subProducts = sortedSetOf<String>()
            outer@ for (i in 0 until availabilities.length()) {
                val availability = availabilities.getJSONObject(i)
                val pkgs = availability.getJSONObject("Sku").getJSONObject("Properties").getJSONArray("Packages")
                for (j in 0 until pkgs.length()) {
                    val pkg = parsePackage(pkgs.getJSONObject(j))
                    if (pkg.platforms.any { it == desktopPlatform }) {
                        found = pkg
                        break@outer
                    }
                }
                val inner = availability.getJSONArray("Availabilities")
                for (k in 0 until inner.length()) {
                    val licensing = inner.getJSONObject(k).optJSONObject("LicensingData") ?: continue
                    val satisfying = licensing.getJSONArray("SatisfyingEntitlementKeys")
                    for (s in 0 until satisfying.length()) {
                        val keys = satisfying.getJSONObject(s).getJSONArray("EntitlementKeys")
                        for (e in 0 until keys.length()) {
                            val parts = keys.getString(e).split(":")
                            if (parts.size == 3 && parts[0] == bundlePrefix) subProducts += parts[1]
                        }
                    }
                }
            }
            val pkg = found
            return when {
                pkg == null && subProducts.isNotEmpty() -> XboxContentResolution.Bundle(subProducts.toList())
                pkg == null -> XboxContentResolution.NoDesktopPackage
                pkg.contentId == null -> XboxContentResolution.MissingContentId
                else -> XboxContentResolution.ContentId(pkg.contentId, pkg)
            }
        }

        private fun parsePackage(p: JSONObject): XboxCatalogPackage {
            val deps = p.getJSONArray("PlatformDependencies")
            return XboxCatalogPackage(
                contentId = p.str("ContentId"),
                platforms = (0 until deps.length()).map { deps.getJSONObject(it).getString("PlatformName") },
                packageFormat = p.str("PackageFormat"),
                packageFamilyName = p.str("PackageFamilyName"),
                packageFullName = p.str("PackageFullName"),
                maxDownloadSizeInBytes = if (p.has("MaxDownloadSizeInBytes") && !p.isNull("MaxDownloadSizeInBytes")) p.optLong("MaxDownloadSizeInBytes") else null,
            )
        }

        private fun parseImages(images: JSONArray?): List<XboxImage> {
            if (images == null) return emptyList()
            return (0 until images.length()).mapNotNull { i ->
                val img = images.optJSONObject(i) ?: return@mapNotNull null
                val uri = img.str("Uri") ?: return@mapNotNull null
                XboxImage(
                    purpose = img.str("ImagePurpose") ?: "",
                    uri = if (uri.startsWith("//")) "https:$uri" else uri,
                    width = img.optInt("Width"),
                    height = img.optInt("Height"),
                )
            }
        }
    }
}
