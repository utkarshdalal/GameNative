package app.gamenative.online

/**
 * Coarse reasons when online multiplayer fails. Kept user-facing and shareable.
 */
enum class OnlineFailReason(val storageKey: String, val label: String) {
    STEAM_AUTH("steam_auth", "Steam auth"),
    TICKET_DRM("ticket_drm", "Ticket / DRM"),
    DNS_CERTS("dns_certs", "DNS / certificates"),
    LOBBY_MATCHMAKING("lobby_matchmaking", "Lobby / matchmaking"),
    ANTI_CHEAT("anti_cheat", "Anti-cheat blocked"),
    UNKNOWN("unknown", "Unknown"),
    ;

    companion object {
        fun fromStorageKey(value: String?): OnlineFailReason? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) }
        }
    }
}
