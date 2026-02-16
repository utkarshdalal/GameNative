package app.gamenative.data.ludusavi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root manifest structure from Ludusavi
 * https://github.com/mtkennerly/ludusavi-manifest
 */
@Serializable
data class LudusaviManifest(
    @SerialName("games")
    val games: Map<String, LudusaviGame> = emptyMap(),
)

/**
 * Individual game entry in the manifest
 */
@Serializable
data class LudusaviGame(
    @SerialName("files")
    val files: Map<String, LudusaviFileEntry> = emptyMap(),
    
    @SerialName("registry")
    val registry: Map<String, LudusaviFileEntry> = emptyMap(),
    
    @SerialName("installDir")
    val installDir: Map<String, String> = emptyMap(),
    
    @SerialName("steam")
    val steam: LudusaviSteamInfo? = null,
    
    @SerialName("gog")
    val gog: LudusaviGogInfo? = null,
    
    @SerialName("cloud")
    val cloud: LudusaviCloudInfo? = null,
)

/**
 * File or path entry with optional conditions
 */
@Serializable
data class LudusaviFileEntry(
    @SerialName("tags")
    val tags: List<String> = emptyList(),
    
    @SerialName("when")
    val conditions: List<LudusaviCondition> = emptyList(),
)

/**
 * Conditional requirements for a file/path entry
 */
@Serializable
data class LudusaviCondition(
    @SerialName("os")
    val os: String? = null,
    
    @SerialName("store")
    val store: String? = null,
)

/**
 * Steam-specific metadata
 */
@Serializable
data class LudusaviSteamInfo(
    @SerialName("id")
    val id: Int,
)

/**
 * GOG-specific metadata
 */
@Serializable
data class LudusaviGogInfo(
    @SerialName("id")
    val id: Long,
)

/**
 * Cloud save support information
 */
@Serializable
data class LudusaviCloudInfo(
    @SerialName("steam")
    val steam: Boolean = false,
    
    @SerialName("gog")
    val gog: Boolean = false,
    
    @SerialName("epic")
    val epic: Boolean = false,
)
