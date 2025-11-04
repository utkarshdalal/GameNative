package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.winlator.container.Container
import com.winlator.container.ContainerData
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

/**
 * Utilities for importing and exporting container configurations.
 * Supports both file-based import/export and Intent-based sharing.
 */
object ContainerConfigIO {

    private const val CONTAINER_CONFIG_VERSION = 1
    private const val MIME_TYPE_JSON = "application/json"
    
    /**
     * Exports a container's configuration to a JSON file.
     * 
     * @param context Android context
     * @param container The container to export
     * @param targetUri Document URI where to save the config (from SAF)
     * @return true if export succeeded, false otherwise
     */
    fun exportToFile(context: Context, container: Container, targetUri: Uri): Boolean {
        return try {
            val configJson = exportToJson(container)
            
            context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                outputStream.write(configJson.toByteArray())
                outputStream.flush()
            }
            
            Timber.i("[ContainerConfigIO]: Successfully exported config for container '${container.name}' to $targetUri")
            true
        } catch (e: Exception) {
            Timber.e(e, "[ContainerConfigIO]: Failed to export config to file")
            false
        }
    }
    
    /**
     * Exports a container's configuration to a JSON string.
     * 
     * @param container The container to export
     * @return JSON string representation of the container config
     */
    fun exportToJson(container: Container): String {
        val configData = JSONObject().apply {
            put("version", CONTAINER_CONFIG_VERSION)
            put("exportedFrom", "GameNative")
            put("containerName", container.name)
            put("timestamp", System.currentTimeMillis())
            
            // Core container settings
            val config = JSONObject().apply {
                put("screenSize", container.screenSize)
                put("envVars", container.envVars)
                put("graphicsDriver", container.graphicsDriver)
                put("graphicsDriverVersion", container.graphicsDriverVersion)
                put("graphicsDriverConfig", container.graphicsDriverConfig)
                put("dxwrapper", container.dxWrapper)
                put("dxwrapperConfig", container.dxWrapperConfig)
                put("audioDriver", container.audioDriver)
                put("wincomponents", container.winComponents)
                put("drives", container.drives)
                put("execArgs", container.execArgs)
                put("showFPS", container.isShowFPS)
                put("launchRealSteam", container.isLaunchRealSteam)
                put("allowSteamUpdates", container.isAllowSteamUpdates)
                put("steamType", container.steamType)
                put("cpuList", container.getCPUList(false))
                put("cpuListWoW64", container.getCPUListWoW64(false))
                put("wow64Mode", container.isWoW64Mode)
                put("startupSelection", container.startupSelection.toInt())
                put("box86Version", container.box86Version)
                put("box64Version", container.box64Version)
                put("box86Preset", container.box86Preset)
                put("box64Preset", container.box64Preset)
                put("desktopTheme", container.desktopTheme)
                put("language", container.language)
                put("containerVariant", container.containerVariant)
                put("wineVersion", container.wineVersion)
                put("emulator", container.emulator)
                put("fexcoreVersion", container.fexCoreVersion)
                put("inputType", container.inputType)
                put("dinputMapperType", container.dinputMapperType.toInt())
                put("sdlControllerAPI", container.isSdlControllerAPI)
                put("disableMouseInput", container.isDisableMouseInput)
                put("touchscreenMode", container.isTouchscreenMode)
                put("useDRI3", container.isUseDRI3)
                put("emulateKeyboardMouse", container.isEmulateKeyboardMouse)
                put("forceDlc", container.isForceDlc)
                
                // Additional Container-specific fields not in ContainerData
                put("primaryController", container.primaryController)
                put("lc_all", container.getExtra("lc_all", "en_US.utf8"))
                put("inputType", container.inputType)
                
                // Include MIDI sound font if set
                if (container.midiSoundFont.isNotEmpty()) {
                    put("midiSoundFont", container.midiSoundFont)
                }
                
                // Include controller mapping if set
                val controllerMapping = container.getExtra("controllerMapping", "")
                if (controllerMapping.isNotEmpty()) {
                    put("controllerMapping", controllerMapping)
                }
                
                // Include controller emulation bindings if set
                val emulationBindings = container.getExtra("controllerEmulationBindings")
                if (emulationBindings.isNotEmpty()) {
                    put("controllerEmulationBindings", JSONObject(emulationBindings))
                }
            }
            
            put("config", config)
        }
        
        return configData.toString(2) // Pretty print with 2-space indent
    }
    
    /**
     * Imports a container configuration from a JSON file.
     * 
     * @param context Android context
     * @param sourceUri Document URI to read from (from SAF)
     * @return ContainerData object if import succeeded, null otherwise
     */
    fun importFromFile(context: Context, sourceUri: Uri): ContainerData? {
        return try {
            val jsonString = context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: return null
            
            importFromJson(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "[ContainerConfigIO]: Failed to import config from file")
            null
        }
    }
    
    /**
     * Imports a container configuration from a JSON string.
     * 
     * @param jsonString JSON string containing the container config
     * @return ContainerData object if import succeeded, null otherwise
     */
    fun importFromJson(jsonString: String): ContainerData? {
        return try {
            val rootJson = JSONObject(jsonString)
            
            // Validate version
            val version = rootJson.optInt("version", 0)
            if (version > CONTAINER_CONFIG_VERSION) {
                Timber.w("[ContainerConfigIO]: Config version $version is newer than supported version $CONTAINER_CONFIG_VERSION")
                // Continue anyway, may still be compatible
            }
            
            val config = rootJson.getJSONObject("config")
            
            // Parse into ContainerData
            ContainerData(
                name = rootJson.optString("containerName", "Imported Config"),
                screenSize = config.optString("screenSize", Container.DEFAULT_SCREEN_SIZE),
                envVars = config.optString("envVars", Container.DEFAULT_ENV_VARS),
                graphicsDriver = config.optString("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER),
                graphicsDriverVersion = config.optString("graphicsDriverVersion", ""),
                graphicsDriverConfig = config.optString("graphicsDriverConfig", ""),
                dxwrapper = config.optString("dxwrapper", Container.DEFAULT_DXWRAPPER),
                dxwrapperConfig = config.optString("dxwrapperConfig", ""),
                audioDriver = config.optString("audioDriver", Container.DEFAULT_AUDIO_DRIVER),
                wincomponents = config.optString("wincomponents", Container.DEFAULT_WINCOMPONENTS),
                drives = config.optString("drives", Container.DEFAULT_DRIVES),
                execArgs = config.optString("execArgs", ""),
                showFPS = config.optBoolean("showFPS", false),
                launchRealSteam = config.optBoolean("launchRealSteam", false),
                allowSteamUpdates = config.optBoolean("allowSteamUpdates", false),
                steamType = config.optString("steamType", "normal"),
                cpuList = config.optString("cpuList", Container.getFallbackCPUList()),
                cpuListWoW64 = config.optString("cpuListWoW64", Container.getFallbackCPUListWoW64()),
                wow64Mode = config.optBoolean("wow64Mode", true),
                startupSelection = config.optInt("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL.toInt()).toByte(),
                box86Version = config.optString("box86Version", ""),
                box64Version = config.optString("box64Version", ""),
                box86Preset = config.optString("box86Preset", ""),
                box64Preset = config.optString("box64Preset", ""),
                desktopTheme = config.optString("desktopTheme", ""),
                containerVariant = config.optString("containerVariant", Container.DEFAULT_VARIANT),
                wineVersion = config.optString("wineVersion", Container.DEFAULT_WINE_VERSION),
                emulator = config.optString("emulator", Container.DEFAULT_EMULATOR),
                fexcoreVersion = config.optString("fexcoreVersion", ""),
                dinputMapperType = config.optInt("dinputMapperType", 1).toByte(),
                sdlControllerAPI = config.optBoolean("sdlControllerAPI", true),
                disableMouseInput = config.optBoolean("disableMouseInput", false),
                touchscreenMode = config.optBoolean("touchscreenMode", false),
                useDRI3 = config.optBoolean("useDRI3", false),
                emulateKeyboardMouse = config.optBoolean("emulateKeyboardMouse", false),
                forceDlc = config.optBoolean("forceDlc", false),
                language = config.optString("language", "english"),
                // FEXCore settings
                fexcoreTSOMode = config.optString("fexcoreTSOMode", "Fast"),
                fexcoreX87Mode = config.optString("fexcoreX87Mode", "Fast"),
                fexcoreMultiBlock = config.optString("fexcoreMultiBlock", "Disabled"),
                // Wine registry settings
                renderer = config.optString("renderer", "gl"),
                csmt = config.optBoolean("csmt", true),
                videoPciDeviceID = config.optInt("videoPciDeviceID", 1728),
                offScreenRenderingMode = config.optString("offScreenRenderingMode", "fbo"),
                strictShaderMath = config.optBoolean("strictShaderMath", true),
                videoMemorySize = config.optString("videoMemorySize", "2048"),
                mouseWarpOverride = config.optString("mouseWarpOverride", "disable"),
                shaderBackend = config.optString("shaderBackend", "glsl"),
                useGLSL = config.optString("useGLSL", "enabled"),
                enableXInput = config.optBoolean("enableXInput", true),
                enableDInput = config.optBoolean("enableDInput", true),
                executablePath = config.optString("executablePath", ""),
                installPath = config.optString("installPath", ""),
                controllerEmulationBindings = config.optString("controllerEmulationBindings", ""),
            ).also {
                Timber.i("[ContainerConfigIO]: Successfully imported config '${it.name}'")
            }
        } catch (e: Exception) {
            Timber.e(e, "[ContainerConfigIO]: Failed to parse container config JSON")
            null
        }
    }
    
    /**
     * Creates an Android Intent to share a container configuration.
     * This allows the config to be shared via Intent to other apps or the IntentLaunchManager.
     * 
     * @param container The container to share
     * @return Intent configured for sharing the container config
     */
    fun createShareIntent(container: Container): Intent {
        val configJson = exportToJson(container)
        
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, configJson)
            putExtra(Intent.EXTRA_SUBJECT, "GameNative Container Config: ${container.name}")
        }
    }
    
    /**
     * Creates a shareable message with a deep link to import the container config.
     * This allows users to share configs via messaging apps.
     * Includes both web link (clickable in Discord) and share code (for copy-paste).
     * 
     * @param container The container to share
     * @param gameName Optional game name to include in the message
     * @return Intent configured for sharing via Android's share sheet
     */
    fun createShareMessageIntent(container: Container, gameName: String = ""): Intent {
        val webLink = createWebLink(container)
        val configCode = createShareCode(container)
        val displayName = gameName.ifEmpty { container.name }
        
        val message = buildString {
            appendLine("🎮 GameNative Config: $displayName")
            appendLine()
            appendLine("Settings: ${container.graphicsDriver}, ${container.dxWrapper}, Wine ${container.wineVersion}")
            appendLine()
            appendLine("� Click to import:")
            appendLine(webLink)
            appendLine()
            appendLine("📋 Or paste this code in GameNative → Import → From Share Code:")
            appendLine(configCode)
        }
        
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra(Intent.EXTRA_SUBJECT, "GameNative Config: $displayName")
        }
    }
    
    /**
     * Creates a compact share code for the container config.
     * This is a base64-encoded, gzipped JSON that can be copied and pasted.
     * 
     * @param container The container to encode
     * @return Share code string (base64)
     */
    fun createShareCode(container: Container): String {
        val configJson = exportToJson(container)
        
        // Compress JSON to reduce size
        val compressed = ByteArrayOutputStream().use { byteStream ->
            GZIPOutputStream(byteStream).use { gzipStream ->
                gzipStream.write(configJson.toByteArray())
            }
            byteStream.toByteArray()
        }
        
        // Base64 encode for easy copy-paste
        val encoded = Base64.encodeToString(compressed, Base64.NO_WRAP)
        
        return "GN1:$encoded" // Prefix with version identifier
    }
    
    /**
     * Imports a container configuration from a share code.
     * Supports multiple formats:
     * - GN1:... (base64 share code)
     * - gamenative://config?data=... (deep link)
     * - https://gamenative.app/config?data=... (web link)
     * 
     * @param shareCode The share code string or URL
     * @return ContainerData if successful, null otherwise
     */
    fun importFromShareCode(shareCode: String): ContainerData? {
        return try {
            val trimmed = shareCode.trim()
            
            // Check if it's a URL (gamenative:// or https://)
            if (trimmed.startsWith("gamenative://") || trimmed.startsWith("https://")) {
                val uri = Uri.parse(trimmed)
                return importFromDeepLink(uri)
            }
            
            // Otherwise treat as base64 share code
            val code = if (trimmed.startsWith("GN1:")) {
                trimmed.substring(4)
            } else {
                trimmed
            }
            
            // Base64 decode
            val compressed = Base64.decode(code, Base64.NO_WRAP)
            
            // Decompress
            val jsonString = ByteArrayInputStream(compressed).use { byteStream ->
                GZIPInputStream(byteStream).use { gzipStream ->
                    gzipStream.bufferedReader().use { it.readText() }
                }
            }
            
            importFromJson(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "[ContainerConfigIO]: Failed to import from share code")
            null
        }
    }
    
    /**
     * Creates a web link that redirects to the deep link.
     * This makes the link clickable in messaging apps like Discord.
     * Format: https://gamenative.app/config?data=<base64-gzipped-json>
     * 
     * @param container The container to encode
     * @return Web link URL string
     */
    fun createWebLink(container: Container): String {
        val configJson = exportToJson(container)
        
        // Compress JSON to reduce link size
        val compressed = ByteArrayOutputStream().use { byteStream ->
            GZIPOutputStream(byteStream).use { gzipStream ->
                gzipStream.write(configJson.toByteArray())
            }
            byteStream.toByteArray()
        }
        
        // Base64 encode for URL safety
        val encoded = Base64.encodeToString(compressed, Base64.URL_SAFE or Base64.NO_WRAP)
        
        return "https://gamenative.app/config?data=$encoded"
    }
    
    /**
     * Creates a deep link URL for importing a container config.
     * Format: gamenative://config?data=<base64-gzipped-json>
     * 
     * @param container The container to encode
     * @return Deep link URL string
     */
    fun createDeepLink(container: Container): String {
        val configJson = exportToJson(container)
        
        // Compress JSON to reduce link size
        val compressed = ByteArrayOutputStream().use { byteStream ->
            GZIPOutputStream(byteStream).use { gzipStream ->
                gzipStream.write(configJson.toByteArray())
            }
            byteStream.toByteArray()
        }
        
        // Base64 encode for URL safety
        val encoded = Base64.encodeToString(compressed, Base64.URL_SAFE or Base64.NO_WRAP)
        
        return "gamenative://config?data=$encoded"
    }
    
    /**
     * Parses a deep link URL and extracts the container configuration.
     * 
     * @param deepLinkUri The deep link URI to parse
     * @return ContainerData if successful, null otherwise
     */
    fun importFromDeepLink(deepLinkUri: Uri): ContainerData? {
        return try {
            val encodedData = deepLinkUri.getQueryParameter("data") ?: return null
            
            // Base64 decode
            val compressed = Base64.decode(encodedData, Base64.URL_SAFE or Base64.NO_WRAP)
            
            // Decompress
            val jsonString = ByteArrayInputStream(compressed).use { byteStream ->
                GZIPInputStream(byteStream).use { gzipStream ->
                    gzipStream.bufferedReader().use { it.readText() }
                }
            }
            
            importFromJson(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "[ContainerConfigIO]: Failed to import from deep link")
            null
        }
    }
    
    /**
     * Creates an Intent that can be used to launch a game with a specific container configuration.
     * This integrates with the IntentLaunchManager system.
     * 
     * @param context Android context
     * @param gameId The Steam game ID
     * @param containerConfig The container configuration to use
     * @return Intent configured for launching the game with the config
     */
    fun createLaunchIntent(context: Context, gameId: Int, containerConfig: ContainerData): Intent {
        // Convert ContainerData to JSON for the intent
        val configJson = JSONObject().apply {
            put("screenSize", containerConfig.screenSize)
            put("envVars", containerConfig.envVars)
            put("graphicsDriver", containerConfig.graphicsDriver)
            put("graphicsDriverVersion", containerConfig.graphicsDriverVersion)
            put("dxwrapper", containerConfig.dxwrapper)
            put("dxwrapperConfig", containerConfig.dxwrapperConfig)
            put("audioDriver", containerConfig.audioDriver)
            put("wincomponents", containerConfig.wincomponents)
            put("drives", containerConfig.drives)
            put("execArgs", containerConfig.execArgs)
            put("showFPS", containerConfig.showFPS)
            put("launchRealSteam", containerConfig.launchRealSteam)
            put("cpuList", containerConfig.cpuList)
            put("cpuListWoW64", containerConfig.cpuListWoW64)
            put("wow64Mode", containerConfig.wow64Mode)
            put("startupSelection", containerConfig.startupSelection.toInt())
            put("box86Version", containerConfig.box86Version)
            put("box64Version", containerConfig.box64Version)
            put("box86Preset", containerConfig.box86Preset)
            put("box64Preset", containerConfig.box64Preset)
        }.toString()
        
        return Intent("app.gamenative.LAUNCH_GAME").apply {
            setPackage(context.packageName)
            putExtra("app_id", gameId)
            putExtra("container_config", configJson)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
    
    /**
     * Generates a filename for exporting a container config.
     * 
     * @param name The name to use (e.g., game name or container name)
     * @return Suggested filename with readable timestamp
     */
    fun generateExportFilename(name: String): String {
        val sanitized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
        val timestamp = dateFormat.format(java.util.Date())
        return "${sanitized}_${timestamp}.json"
    }
}
