package app.gamenative.service.xbox.api

/**
 * Key names for the Xbox service values carried in the helper archive's xboxhost.properties.
 * The API classes take a [XboxConfig] so they stay free of Android types; the app binds it to
 * XboxHelperConfig.value(filesDir, key).
 */
object XboxApiConfig {
    const val USER_AUTH_URL = "xbox.api.userAuthUrl"
    const val DEVICE_AUTH_URL = "xbox.api.deviceAuthUrl"
    const val XSTS_URL = "xbox.api.xstsUrl"
    const val SISU_AUTHORIZE_URL = "xbox.api.sisuAuthorizeUrl"
    const val TITLE_ENDPOINTS_URL = "xbox.api.titleEndpointsUrl"
    const val AUTH_CONTRACT_VERSION = "xbox.api.authContractVersion"
    const val AUTH_RELYING_PARTY = "xbox.api.authRelyingParty"
    const val USER_AUTH_SITE_NAME = "xbox.api.userAuthSiteName"
    const val SANDBOX = "xbox.api.sandbox"
    const val SISU_CLIENT_VERSION = "xbox.api.sisuClientVersion"
    const val UPDATE_RELYING_PARTY = "xbox.api.updateRelyingParty"

    const val DISPLAY_CATALOG_URL = "xbox.api.displayCatalogUrl"
    const val DISPLAY_CATALOG_MARKET = "xbox.api.displayCatalogMarket"
    const val DISPLAY_CATALOG_LANGUAGES = "xbox.api.displayCatalogLanguages"
    const val DESKTOP_PLATFORM = "xbox.api.desktopPlatform"
    const val BUNDLE_ENTITLEMENT_PREFIX = "xbox.api.bundleEntitlementPrefix"

    const val PACKAGES_URL = "xbox.api.packagesUrl"
    const val PACKAGES_CONTRACT_VERSION = "xbox.api.packagesContractVersion"

    const val GAME_PASS_SUBSCRIPTIONS_URL = "xbox.api.gamePassSubscriptionsUrl"
    const val GAME_PASS_PFNS_URL = "xbox.api.gamePassPfnsUrl"
    const val GAME_PASS_SUBSCRIPTION_IDS = "xbox.api.gamePassSubscriptionIds"
}

/** Reads one helper-archive value; throws when the key is missing. */
fun interface XboxConfig {
    fun value(key: String): String

    fun fill(key: String, values: Map<String, String>): String =
        values.entries.fold(value(key)) { acc, (name, v) -> acc.replace("{$name}", v) }

    fun list(key: String): List<String> = value(key).split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
