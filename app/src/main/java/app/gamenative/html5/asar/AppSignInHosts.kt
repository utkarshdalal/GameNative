package app.gamenative.html5.asar

import app.gamenative.Constants
import app.gamenative.service.amazon.AmazonConstants
import app.gamenative.service.ea.EaConstants
import app.gamenative.service.epic.EpicConstants
import app.gamenative.service.gog.GOGConstants
import java.net.URI

// the sites the app signs the user in to in its own WebViews and OAuth flows. a game page never runs at one of
// these origins, even in a profile of its own. matched by registrable domain, so login./store./www. are covered.
internal object AppSignInHosts {
    private val SIGN_IN_URLS = listOf(
        Constants.Library.STORE_URL,
        Constants.Persona.PROFILE_URL,
        "https://login.steampowered.com",
        "https://signin.rockstargames.com",
        AmazonConstants.OPENID_RETURN_TO,
        GOGConstants.GOG_AUTH_URL,
        EpicConstants.EPIC_AUTH_BASE_URL,
        EaConstants.AUTH_ENDPOINT,
        "https://connect.ubisoft.com",
        "https://ubisoftconnect.com",
        "https://public-ubiservices.ubi.com",
    )

    val domains: Set<String> = SIGN_IN_URLS.mapNotNull { url -> URI(url).host?.let(::registrableDomain) }.toSet()

    fun covers(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return domains.any { h == it || h.endsWith(".$it") }
    }

    // every listed site sits directly under a one-label suffix (.com).
    private fun registrableDomain(host: String): String = host.lowercase().split('.').takeLast(2).joinToString(".")
}
