package app.gamenative.data

import `in`.dragonbra.javasteam.enums.EPersonaState

/**
 * This class serves to update your steam's profile icon on the main library screen and settings dialog.
 */
data class SteamFriend(
    val avatarHash: String = "",
    val gameAppID: Int = 0,
    val gameName: String = "",
    val name: String = "",
    val state: EPersonaState = EPersonaState.Offline,
) {
    val isOnline: Boolean
        get() = (state.code() in 1..6)

    val isPlayingGame: Boolean
        get() = if (isOnline) gameAppID > 0 || gameName.isNotBlank() else false
}
