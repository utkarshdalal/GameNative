package app.gamenative.data

/**
 * Save location template from GOG API (before path resolution)
 * @param name The name/identifier of the location (e.g., "__default", "saves", "configs")
 * @param location The path template with GOG variables (e.g., "<?INSTALL?>/saves")
 */
data class GOGCloudSavesLocationTemplate(
    val name: String,
    val location: String
)

/**
 * Resolved GOG cloud save location (after path resolution)
 * @param name The name/identifier of the save location
 * @param location The absolute path to the save directory on the device
 * @param clientId The game's GOG client ID used for cloud storage API
 * @param clientSecret The game's GOG client secret for authentication
 */
data class GOGCloudSavesLocation(
    val name: String,
    val location: String,
    val clientId: String,
    val clientSecret: String = ""  // Default empty for backward compatibility
)

