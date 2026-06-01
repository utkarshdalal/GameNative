package app.gamenative.workshop

internal object WorkshopOverrideIds {
    /**
     * Games that should use the standard ISteamUGC/mods.json path even when
     * mod directory detection finds a high-confidence filesystem mod folder.
     */
    val forceStandardAppIds = setOf(
        211820, // Starbound
        1468810, // Tale of Immortal
        262060, // Darkest Dungeon
    )
}
