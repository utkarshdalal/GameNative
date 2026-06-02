package app.gamenative.html5.host

import app.gamenative.PluviaApp
import app.gamenative.html5.shim.Html5AchievementSeed
import app.gamenative.service.AchievementWatcher
import app.gamenative.service.SteamService

// mirrors XServerScreen.kt -- Steam-gated AchievementWatcher start
// on PluviaApp.achievementWatcher companion. shutdownEnvironment cleanup at PluviaApp.kt
// already covers HTML5; do NOT add achievementWatcher.stop() to WebViewScreen.onDispose
// lifecycle -- would double-stop on activity destroy).

// gseDirs / configDir reuse the seed result when present (avoids redundant Steam lookups);
// fall back to live SteamService companion calls when seed failed AND no prior on-disk state.
internal fun startAchievementWatcherForHtml5(
    context: android.content.Context,
    appId: Int,
    container: app.gamenative.runtime.WebViewContainer,
    seedResult: Html5AchievementSeed.SeedResult?,
) {
    val watchDirs = seedResult?.gseDirs ?: SteamService.getGseSaveDirs(context, appId)
    val configDirectory = seedResult?.configDir ?: SteamService.findSteamSettingsDir(context, appId)
    val achAppId = SteamService.cachedAchievementsAppId
    val displayNameMap = SteamService.cachedAchievements?.associate { ach ->
        ach.name to (
            ach.displayName?.get(container.language)
                ?: ach.displayName?.get("english")
                ?: ach.name
            )
    } ?: emptyMap()
    val iconUrlMap: Map<String, String?> = SteamService.cachedAchievements?.associate { ach ->
        ach.name to ach.icon?.let {
            "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/${achAppId ?: appId}/$it"
        }
    } ?: emptyMap()

    // defensive: stop any prior watcher leaking from a previous launch. shutdownEnvironment
    // should have nulled it out, but a recompose-without-shutdown path (rare) would leak.
    runCatching { PluviaApp.achievementWatcher?.stop() }

    PluviaApp.achievementWatcher = AchievementWatcher(
        appId = appId,
        watchDirs = watchDirs,
        displayNameMap = displayNameMap,
        iconUrlMap = iconUrlMap,
        configDirectory = configDirectory,
    ).also { it.start() }
}
