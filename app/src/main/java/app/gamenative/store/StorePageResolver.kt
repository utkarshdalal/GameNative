package app.gamenative.store

import app.gamenative.R
import app.gamenative.data.GameSource
import java.util.Locale

object StorePageResolver {
    private const val STEAM_PACKAGE = "com.valvesoftware.android.steam.community"
    private val gogSlugPattern = Regex("[a-z0-9][a-z0-9_-]*")
    private val epicSlugPattern = Regex("[a-z0-9][a-z0-9_-]*")

    fun steam(appId: Int): StorePageTarget? {
        if (appId <= 0) return null

        val webUrl = "https://store.steampowered.com/app/$appId/"
        return StorePageTarget.NativeWithWebFallback(
            source = GameSource.STEAM,
            canonicalWebUrl = webUrl,
            labelRes = R.string.view_on_steam,
            nativeCandidates = listOf(
                NativeStoreTarget(
                    uri = "steam://store/$appId",
                    packageName = STEAM_PACKAGE,
                ),
                NativeStoreTarget(
                    uri = "steam://openurl/$webUrl",
                    packageName = STEAM_PACKAGE,
                ),
            ),
        )
    }

    fun gog(slug: String): StorePageTarget? {
        val normalizedSlug = slug.trim().lowercase(Locale.ROOT)
        if (!gogSlugPattern.matches(normalizedSlug)) return null

        return StorePageTarget.WebOnly(
            source = GameSource.GOG,
            canonicalWebUrl = "https://www.gog.com/en/game/$normalizedSlug",
            labelRes = R.string.view_on_gog,
        )
    }

    fun epic(slug: String): StorePageTarget? {
        val normalizedSlug = slug.trim().lowercase(Locale.ROOT)
        if (!epicSlugPattern.matches(normalizedSlug)) return null

        return StorePageTarget.WebOnly(
            source = GameSource.EPIC,
            canonicalWebUrl = "https://store.epicgames.com/p/$normalizedSlug",
            labelRes = R.string.view_on_epic_games_store,
        )
    }
}
