package app.gamenative.online

/**
 * Whether a game is capable of online multiplayer in principle
 * (store tags / features), independent of whether it works here yet.
 */
enum class OnlineCapability(val storageKey: String) {
    /** No online multiplayer signals found. */
    NONE("none"),

    /** Store/community signals say the title has online multiplayer. */
    HAS_ONLINE("has_online"),

    /** Explicitly marked as unsupported for online on this fork (e.g. anti-cheat). */
    BLOCKED("blocked"),
    ;

    val shouldOfferInstallChoice: Boolean
        get() = this == HAS_ONLINE

    companion object {
        fun fromStorageKey(value: String?): OnlineCapability {
            if (value.isNullOrBlank()) return NONE
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: NONE
        }
    }
}
