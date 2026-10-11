package app.gamenative.online

/**
 * Fork-level online metadata for a store game.
 * Detection and UI wiring land in later phases; this is the shared contract.
 */
data class OnlineGameInfo(
    val appId: String,
    val store: String = "steam",
    val capability: OnlineCapability = OnlineCapability.NONE,
    val status: OnlineStatus = OnlineStatus.OFFLINE_ONLY,
    val installProfile: InstallProfile? = null,
    val notes: String? = null,
    val lastVerifiedEpochMs: Long? = null,
) {
    val showInstallProfileChoice: Boolean
        get() = capability.shouldOfferInstallChoice && status.allowsOnlineInstall

    val effectiveInstallProfile: InstallProfile
        get() = installProfile ?: InstallProfile.defaultFor(status)

    companion object {
        fun offlineOnly(appId: String, store: String = "steam") = OnlineGameInfo(
            appId = appId,
            store = store,
            capability = OnlineCapability.NONE,
            status = OnlineStatus.OFFLINE_ONLY,
        )

        fun untestedOnline(appId: String, store: String = "steam") = OnlineGameInfo(
            appId = appId,
            store = store,
            capability = OnlineCapability.HAS_ONLINE,
            status = OnlineStatus.UNTESTED_ONLINE,
        )

        fun blocked(appId: String, store: String = "steam", notes: String? = null) = OnlineGameInfo(
            appId = appId,
            store = store,
            capability = OnlineCapability.BLOCKED,
            status = OnlineStatus.ONLINE_BLOCKED,
            notes = notes,
        )
    }
}
