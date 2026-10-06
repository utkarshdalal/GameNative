package app.gamenative.utils

import app.gamenative.data.LaunchInfo
import app.gamenative.service.SteamService
import com.winlator.container.Container
import java.util.concurrent.ConcurrentHashMap

enum class LaunchMode(val savedChoiceExtra: String) {
    FLAT("steamLaunchOptionFlat"),
    VR("steamLaunchOptionVr"),
}

/**
 * Steam's own launch options for a game, split by [LaunchMode]: which ones to offer, the one
 * picked at Play for the current launch, and the one the player asked to remember.
 */
object SteamLaunchOptions {
    private val sessionChoices = ConcurrentHashMap<String, String>()

    fun candidates(gameId: Int, mode: LaunchMode): List<LaunchInfo> =
        candidates(SteamService.getWindowsLaunchInfos(gameId), mode)

    // Some games list the same option several times; keep one of each.
    fun candidates(infos: List<LaunchInfo>, mode: LaunchMode): List<LaunchInfo> = when (mode) {
        LaunchMode.VR -> infos.filter { it.isVr }
        LaunchMode.FLAT -> infos.filter { !it.isVr && !it.isOculusOnly }
    }.distinctBy { key(it) }

    fun key(info: LaunchInfo): String = "${info.executable}|${info.arguments}"

    fun savedChoice(container: Container, mode: LaunchMode): String = container.getExtra(mode.savedChoiceExtra)

    /** Options to ask about at Play, empty when there is nothing to choose. */
    fun pendingChoice(container: Container, gameId: Int, mode: LaunchMode): List<LaunchInfo> {
        val options = candidates(gameId, mode)
        if (options.size < 2) return emptyList()
        val saved = savedChoice(container, mode)
        return if (options.any { key(it) == saved }) emptyList() else options
    }

    fun choose(containerId: String, mode: LaunchMode, info: LaunchInfo) {
        sessionChoices["$containerId:$mode"] = key(info)
    }

    fun hasSessionChoice(containerId: String, mode: LaunchMode): Boolean = sessionChoices.containsKey("$containerId:$mode")

    fun clearSessionChoice(containerId: String) {
        LaunchMode.entries.forEach { sessionChoices.remove("$containerId:$it") }
    }

    fun remember(container: Container, mode: LaunchMode, info: LaunchInfo) {
        container.putExtra(mode.savedChoiceExtra, key(info))
        container.saveData()
    }

    fun resolve(container: Container, gameId: Int, mode: LaunchMode): LaunchInfo? {
        val infos = SteamService.getWindowsLaunchInfos(gameId)
        val options = candidates(infos, mode)
        val wanted = sessionChoices["${container.id}:$mode"] ?: savedChoice(container, mode)
        return options.firstOrNull { key(it) == wanted } ?: options.firstOrNull() ?: infos.firstOrNull()
    }
}
