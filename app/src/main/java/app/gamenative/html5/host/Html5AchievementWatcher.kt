package app.gamenative.html5.host

import app.gamenative.PluviaApp
import app.gamenative.html5.shim.Html5AchievementSeed
import app.gamenative.service.AchievementWatcher
import app.gamenative.service.SteamService

// do NOT stop the watcher in WebViewScreen's onDispose: PluviaApp.shutdownEnvironment already
// stops it, so that would double-stop on activity destroy.
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

    // shutdownEnvironment should have nulled this, but a recompose without shutdown would leak it.
    runCatching { PluviaApp.achievementWatcher?.stop() }

    PluviaApp.achievementWatcher = AchievementWatcher(
        appId = appId,
        watchDirs = watchDirs,
        displayNameMap = displayNameMap,
        iconUrlMap = iconUrlMap,
        configDirectory = configDirectory,
        context = context,
    ).also { it.start() }
}
