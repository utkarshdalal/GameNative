package app.gamenative.online

/**
 * Install-time choice for how a game should be configured.
 *
 * Offline prioritizes boot speed and stability.
 * Online enables the Steam online client path (Bionic / Headless) and related settings.
 */
enum class InstallProfile(val storageKey: String) {
    OFFLINE("offline"),
    ONLINE("online"),
    ;

    companion object {
        fun fromStorageKey(value: String?): InstallProfile? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) }
        }

        /** Safer default when online status is unknown or flaky. */
        fun defaultFor(status: OnlineStatus): InstallProfile {
            return when (status) {
                OnlineStatus.ONLINE_OK -> ONLINE
                OnlineStatus.ONLINE_FLAKY,
                OnlineStatus.UNTESTED_ONLINE,
                OnlineStatus.OFFLINE_ONLY,
                OnlineStatus.ONLINE_BLOCKED,
                -> OFFLINE
            }
        }
    }
}
