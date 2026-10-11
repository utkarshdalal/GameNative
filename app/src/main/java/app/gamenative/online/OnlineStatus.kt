package app.gamenative.online

/**
 * Community / fork verdict for multiplayer on this stack.
 * Shown as library badges; never invents success for blocked titles.
 */
enum class OnlineStatus(val storageKey: String, val badgeLabel: String) {
    OFFLINE_ONLY("offline_only", "OFFLINE ONLY"),
    ONLINE_OK("online_ok", "ONLINE OK"),
    ONLINE_FLAKY("online_flaky", "ONLINE FLAKY"),
    ONLINE_BLOCKED("online_blocked", "ONLINE BLOCKED"),
    UNTESTED_ONLINE("untested_online", "UNTESTED ONLINE"),
    ;

    val allowsOnlineInstall: Boolean
        get() = this != ONLINE_BLOCKED && this != OFFLINE_ONLY

    companion object {
        fun fromStorageKey(value: String?): OnlineStatus? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) }
        }
    }
}
