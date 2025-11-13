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
    
    // Security limits
    private const val MAX_JSON_SIZE_BYTES = 1_048_576 // 1MB max
    private const val MAX_STRING_LENGTH = 10_000
    private const val MAX_EXEC_ARGS_LENGTH = 2000
    private const val MAX_ENV_VARS_LENGTH = 5000
    
    /**
     * Converts ContainerData to a temporary Container for export/share operations.
     * This helper ensures consistent container creation across export and share flows.
     */
    internal fun ContainerData.toTempContainer(): Container {
        return Container("temp").apply {
            name = this@toTempContainer.name
            screenSize = this@toTempContainer.screenSize
            envVars = this@toTempContainer.envVars
            graphicsDriver = this@toTempContainer.graphicsDriver
            graphicsDriverVersion = this@toTempContainer.graphicsDriverVersion
            graphicsDriverConfig = this@toTempContainer.graphicsDriverConfig
            dxWrapper = this@toTempContainer.dxwrapper
            dxWrapperConfig = this@toTempContainer.dxwrapperConfig
            audioDriver = this@toTempContainer.audioDriver
            winComponents = this@toTempContainer.wincomponents
            drives = this@toTempContainer.drives
            execArgs = this@toTempContainer.execArgs
            executablePath = this@toTempContainer.executablePath
            installPath = this@toTempContainer.installPath
            isShowFPS = this@toTempContainer.showFPS
            isLaunchRealSteam = this@toTempContainer.launchRealSteam
            isAllowSteamUpdates = this@toTempContainer.allowSteamUpdates
            steamType = this@toTempContainer.steamType
            setCPUList(this@toTempContainer.cpuList)
            setCPUListWoW64(this@toTempContainer.cpuListWoW64)
            isWoW64Mode = this@toTempContainer.wow64Mode
            startupSelection = this@toTempContainer.startupSelection
            box86Version = this@toTempContainer.box86Version
            box64Version = this@toTempContainer.box64Version
            box86Preset = this@toTempContainer.box86Preset
            box64Preset = this@toTempContainer.box64Preset
            desktopTheme = this@toTempContainer.desktopTheme
            language = this@toTempContainer.language
            containerVariant = this@toTempContainer.containerVariant
            wineVersion = this@toTempContainer.wineVersion
            emulator = this@toTempContainer.emulator
            fexCoreVersion = this@toTempContainer.fexcoreVersion
            dinputMapperType = this@toTempContainer.dinputMapperType
            isSdlControllerAPI = this@toTempContainer.sdlControllerAPI
            isDisableMouseInput = this@toTempContainer.disableMouseInput
            isTouchscreenMode = this@toTempContainer.touchscreenMode
            isUseDRI3 = this@toTempContainer.useDRI3
            isEmulateKeyboardMouse = this@toTempContainer.emulateKeyboardMouse
            isForceDlc = this@toTempContainer.forceDlc
            // Set controller emulation bindings if present
            if (this@toTempContainer.controllerEmulationBindings.isNotEmpty()) {
                putExtra("controllerEmulationBindings", this@toTempContainer.controllerEmulationBindings)
            }
            // FEXCore settings
            putExtra("fexcoreTSOMode", this@toTempContainer.fexcoreTSOMode)
            putExtra("fexcoreX87Mode", this@toTempContainer.fexcoreX87Mode)
            putExtra("fexcoreMultiBlock", this@toTempContainer.fexcoreMultiBlock)
            // Wine registry settings
            putExtra("renderer", this@toTempContainer.renderer)
            putExtra("csmt", this@toTempContainer.csmt.toString())
            putExtra("videoPciDeviceID", this@toTempContainer.videoPciDeviceID.toString())
            putExtra("offScreenRenderingMode", this@toTempContainer.offScreenRenderingMode)
            putExtra("strictShaderMath", this@toTempContainer.strictShaderMath.toString())
            putExtra("videoMemorySize", this@toTempContainer.videoMemorySize)
            putExtra("mouseWarpOverride", this@toTempContainer.mouseWarpOverride)
            putExtra("shaderBackend", this@toTempContainer.shaderBackend)
            putExtra("useGLSL", this@toTempContainer.useGLSL)
            putExtra("enableXInput", this@toTempContainer.enableXInput.toString())
            putExtra("enableDInput", this@toTempContainer.enableDInput.toString())
        }
    }
    
    /**
     * Sanitizes imported configuration data to prevent malicious inputs.
     * Validates paths, limits string lengths, and removes dangerous characters.
     */
    private fun sanitizeContainerData(data: ContainerData): ContainerData {
        return data.copy(
            // Sanitize string lengths
            name = data.name.take(100).sanitizeDisplayString(),
            screenSize = data.screenSize.take(50),
            envVars = data.envVars.take(MAX_ENV_VARS_LENGTH).sanitizeEnvVars(),
            graphicsDriver = data.graphicsDriver.take(100),
            graphicsDriverVersion = data.graphicsDriverVersion.take(100),
            graphicsDriverConfig = data.graphicsDriverConfig.take(500),
            dxwrapper = data.dxwrapper.take(100),
            dxwrapperConfig = data.dxwrapperConfig.take(500),
            audioDriver = data.audioDriver.take(100),
            wincomponents = data.wincomponents.take(500),
            
            // Sanitize potentially dangerous fields
            drives = data.drives.take(1000).sanitizeDrives(),
            execArgs = data.execArgs.take(MAX_EXEC_ARGS_LENGTH).sanitizeExecArgs(),
            executablePath = data.executablePath.take(500).sanitizePath(),
            installPath = data.installPath.take(500).sanitizePath(),
            
            // Sanitize other string fields
            steamType = data.steamType.take(50),
            cpuList = data.cpuList.take(200),
            cpuListWoW64 = data.cpuListWoW64.take(200),
            box86Version = data.box86Version.take(100),
            box64Version = data.box64Version.take(100),
            box86Preset = data.box86Preset.take(100),
            box64Preset = data.box64Preset.take(100),
            desktopTheme = data.desktopTheme.take(100),
            containerVariant = data.containerVariant.take(100),
            wineVersion = data.wineVersion.take(100),
            emulator = data.emulator.take(100),
            fexcoreVersion = data.fexcoreVersion.take(100),
            language = data.language.take(50),
            
            // Sanitize FEXCore settings
            fexcoreTSOMode = data.fexcoreTSOMode.take(50),
            fexcoreX87Mode = data.fexcoreX87Mode.take(50),
            fexcoreMultiBlock = data.fexcoreMultiBlock.take(50),
            
            // Sanitize Wine registry settings
            renderer = data.renderer.take(50),
            offScreenRenderingMode = data.offScreenRenderingMode.take(50),
            videoMemorySize = data.videoMemorySize.take(50),
            mouseWarpOverride = data.mouseWarpOverride.take(50),
            shaderBackend = data.shaderBackend.take(50),
            useGLSL = data.useGLSL.take(50),
            controllerEmulationBindings = data.controllerEmulationBindings.take(MAX_STRING_LENGTH),
        )
    }
    
    /**
     * Sanitizes display strings by removing control characters and nulls.
     */
    private fun String.sanitizeDisplayString(): String {
        return this.replace(Regex("[\u0000-\u001F\u007F]"), "").trim()
    }
    
    /**
     * Sanitizes environment variables to prevent injection.
     * Removes dangerous characters and validates format.
     */
    private fun String.sanitizeEnvVars(): String {
        // Split by semicolon, validate each env var
        return this.split(";")
            .filter { it.isNotBlank() }
            .map { envVar ->
                val trimmed = envVar.trim()
                // Only allow alphanumeric, underscore, equals, dash, dot, slash, colon
                // This prevents shell metacharacters and command substitution
                trimmed.replace(Regex("[^a-zA-Z0-9_=\\-./:]"), "")
            }
            .filter { it.contains("=") } // Must have key=value format
            .take(50) // Limit number of env vars
            .joinToString(";")
    }
    
    /**
     * Sanitizes executable arguments to prevent command injection.
     * Removes shell metacharacters and dangerous patterns.
     */
    private fun String.sanitizeExecArgs(): String {
        // Remove potentially dangerous shell metacharacters
        // Allow: alphanumeric, space, dash, equals, dot, slash, colon, underscore, comma
        // Disallow: pipes, redirects, command substitution, etc.
        return this.replace(Regex("[;|&$`<>(){}\\[\\]!*?~#]"), "")
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .trim()
    }
    
    /**
     * Sanitizes file paths to prevent path traversal.
     * Removes potentially dangerous path components.
     */
    private fun String.sanitizePath(): String {
        if (this.isBlank()) return ""
        
        // Remove null bytes
        var sanitized = this.replace("\u0000", "")
        
        // Remove dangerous path traversal patterns
        sanitized = sanitized.replace("..", "")
        
        // For Windows paths (C:, Z:, etc.), preserve the drive letter
        // But validate it's a reasonable path
        if (sanitized.matches(Regex("^[A-Za-z]:[/\\\\].*"))) {
            // Windows path - allow drive letter and forward/backslashes
            sanitized = sanitized.replace(Regex("[^a-zA-Z0-9_\\-./:\\\\() ]"), "")
        } else if (sanitized.startsWith("/")) {
            // Unix path
            sanitized = sanitized.replace(Regex("[^a-zA-Z0-9_\\-./() ]"), "")
        } else {
            // Relative path or invalid
            sanitized = sanitized.replace(Regex("[^a-zA-Z0-9_\\-./() ]"), "")
        }
        
        return sanitized.trim()
    }
    
    /**
     * Sanitizes drive mappings to prevent arbitrary filesystem access.
     * Only allows standard drive letter mappings.
     */
    private fun String.sanitizeDrives(): String {
        // Drive format is like: "Z:/home/user/.local/share/gamenative/drive"
        // Allow multiple drives separated by spaces
        return this.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { drive ->
                // Must match pattern: LETTER:PATH
                if (drive.matches(Regex("^[A-Za-z]:[/\\\\].*"))) {
                    // Sanitize the path part
                    val parts = drive.split(":", limit = 2)
                    if (parts.size == 2) {
                        val letter = parts[0].uppercase()
                        val path = parts[1].sanitizePath()
                        "$letter:$path"
                    } else {
                        ""
                    }
                } else {
                    ""
                }
            }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
    
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
            put("appVersion", app.gamenative.BuildConfig.VERSION_NAME)
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
                
                // FEXCore settings
                put("fexcoreTSOMode", container.getExtra("fexcoreTSOMode", "Fast"))
                put("fexcoreX87Mode", container.getExtra("fexcoreX87Mode", "Fast"))
                put("fexcoreMultiBlock", container.getExtra("fexcoreMultiBlock", "Disabled"))
                
                // Wine registry settings
                put("renderer", container.getExtra("renderer", "gl"))
                put("csmt", container.getExtra("csmt", "true").toBoolean())
                put("videoPciDeviceID", container.getExtra("videoPciDeviceID", "1728").toIntOrNull() ?: 1728)
                put("offScreenRenderingMode", container.getExtra("offScreenRenderingMode", "fbo"))
                put("strictShaderMath", container.getExtra("strictShaderMath", "true").toBoolean())
                put("videoMemorySize", container.getExtra("videoMemorySize", "2048"))
                put("mouseWarpOverride", container.getExtra("mouseWarpOverride", "disable"))
                put("shaderBackend", container.getExtra("shaderBackend", "glsl"))
                put("useGLSL", container.getExtra("useGLSL", "enabled"))
                put("enableXInput", container.getExtra("enableXInput", "true").toBoolean())
                put("enableDInput", container.getExtra("enableDInput", "true").toBoolean())
                
                // Paths
                put("executablePath", container.executablePath)
                put("installPath", container.installPath)
                
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
                // Security: Limit file size to prevent resource exhaustion
                val limitedStream = inputStream.buffered().apply {
                    // Read with size limit
                    mark(MAX_JSON_SIZE_BYTES + 1)
                }
                
                val content = limitedStream.readBytes(MAX_JSON_SIZE_BYTES)
                if (content.size > MAX_JSON_SIZE_BYTES) {
                    Timber.w("[ContainerConfigIO]: Import file exceeds size limit of $MAX_JSON_SIZE_BYTES bytes")
                    return null
                }
                
                String(content)
            } ?: return null
            
            importFromJson(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "[ContainerConfigIO]: Failed to import config from file")
            null
        }
    }
    
    /**
     * Helper to read bytes with a size limit.
     */
    private fun java.io.InputStream.readBytes(maxSize: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(8192)
        var total = 0
        var count: Int
        
        while (this.read(data).also { count = it } != -1) {
            total += count
            if (total > maxSize) {
                return buffer.toByteArray()
            }
            buffer.write(data, 0, count)
        }
        
        return buffer.toByteArray()
    }
    
    /**
     * Imports a container configuration from a JSON string.
     * 
     * @param jsonString JSON string containing the container config
     * @return ContainerData object if import succeeded, null otherwise
     */
    fun importFromJson(jsonString: String): ContainerData? {
        return try {
            // Security: Validate JSON size
            if (jsonString.length > MAX_JSON_SIZE_BYTES) {
                Timber.w("[ContainerConfigIO]: JSON exceeds size limit")
                return null
            }
            
            val rootJson = JSONObject(jsonString)
            
            // Validate version
            val version = rootJson.optInt("version", 0)
            if (version > CONTAINER_CONFIG_VERSION) {
                Timber.w("[ContainerConfigIO]: Config version $version is newer than supported version $CONTAINER_CONFIG_VERSION")
                // Continue anyway, may still be compatible
            }
            
            val config = rootJson.getJSONObject("config")
            
            // Parse into ContainerData
            val unsanitized = ContainerData(
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
            )
            
            // Security: Sanitize all inputs to prevent injection attacks
            val sanitized = sanitizeContainerData(unsanitized)
            
            Timber.i("[ContainerConfigIO]: Successfully imported and sanitized config '${sanitized.name}'")
            sanitized
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
            
            // Security: Validate input length
            if (trimmed.length > MAX_STRING_LENGTH) {
                Timber.w("[ContainerConfigIO]: Share code exceeds maximum length")
                return null
            }
            
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
            
            // Security: Validate compressed size to prevent decompression bombs
            if (compressed.size > MAX_JSON_SIZE_BYTES) {
                Timber.w("[ContainerConfigIO]: Compressed data exceeds size limit")
                return null
            }
            
            // Decompress with size limit
            val jsonString = ByteArrayInputStream(compressed).use { byteStream ->
                GZIPInputStream(byteStream).use { gzipStream ->
                    // Read with size limit to prevent decompression bombs
                    val decompressed = gzipStream.readBytes(MAX_JSON_SIZE_BYTES)
                    if (decompressed.size >= MAX_JSON_SIZE_BYTES) {
                        Timber.w("[ContainerConfigIO]: Decompressed data exceeds size limit")
                        return null
                    }
                    String(decompressed)
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
            
            // Security: Validate encoded data length
            if (encodedData.length > MAX_STRING_LENGTH) {
                Timber.w("[ContainerConfigIO]: Encoded data in deep link exceeds maximum length")
                return null
            }
            
            // Base64 decode
            val compressed = Base64.decode(encodedData, Base64.URL_SAFE or Base64.NO_WRAP)
            
            // Security: Validate compressed size
            if (compressed.size > MAX_JSON_SIZE_BYTES) {
                Timber.w("[ContainerConfigIO]: Compressed data in deep link exceeds size limit")
                return null
            }
            
            // Decompress with size limit
            val jsonString = ByteArrayInputStream(compressed).use { byteStream ->
                GZIPInputStream(byteStream).use { gzipStream ->
                    // Read with size limit to prevent decompression bombs
                    val decompressed = gzipStream.readBytes(MAX_JSON_SIZE_BYTES)
                    if (decompressed.size >= MAX_JSON_SIZE_BYTES) {
                        Timber.w("[ContainerConfigIO]: Decompressed data from deep link exceeds size limit")
                        return null
                    }
                    String(decompressed)
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
            put("graphicsDriverConfig", containerConfig.graphicsDriverConfig)
            put("dxwrapper", containerConfig.dxwrapper)
            put("dxwrapperConfig", containerConfig.dxwrapperConfig)
            put("audioDriver", containerConfig.audioDriver)
            put("wincomponents", containerConfig.wincomponents)
            put("drives", containerConfig.drives)
            put("execArgs", containerConfig.execArgs)
            put("executablePath", containerConfig.executablePath)
            put("installPath", containerConfig.installPath)
            put("showFPS", containerConfig.showFPS)
            put("launchRealSteam", containerConfig.launchRealSteam)
            put("allowSteamUpdates", containerConfig.allowSteamUpdates)
            put("steamType", containerConfig.steamType)
            put("cpuList", containerConfig.cpuList)
            put("cpuListWoW64", containerConfig.cpuListWoW64)
            put("wow64Mode", containerConfig.wow64Mode)
            put("startupSelection", containerConfig.startupSelection.toInt())
            put("box86Version", containerConfig.box86Version)
            put("box64Version", containerConfig.box64Version)
            put("box86Preset", containerConfig.box86Preset)
            put("box64Preset", containerConfig.box64Preset)
            put("desktopTheme", containerConfig.desktopTheme)
            put("language", containerConfig.language)
            put("containerVariant", containerConfig.containerVariant)
            put("wineVersion", containerConfig.wineVersion)
            put("emulator", containerConfig.emulator)
            put("fexcoreVersion", containerConfig.fexcoreVersion)
            put("dinputMapperType", containerConfig.dinputMapperType.toInt())
            put("sdlControllerAPI", containerConfig.sdlControllerAPI)
            put("disableMouseInput", containerConfig.disableMouseInput)
            put("touchscreenMode", containerConfig.touchscreenMode)
            put("useDRI3", containerConfig.useDRI3)
            put("emulateKeyboardMouse", containerConfig.emulateKeyboardMouse)
            put("forceDlc", containerConfig.forceDlc)
            
            // FEXCore settings
            put("fexcoreTSOMode", containerConfig.fexcoreTSOMode)
            put("fexcoreX87Mode", containerConfig.fexcoreX87Mode)
            put("fexcoreMultiBlock", containerConfig.fexcoreMultiBlock)
            
            // Wine registry settings
            put("renderer", containerConfig.renderer)
            put("csmt", containerConfig.csmt)
            put("videoPciDeviceID", containerConfig.videoPciDeviceID)
            put("offScreenRenderingMode", containerConfig.offScreenRenderingMode)
            put("strictShaderMath", containerConfig.strictShaderMath)
            put("videoMemorySize", containerConfig.videoMemorySize)
            put("mouseWarpOverride", containerConfig.mouseWarpOverride)
            put("shaderBackend", containerConfig.shaderBackend)
            put("useGLSL", containerConfig.useGLSL)
            put("enableXInput", containerConfig.enableXInput)
            put("enableDInput", containerConfig.enableDInput)
            
            // Controller emulation bindings if present
            if (containerConfig.controllerEmulationBindings.isNotEmpty()) {
                put("controllerEmulationBindings", JSONObject(containerConfig.controllerEmulationBindings))
            }
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
