package app.gamenative.workshop

internal object WorkshopOverrideIds {
    /**
     * Games that should use the standard ISteamUGC/mods.json path even when
     * mod directory detection finds a high-confidence filesystem mod folder.
     *
     * Add an app ID here when the game has a mods/ or similar folder for local
     * mods, but Workshop items are still discovered through Steam UGC metadata.
     */
    val forceStandardAppIds = setOf(
        211820, // Starbound
        1468810, // Tale of Immortal
        262060, // Darkest Dungeon
    )
}
