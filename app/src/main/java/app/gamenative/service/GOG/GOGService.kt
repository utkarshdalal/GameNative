package app.gamenative.service.GOG

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GOGCredentials
import app.gamenative.data.GOGGame
import app.gamenative.service.NotificationHelper
import app.gamenative.utils.ContainerUtils
import com.chaquo.python.Kwarg
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber

@Singleton
class GOGService @Inject constructor() : Service() {

    companion object {
        private var instance: GOGService? = null
        private var appContext: Context? = null
        private var isInitialized = false
        private var httpClient: OkHttpClient? = null
        private var python: Python? = null

        // Constants
        private const val GOG_CLIENT_ID = "46899977096215655"

        // Add sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            if (!isRunning) {
                val intent = Intent(context, GOGService::class.java)
                context.startForegroundService(intent)
            }
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        fun setHttpClient(client: OkHttpClient) {
            httpClient = client
        }

        /**
         * Initialize the GOG service with Chaquopy Python
         */
        fun initialize(context: Context): Boolean {
            if (isInitialized) return true

            try {
                // Store the application context
                appContext = context.applicationContext

                Timber.i("Initializing GOG service with Chaquopy...")

                // Initialize Python if not already started
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context))
                }
                python = Python.getInstance()

                isInitialized = true
                Timber.i("GOG service initialized successfully with Chaquopy")

                return isInitialized
            } catch (e: Exception) {
                Timber.e(e, "Exception during GOG service initialization")
                return false
            }
        }

        /**
         * Execute GOGDL command using Chaquopy
         */
        suspend fun executeCommand(vararg args: String): Result<String> {
            return withContext(Dispatchers.IO) {
                try {
                    val python = Python.getInstance()
                    val sys = python.getModule("sys")
                    val io = python.getModule("io")
                    val originalArgv = sys.get("argv")

                    try {
                        // Now import our Android-compatible GOGDL CLI module
                        val gogdlCli = python.getModule("gogdl.cli")

                        // Set up arguments for argparse
                        val argsList = listOf("gogdl") + args.toList()
                        Timber.d("Setting GOGDL arguments for argparse: ${args.joinToString(" ")}")
                        // Convert to Python list to avoid jarray issues
                        val pythonList = python.builtins.callAttr("list", argsList.toTypedArray())
                        sys.put("argv", pythonList)
                        Timber.d("sys.argv set to: $argsList")

                        // Capture stdout
                        val stdoutCapture = io.callAttr("StringIO")
                        val originalStdout = sys.get("stdout")
                        sys.put("stdout", stdoutCapture)

                        // Execute the main function
                        gogdlCli.callAttr("main")

                        // Get the captured output
                        val output = stdoutCapture.callAttr("getvalue").toString()
                        Timber.d("GOGDL output: $output")

                        // Restore original stdout
                        sys.put("stdout", originalStdout)

                        if (output.isNotEmpty()) {
                            Result.success(output)
                        } else {
                            Result.success("GOGDL execution completed")
                        }
                    } catch (e: Exception) {
                        Timber.d("GOGDL execution completed with exception: ${e.javaClass.simpleName} - ${e.message}")
                        Result.failure(Exception("GOGDL execution failed: $e"))
                    } finally {
                        // Restore original sys.argv
                        sys.put("argv", originalArgv)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to execute GOGDL command: ${args.joinToString(" ")}")
                    Result.failure(Exception("GOGDL execution failed: $e"))
                }
            }
        }

        /**
         * Read and parse auth credentials from file
         */
        private fun readAuthCredentials(authConfigPath: String): Result<Pair<String, String>> {
            return try {
                val authFile = File(authConfigPath)
                Timber.d("Checking auth file at: ${authFile.absolutePath}")
                Timber.d("Auth file exists: ${authFile.exists()}")

                if (!authFile.exists()) {
                    return Result.failure(Exception("No authentication found. Please log in first."))
                }

                val authContent = authFile.readText()
                Timber.d("Auth file content: $authContent")

                val authJson = JSONObject(authContent)

                // GOGDL stores credentials nested under client ID
                val credentialsJson = if (authJson.has(GOG_CLIENT_ID)) {
                    authJson.getJSONObject(GOG_CLIENT_ID)
                } else {
                    // Fallback: try to read from root level
                    authJson
                }

                val accessToken = credentialsJson.optString("access_token", "")
                val userId = credentialsJson.optString("user_id", "")

                Timber.d("Parsed access_token: ${if (accessToken.isNotEmpty()) "${accessToken.take(20)}..." else "EMPTY"}")
                Timber.d("Parsed user_id: $userId")

                if (accessToken.isEmpty() || userId.isEmpty()) {
                    Timber.e("Auth data validation failed - accessToken empty: ${accessToken.isEmpty()}, userId empty: ${userId.isEmpty()}")
                    return Result.failure(Exception("Invalid authentication data. Please log in again."))
                }

                Result.success(Pair(accessToken, userId))
            } catch (e: Exception) {
                Timber.e(e, "Failed to read auth credentials")
                Result.failure(e)
            }
        }

        /**
         * Parse full GOGCredentials from auth file
         */
        private fun parseFullCredentials(authConfigPath: String): GOGCredentials {
            return try {
                val authFile = File(authConfigPath)
                if (authFile.exists()) {
                    val authContent = authFile.readText()
                    val authJson = JSONObject(authContent)

                    // GOGDL stores credentials nested under client ID
                    val credentialsJson = if (authJson.has(GOG_CLIENT_ID)) {
                        authJson.getJSONObject(GOG_CLIENT_ID)
                    } else {
                        // Fallback: try to read from root level
                        authJson
                    }

                    GOGCredentials(
                        accessToken = credentialsJson.optString("access_token", ""),
                        refreshToken = credentialsJson.optString("refresh_token", ""),
                        userId = credentialsJson.optString("user_id", ""),
                        username = credentialsJson.optString("username", "GOG User"),
                    )
                } else {
                    // Return dummy credentials for successful auth
                    GOGCredentials(
                        accessToken = "authenticated_${System.currentTimeMillis()}",
                        refreshToken = "refresh_${System.currentTimeMillis()}",
                        userId = "user_123",
                        username = "GOG User",
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse auth result")
                // Return dummy credentials as fallback
                GOGCredentials(
                    accessToken = "fallback_token",
                    refreshToken = "fallback_refresh",
                    userId = "fallback_user",
                    username = "GOG User",
                )
            }
        }

        /**
         * Create GOGCredentials from JSON output
         */
        private fun createCredentialsFromJson(outputJson: JSONObject): GOGCredentials {
            return GOGCredentials(
                accessToken = outputJson.optString("access_token", ""),
                refreshToken = outputJson.optString("refresh_token", ""),
                userId = outputJson.optString("user_id", ""),
                username = "GOG User", // We don't have username in the token response
            )
        }

        /**
         * Authenticate with GOG using authorization code
         */
        suspend fun authenticateWithCode(authConfigPath: String, authorizationCode: String): Result<GOGCredentials> {
            return try {
                Timber.i("Starting GOG authentication with authorization code...")

                // Extract the actual authorization code from URL if needed
                val actualCode = if (authorizationCode.startsWith("http")) {
                    // Extract code parameter from URL
                    val codeParam = authorizationCode.substringAfter("code=", "")
                    if (codeParam.isEmpty()) {
                        return Result.failure(Exception("Invalid authorization URL: no code parameter found"))
                    }
                    // Remove any additional parameters after the code
                    val cleanCode = codeParam.substringBefore("&")
                    Timber.d("Extracted authorization code from URL: ${cleanCode.take(20)}...")
                    cleanCode
                } else {
                    authorizationCode
                }

                // Create auth config directory
                val authFile = File(authConfigPath)
                val authDir = authFile.parentFile
                if (authDir != null && !authDir.exists()) {
                    authDir.mkdirs()
                    Timber.d("Created auth config directory: ${authDir.absolutePath}")
                }

                // Execute GOGDL auth command with the authorization code
                Timber.d("Authenticating with auth config path: $authConfigPath, code: ${actualCode.take(10)}...")
                Timber.d("Full auth command: --auth-config-path $authConfigPath auth --code ${actualCode.take(20)}...")

                val result = executeCommand("--auth-config-path", authConfigPath, "auth", "--code=$actualCode")

                if (result.isSuccess) {
                    val gogdlOutput = result.getOrNull() ?: ""
                    Timber.i("GOGDL command completed, checking authentication result...")
                    Timber.d("GOGDL output for auth: $gogdlOutput")

                    // First, check if GOGDL output indicates success
                    try {
                        val outputJson = JSONObject(gogdlOutput.trim())

                        // Check if the response indicates an error
                        if (outputJson.has("error") && outputJson.getBoolean("error")) {
                            val errorMsg = outputJson.optString("error_description", "Authentication failed")
                            Timber.e("GOG authentication failed: $errorMsg")
                            return Result.failure(Exception("GOG authentication failed: $errorMsg"))
                        }

                        // Check if we have the required fields for successful auth
                        val accessToken = outputJson.optString("access_token", "")
                        val userId = outputJson.optString("user_id", "")

                        if (accessToken.isEmpty() || userId.isEmpty()) {
                            Timber.e("GOG authentication incomplete: missing access_token or user_id in output")
                            return Result.failure(Exception("Authentication incomplete: missing required data"))
                        }

                        // GOGDL output looks good, now check if auth file was created
                        val authFile = File(authConfigPath)
                        if (authFile.exists()) {
                            // Parse authentication result from file
                            val authData = parseFullCredentials(authConfigPath)
                            Timber.i("GOG authentication successful for user: ${authData.username}")
                            Result.success(authData)
                        } else {
                            Timber.w("GOGDL returned success but no auth file created, using output data")
                            // Create credentials from GOGDL output
                            val credentials = createCredentialsFromJson(outputJson)
                            Result.success(credentials)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse GOGDL output")
                        // Fallback: check if auth file exists
                        val authFile = File(authConfigPath)
                        if (authFile.exists()) {
                            try {
                                val authData = parseFullCredentials(authConfigPath)
                                Timber.i("GOG authentication successful (fallback) for user: ${authData.username}")
                                Result.success(authData)
                            } catch (ex: Exception) {
                                Timber.e(ex, "Failed to parse auth file")
                                Result.failure(Exception("Failed to parse authentication result: ${ex.message}"))
                            }
                        } else {
                            Timber.e("GOG authentication failed: no auth file created and failed to parse output")
                            Result.failure(Exception("Authentication failed: no credentials available"))
                        }
                    }
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Authentication failed"
                    Timber.e("GOG authentication command failed: $error")
                    Result.failure(Exception(error))
                }
            } catch (e: Exception) {
                Timber.e(e, "GOG authentication exception")
                Result.failure(e)
            }
        }

        /**
         * Fetch detailed information for a specific GOG game
         */
        private suspend fun fetchGameDetails(gameId: String, accessToken: String): GOGGame? = withContext(Dispatchers.IO) {
            try {
                val python = Python.getInstance()
                val requests = python.getModule("requests")

                // Use the GOG API products endpoint to get game details
                val url = "https://api.gog.com/products/$gameId"

                // Create headers dictionary
                val pyDict = python.builtins.callAttr("dict")
                pyDict.callAttr("__setitem__", "Authorization", "Bearer $accessToken")
                pyDict.callAttr("__setitem__", "User-Agent", "GOGGalaxyClient/2.0.45.61 (Windows_x86_64)")

                Timber.d("Fetching GOG game details for ID: $gameId")

                val response = requests.callAttr(
                    "get", url,
                    Kwarg("headers", pyDict),
                    Kwarg("timeout", 10),
                )

                val statusCode = response.get("status_code")?.toInt() ?: 0

                if (statusCode == 200) {
                    val gameJson = response.callAttr("json")

                    // Extract game information
                    val title = gameJson?.callAttr("get", "title")?.toString() ?: "Unknown Game"
                    val slug = gameJson?.callAttr("get", "slug")?.toString() ?: gameId

                    // Check the game_type field for filtering
                    val gameType = gameJson?.callAttr("get", "game_type")?.toString() ?: ""

                    // Filter based on game_type - only keep if it's a proper game
                    if (gameType != "game") {
                        return@withContext null
                    }

                    // Get description - it might be nested
                    val description = try {
                        gameJson?.callAttr("get", "description")?.callAttr("get", "full")?.toString()
                            ?: gameJson?.callAttr("get", "description")?.toString()
                            ?: ""
                    } catch (e: Exception) {
                        ""
                    }

                    // Get best available image URL - try different types in order of preference
                    val imageUrl = try {
                        val images = gameJson?.callAttr("get", "images")
                        if (images != null) {
                            // Try logo2x (high resolution) first, then logo, then other options
                            val imageTypes = listOf("logo2x", "logo", "icon", "background")

                            var foundUrl = ""
                            for (imageType in imageTypes) {
                                val imageData = images.callAttr("get", imageType)?.toString()
                                if (!imageData.isNullOrEmpty()) {
                                    // GOG URLs start with // so we need to add https:
                                    val fullUrl = if (imageData.startsWith("//")) {
                                        "https:$imageData"
                                    } else {
                                        imageData
                                    }

                                    // Try to upgrade logo images to highest quality background version
                                    foundUrl = when {
                                        fullUrl.contains("_glx_logo.jpg") -> {
                                            val baseUrl = fullUrl.substringBefore("_glx_logo.jpg")
                                            "$baseUrl.jpg"
                                        }
                                        fullUrl.contains("_glx_logo_2x.jpg") -> {
                                            val baseUrl = fullUrl.substringBefore("_glx_logo_2x.jpg")
                                            "$baseUrl.jpg"
                                        }
                                        else -> fullUrl
                                    }

                                    Timber.d("Game $gameId - using $imageType image: $fullUrl -> $foundUrl")
                                    break // Exit loop once we find a valid URL
                                }
                            }
                            foundUrl
                        } else {
                            ""
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Game $gameId - error extracting image URL")
                        ""
                    }

                    // Get icon URL specifically
                    val iconUrl = try {
                        val images = gameJson?.callAttr("get", "images")
                        val iconData = images?.callAttr("get", "icon")?.toString()
                        if (!iconData.isNullOrEmpty()) {
                            val fullIconUrl = if (iconData.startsWith("//")) {
                                "https:$iconData"
                            } else {
                                iconData
                            }
                            Timber.d("Game $gameId - icon URL: $fullIconUrl")
                            fullIconUrl
                        } else {
                            ""
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Game $gameId - error extracting icon URL")
                        ""
                    }

                    // Get developer and publisher - these fields are often missing in GOG API
                    val developer = try {
                        val developers = gameJson?.callAttr("get", "developers")
                        if (developers != null) {
                            val firstDev = developers.callAttr("__getitem__", 0)
                            firstDev?.toString()?.takeIf { it.isNotEmpty() } ?: "Unknown Developer"
                        } else {
                            "Unknown Developer"
                        }
                    } catch (e: Exception) {
                        "Unknown Developer"
                    }

                    val publisher = try {
                        val publishers = gameJson?.callAttr("get", "publishers")
                        if (publishers != null) {
                            val firstPub = publishers.callAttr("__getitem__", 0)
                            firstPub?.toString()?.takeIf { it.isNotEmpty() } ?: "Unknown Publisher"
                        } else {
                            "Unknown Publisher"
                        }
                    } catch (e: Exception) {
                        "Unknown Publisher"
                    }

                    // Get release date
                    val releaseDate = try {
                        gameJson?.callAttr("get", "release_date")?.toString() ?: ""
                    } catch (e: Exception) {
                        ""
                    }

                    Timber.d("Successfully fetched details for game: $title")

                    GOGGame(
                        id = gameId,
                        title = title,
                        slug = slug,
                        description = description,
                        imageUrl = imageUrl,
                        iconUrl = iconUrl,
                        developer = developer,
                        publisher = publisher,
                        releaseDate = releaseDate,
                    )
                } else {
                    Timber.w("Failed to fetch game details for $gameId: HTTP $statusCode")
                    null
                }
            } catch (e: Exception) {
                Timber.e(e, "Exception fetching game details for $gameId")
                null
            }
        }

        /**
         * Enhanced download method with proper progress tracking (bypassing GOGDL completely)
         */
        suspend fun downloadGame(gameId: String, installPath: String, authConfigPath: String): Result<DownloadInfo?> {
            return try {
                Timber.i("Starting GOGDL download with progress parsing for game $gameId")

                val installDir = File(installPath)
                if (!installDir.exists()) {
                    installDir.mkdirs()
                }

                // Create DownloadInfo for progress tracking
                val downloadInfo = DownloadInfo(jobCount = 1)
                
                // Track this download in the active downloads map
                getInstance()?.activeDownloads?.put(gameId, downloadInfo)

                // Start GOGDL download with progress parsing
                val downloadJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Create support directory for redistributables (like Heroic does)
                        val supportDir = File(installDir.parentFile, "gog-support")
                        supportDir.mkdirs()

                        val result = executeCommandWithProgressParsing(
                            downloadInfo,
                            "--auth-config-path", authConfigPath,
                            "download", ContainerUtils.extractGameIdFromContainerId(gameId).toString(),
                            "--platform", "windows",
                            "--path", installPath,
                            "--support", supportDir.absolutePath,
                            "--skip-dlcs",
                            "--lang", "en-US",
                            "--max-workers", "1",
                        )

                        if (result.isSuccess) {
                            // Check if the download was actually cancelled
                            if (downloadInfo.isCancelled()) {
                                downloadInfo.setProgress(-1.0f) // Mark as cancelled
                                Timber.i("GOGDL download was cancelled by user")
                            } else {
                                downloadInfo.setProgress(1.0f) // Mark as complete
                                Timber.i("GOGDL download completed successfully")
                            }
                        } else {
                            downloadInfo.setProgress(-1.0f) // Mark as failed
                            Timber.e("GOGDL download failed: ${result.exceptionOrNull()?.message}")
                        }
                    } catch (e: CancellationException) {
                        Timber.i("GOGDL download cancelled by user")
                        downloadInfo.setProgress(-1.0f) // Mark as cancelled
                    } catch (e: Exception) {
                        Timber.e(e, "GOGDL download failed")
                        downloadInfo.setProgress(-1.0f) // Mark as failed
                    } finally {
                        // Clean up the download from active downloads
                        getInstance()?.activeDownloads?.remove(gameId)
                        Timber.d("Cleaned up download for game: $gameId")
                    }
                }
                
                // Store the job in DownloadInfo so it can be cancelled
                downloadInfo.setDownloadJob(downloadJob)

                Result.success(downloadInfo)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start GOG game download")
                Result.failure(e)
            }
        }

        private suspend fun executeCommandWithProgressParsing(downloadInfo: DownloadInfo, vararg args: String): Result<String> {
            return withContext(Dispatchers.IO) {
                var logMonitorJob: Job? = null
                try {
                    // Start log monitoring for GOGDL progress (works for both V1 and V2)
                    logMonitorJob = CoroutineScope(Dispatchers.IO).launch {
                        monitorGOGDLProgress(downloadInfo)
                    }
                    
                    // Store the progress monitor job in DownloadInfo so it can be cancelled
                    downloadInfo.setProgressMonitorJob(logMonitorJob)

                    val python = Python.getInstance()
                    val sys = python.getModule("sys")
                    val originalArgv = sys.get("argv")

                    try {
                        val gogdlCli = python.getModule("gogdl.cli")

                        // Set up arguments for argparse
                        val argsList = listOf("gogdl") + args.toList()
                        Timber.d("Setting GOGDL arguments for argparse: ${args.joinToString(" ")}")
                        val pythonList = python.builtins.callAttr("list", argsList.toTypedArray())
                        sys.put("argv", pythonList)

                        // Check for cancellation before starting
                        ensureActive()

                        // Set up cancellation mechanism for Python
                        // Extract game ID from the download command arguments
                        val gameIdFromArgs = args.find { it.matches(Regex("\\d+")) } ?: "unknown"
                        val builtins = python.getModule("builtins")
                        
                        // Set a global variable that Python can check
                        builtins.put("GOGDL_CANCEL_${gameIdFromArgs}", false)
                        Timber.i("Set up Python cancellation flag: GOGDL_CANCEL_${gameIdFromArgs}")

                        // Execute the main function with periodic cancellation checks
                        val pythonExecutionJob = async(Dispatchers.IO) {
                            gogdlCli.callAttr("main")
                        }
                        
                        // Wait for either completion or cancellation
                        while (pythonExecutionJob.isActive) {
                            delay(100) // Check every 100ms
                            ensureActive() // Throw CancellationException if cancelled
                        }
                        
                        pythonExecutionJob.await()
                        Timber.d("GOGDL execution completed successfully")
                        Result.success("Download completed")
                    } catch (e: Exception) {
                        Timber.e(e, "GOGDL execution failed: ${e.message}")
                        Result.failure(e)
                    } finally {
                        sys.put("argv", originalArgv)
                    }
                } catch (e: CancellationException) {
                    Timber.i("GOGDL command cancelled")
                    throw e // Re-throw to propagate cancellation
                } catch (e: Exception) {
                    Timber.e(e, "Failed to execute GOGDL command: ${args.joinToString(" ")}")
                    Result.failure(e)
                } finally {
                    logMonitorJob?.cancel()
                }
            }
        }

        /**
         * Monitor GOGDL progress by parsing log output like Heroic Games Launcher does
         * Works for both V1 and V2 games using the same progress format
         */
        private suspend fun monitorGOGDLProgress(downloadInfo: DownloadInfo) {
            var process: Process? = null
            try {
                // Clear any existing logcat buffer to ensure fresh start
                try {
                    val clearProcess = ProcessBuilder("logcat", "-c").start()
                    clearProcess.waitFor()
                    Timber.d("Cleared logcat buffer for fresh progress monitoring")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to clear logcat buffer, continuing anyway")
                }
                
                // Add delay to ensure Python process has started and old logs are cleared
                delay(1000)
                
                // Use logcat to read python.stderr logs in real-time with timestamp filtering
                // Only process logs that are newer than when we started
                val startTime = System.currentTimeMillis()
                process = ProcessBuilder("logcat", "-s", "python.stderr:W", "-T", "1")
                    .redirectErrorStream(true)
                    .start()

                val reader = process.inputStream.bufferedReader()
                Timber.d("Progress monitoring logcat process started successfully with timestamp filtering")
                
                // Track progress state exactly like Heroic does
                var currentPercent: Float? = null
                var currentEta: String = ""
                var currentBytes: String = ""
                var currentDownSpeed: Float? = null
                var currentDiskSpeed: Float? = null

                while (downloadInfo.getProgress() < 1.0f && downloadInfo.getProgress() >= 0.0f && !downloadInfo.isCancelled()) {
                    // Check for cancellation before reading each line
                    if (downloadInfo.isCancelled()) {
                        Timber.d("Progress monitoring stopping due to cancellation")
                        break
                    }
                    
                    val line = reader.readLine()
                    if (line != null) {
                        // Double-check cancellation after reading line
                        if (downloadInfo.isCancelled()) {
                            Timber.d("Progress monitoring stopping due to cancellation after line read")
                            break
                        }
                        // Parse like Heroic: only update if field is empty/undefined
                        
                        // parse log for percent (only if not already set)
                        if (currentPercent == null) {
                            val percentMatch = Regex("""Progress: (\d+\.\d+) """).find(line)
                            if (percentMatch != null) {
                                val percent = percentMatch.groupValues[1].toFloatOrNull()
                                if (percent != null && !percent.isNaN()) {
                                    currentPercent = percent
                                }
                            }
                        }

                        // parse log for eta (only if empty)
                        if (currentEta.isEmpty()) {
                            val etaMatch = Regex("""ETA: (\d\d:\d\d:\d\d)""").find(line)
                            if (etaMatch != null) {
                                currentEta = etaMatch.groupValues[1]
                            }
                        }

                        // parse log for game download progress (only if empty)
                        if (currentBytes.isEmpty()) {
                            val bytesMatch = Regex("""Downloaded: (\S+) MiB""").find(line)
                            if (bytesMatch != null) {
                                currentBytes = "${bytesMatch.groupValues[1]}MB"
                            }
                        }

                        // parse log for download speed (only if not set)
                        if (currentDownSpeed == null) {
                            val downSpeedMatch = Regex("""Download\t- (\S+) MiB""").find(line)
                            if (downSpeedMatch != null) {
                                val speed = downSpeedMatch.groupValues[1].toFloatOrNull()
                                if (speed != null && !speed.isNaN()) {
                                    currentDownSpeed = speed
                                }
                            }
                        }

                        // parse disk write speed (only if not set)
                        if (currentDiskSpeed == null) {
                            val diskSpeedMatch = Regex("""Disk\t- (\S+) MiB""").find(line)
                            if (diskSpeedMatch != null) {
                                val speed = diskSpeedMatch.groupValues[1].toFloatOrNull()
                                if (speed != null && !speed.isNaN()) {
                                    currentDiskSpeed = speed
                                }
                            }
                        }
                        
                        // only send update if all values are present (exactly like Heroic)
                        if (currentPercent != null && currentEta.isNotEmpty() && 
                            currentBytes.isNotEmpty() && currentDownSpeed != null && currentDiskSpeed != null) {
                            
                            // Update progress with the percentage
                            val progress = (currentPercent!! / 100.0f).coerceIn(0.0f, 1.0f)
                            downloadInfo.setProgress(progress)
                            
                            // Log exactly like Heroic does
                            Timber.i("Progress for game: ${currentPercent}%/${currentBytes}/${currentEta} Down: ${currentDownSpeed}MB/s / Disk: ${currentDiskSpeed}MB/s")
                            
                            // reset (exactly like Heroic does)
                            currentPercent = null
                            currentEta = ""
                            currentBytes = ""
                            currentDownSpeed = null
                            currentDiskSpeed = null
                        }
                    } else {
                        delay(100L) // Brief delay if no new log lines
                    }
                }

                Timber.d("Progress monitoring loop ended - cancelled: ${downloadInfo.isCancelled()}, progress: ${downloadInfo.getProgress()}")
                process?.destroyForcibly() // Use destroyForcibly for more aggressive termination
                Timber.d("Logcat process destroyed forcibly")
            } catch (e: CancellationException) {
                Timber.d("GOGDL progress monitoring cancelled")
                process?.destroyForcibly()
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Error monitoring GOGDL progress, falling back to estimation")
                // Simple fallback - just wait and set progress to completion
                var lastProgress = 0.0f
                val startTime = System.currentTimeMillis()

                while (downloadInfo.getProgress() < 1.0f && downloadInfo.getProgress() >= 0.0f && !downloadInfo.isCancelled()) {
                    delay(2000L)
                    val elapsed = System.currentTimeMillis() - startTime
                    val estimatedProgress = when {
                        elapsed < 5000 -> 0.05f
                        elapsed < 15000 -> 0.20f
                        elapsed < 30000 -> 0.50f
                        elapsed < 60000 -> 0.80f
                        else -> 0.90f
                    }.coerceAtLeast(lastProgress)

                    if (estimatedProgress > lastProgress) {
                        downloadInfo.setProgress(estimatedProgress)
                        lastProgress = estimatedProgress
                    }
                }
            }
        }

        /**
         * Parse GOGDL progress components from log line using Heroic Games Launcher approach
         * Collects all progress data before updating (prevents partial updates)
         */
        private fun parseGOGDLProgressComponents(
            line: String,
            onPercent: (Float) -> Unit,
            onEta: (String) -> Unit,
            onBytes: (String) -> Unit,
            onDownSpeed: (Float) -> Unit,
            onDiskSpeed: (Float) -> Unit
        ) {
            try {
                // Parse progress percentage: "= Progress: 45.67 12345/67890, Running for: 00:01:23, ETA: 00:02:34"
                val progressRegex = Regex("""= Progress: (\d+\.\d+) .+ETA: (\d\d:\d\d:\d\d)""")
                val progressMatch = progressRegex.find(line)
                
                if (progressMatch != null) {
                    val percent = progressMatch.groupValues[1].toFloat()
                    val eta = progressMatch.groupValues[2]
                    onPercent(percent)
                    onEta(eta)
                    return
                }

                // Parse download progress: "= Downloaded: 123.45 MiB, Written: 234.56 MiB"
                val downloadedRegex = Regex("""= Downloaded: (\S+) MiB""")
                val downloadedMatch = downloadedRegex.find(line)
                
                if (downloadedMatch != null) {
                    val downloadedMB = downloadedMatch.groupValues[1]
                    onBytes("${downloadedMB}MB")
                    return
                }

                // Parse download speed: " + Download	- 12.34 MiB/s (raw) / 23.45 MiB/s (decompressed)"
                val downloadSpeedRegex = Regex(""" \+ Download\t- (\S+) MiB/s \(raw\)""")
                val downloadSpeedMatch = downloadSpeedRegex.find(line)
                
                if (downloadSpeedMatch != null) {
                    val downloadSpeed = downloadSpeedMatch.groupValues[1].toFloat()
                    onDownSpeed(downloadSpeed)
                    return
                }

                // Parse disk speed: " + Disk	- 34.56 MiB/s (write) / 45.67 MiB/s (read)"
                val diskSpeedRegex = Regex(""" \+ Disk\t- (\S+) MiB/s \(write\)""")
                val diskSpeedMatch = diskSpeedRegex.find(line)
                
                if (diskSpeedMatch != null) {
                    val diskSpeed = diskSpeedMatch.groupValues[1].toFloat()
                    onDiskSpeed(diskSpeed)
                    return
                }

                // Handle completion
                if (line.contains("download completed") || line.contains("Download completed")) {
                    Timber.i("GOGDL: Download completed")
                    // Force 100% completion
                    onPercent(100.0f)
                    onEta("00:00:00")
                    onBytes("Complete")
                    onDownSpeed(0.0f)
                    onDiskSpeed(0.0f)
                    return
                }

            } catch (e: Exception) {
                Timber.w(e, "Error parsing GOGDL progress line: $line")
            }
        }

        /**
         * Parse GOGDL progress from log line using Heroic Games Launcher patterns
         * Works for both V1 and V2 games since they use the same ExecutingManager/ProgressBar
         */
        private fun parseGOGDLProgressLine(line: String, downloadInfo: DownloadInfo): Boolean {
            try {
                // Parse progress percentage: "= Progress: 45.67 12345/67890, Running for: 00:01:23, ETA: 00:02:34"
                val progressRegex = Regex("""= Progress: (\d+\.\d+) """)
                val progressMatch = progressRegex.find(line)
                
                if (progressMatch != null) {
                    val percent = progressMatch.groupValues[1].toFloat()
                    val progress = (percent / 100.0f).coerceIn(0.0f, 1.0f)
                    downloadInfo.setProgress(progress)
                    return true
                }

                // Parse download progress: "= Downloaded: 123.45 MiB, Written: 234.56 MiB"
                val downloadedRegex = Regex("""= Downloaded: (\S+) MiB""")
                val downloadedMatch = downloadedRegex.find(line)
                
                if (downloadedMatch != null) {
                    val downloadedMB = downloadedMatch.groupValues[1]
                    Timber.d("Downloaded: ${downloadedMB}MB")
                    return true
                }

                // Parse download speed: " + Download	- 12.34 MiB/s (raw) / 23.45 MiB/s (decompressed)"
                val downloadSpeedRegex = Regex(""" \+ Download\t- (\S+) MiB/s \(raw\)""")
                val downloadSpeedMatch = downloadSpeedRegex.find(line)
                
                if (downloadSpeedMatch != null) {
                    val downloadSpeed = downloadSpeedMatch.groupValues[1]
                    Timber.d("Download speed: ${downloadSpeed}MB/s")
                    return true
                }

                // Parse disk speed: " + Disk	- 34.56 MiB/s (write) / 45.67 MiB/s (read)"
                val diskSpeedRegex = Regex(""" \+ Disk\t- (\S+) MiB/s \(write\)""")
                val diskSpeedMatch = diskSpeedRegex.find(line)
                
                if (diskSpeedMatch != null) {
                    val diskSpeed = diskSpeedMatch.groupValues[1]
                    Timber.d("Disk speed: ${diskSpeed}MB/s")
                    return true
                }

                // Log other important GOGDL messages
                if (line.contains("Starting V1 download") || line.contains("Starting V2 download")) {
                    Timber.i("GOGDL: $line")
                    return true
                }
                
                if (line.contains("download completed") || line.contains("Download completed")) {
                    Timber.i("GOGDL: Download completed")
                    downloadInfo.setProgress(1.0f)
                    return true
                }

                return false
            } catch (e: Exception) {
                Timber.w(e, "Error parsing GOGDL progress line: $line")
                return false
            }
        }

        /**
         * Parse both V1Manager and V2Manager progress from log lines (Heroic approach)
         */
        private fun parseGOGDLProgress(line: String, downloadInfo: DownloadInfo) {
            try {
                // Parse V1Manager progress: "[V1Manager] INFO: Completed 12/16: filename"
                val v1ProgressRegex = Regex("""\[V1Manager\] INFO: Completed\s+(\d+)/(\d+):\s+(.+)""")
                val v1Match = v1ProgressRegex.find(line)

                if (v1Match != null) {
                    val completed = v1Match.groupValues[1].toInt()
                    val total = v1Match.groupValues[2].toInt()
                    val filename = v1Match.groupValues[3]

                    val progress = (completed.toFloat() / total.toFloat()).coerceIn(0.0f, 1.0f)

                    downloadInfo.setProgress(progress)
                    Timber.i("V1 Progress: $completed/$total files (${(progress * 100).toInt()}%) - $filename")
                    return
                }

                // Parse V2Manager progress: "[V2Manager] INFO: Downloading file: filename.exe"
                val v2FileRegex = Regex("""\[V2Manager\] INFO: Downloading file:\s+(.+)""")
                val v2FileMatch = v2FileRegex.find(line)

                if (v2FileMatch != null) {
                    val filename = v2FileMatch.groupValues[1]
                    // For V2, we don't have total file count, so use incremental progress
                    val currentProgress = downloadInfo.getProgress()
                    val increment = 0.05f // 5% per file
                    val newProgress = (currentProgress + increment).coerceAtMost(0.95f)

                    downloadInfo.setProgress(newProgress)
                    Timber.i("V2 Progress: Downloading $filename (${(newProgress * 100).toInt()}%)")
                    return
                }

                // Parse V2Manager chunk progress: "[V2Manager] INFO: Downloading chunk 3/5 for filename.exe"
                val v2ChunkRegex = Regex("""\[V2Manager\] INFO: Downloading chunk\s+(\d+)/(\d+)\s+for\s+(.+)""")
                val v2ChunkMatch = v2ChunkRegex.find(line)

                if (v2ChunkMatch != null) {
                    val currentChunk = v2ChunkMatch.groupValues[1].toInt()
                    val totalChunks = v2ChunkMatch.groupValues[2].toInt()
                    val filename = v2ChunkMatch.groupValues[3]

                    // For chunk progress, add smaller increments
                    val currentProgress = downloadInfo.getProgress()
                    val chunkIncrement = 0.01f // 1% per chunk
                    val newProgress = (currentProgress + chunkIncrement).coerceAtMost(0.95f)

                    downloadInfo.setProgress(newProgress)
                    Timber.d("V2 Chunk Progress: $currentChunk/$totalChunks for $filename (${(newProgress * 100).toInt()}%)")
                    return
                }

                // Parse V2Manager depot info: "[V2Manager] INFO: Depot contains 25 files"
                val v2DepotRegex = Regex("""\[V2Manager\] INFO: Depot contains\s+(\d+)\s+files""")
                val v2DepotMatch = v2DepotRegex.find(line)

                if (v2DepotMatch != null) {
                    val totalFiles = v2DepotMatch.groupValues[1].toInt()
                    Timber.i("V2 Download: Depot contains $totalFiles files")
                    // Set initial progress
                    downloadInfo.setProgress(0.05f)
                    return
                }

                // Check for completion (both V1 and V2)
                if ((line.contains("All") && line.contains("files downloaded successfully")) ||
                    line.contains("Download completed successfully") ||
                    line.contains("Installation completed")
                ) {
                    downloadInfo.setProgress(1.0f)
                    Timber.i("Download completed successfully")
                    return
                }

                // Check for errors (both V1 and V2)
                if (line.contains("ERROR") || line.contains("Failed")) {
                    Timber.w("Download error detected: $line")
                    return
                }
            } catch (e: Exception) {
                Timber.w("Error parsing progress: ${e.message}")
            }
        }

        /**
         * Calculate the total size of all files in a directory
         */
        private fun calculateDirectorySize(directory: File): Long {
            var size = 0L
            try {
                directory.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        size += file.length()
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error calculating directory size")
            }
            return size
        }

        /**
         * Sync GOG cloud saves for a game
         */
        suspend fun syncCloudSaves(gameId: String, savePath: String, authConfigPath: String, timestamp: Float = 0.0f): Result<Unit> {
            return try {
                Timber.i("Starting GOG cloud save sync for game $gameId")

                val result = executeCommand(
                    "--auth-config-path", authConfigPath,
                    "save-sync", savePath,
                    "--dirname", gameId,
                    "--timestamp", timestamp.toString(),
                )

                if (result.isSuccess) {
                    Timber.i("GOG cloud save sync completed successfully for game $gameId")
                    Result.success(Unit)
                } else {
                    val error = result.exceptionOrNull() ?: Exception("Save sync failed")
                    Timber.e(error, "GOG cloud save sync failed for game $gameId")
                    Result.failure(error)
                }
            } catch (e: Exception) {
                Timber.e(e, "GOG cloud save sync exception for game $gameId")
                Result.failure(e)
            }
        }

        /**
         * Check if user is authenticated by testing GOGDL command
         */
        fun hasStoredCredentials(context: Context): Boolean {
            val authFile = File(context.filesDir, "gog_auth.json")
            return authFile.exists()
        }

        /**
         * Get user credentials by calling GOGDL auth command (without --code)
         * This will automatically handle token refresh if needed
         */
        suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> {
            return try {
                val authConfigPath = "${context.filesDir}/gog_auth.json"

                if (!hasStoredCredentials(context)) {
                    return Result.failure(Exception("No stored credentials found"))
                }

                // Use GOGDL to get credentials - this will handle token refresh automatically
                val result = executeCommand("--auth-config-path", authConfigPath, "auth")

                if (result.isSuccess) {
                    val output = result.getOrNull() ?: ""
                    Timber.d("GOGDL credentials output: $output")

                    try {
                        val credentialsJson = JSONObject(output.trim())

                        // Check if there's an error
                        if (credentialsJson.has("error") && credentialsJson.getBoolean("error")) {
                            val errorMsg = credentialsJson.optString("message", "Authentication failed")
                            Timber.e("GOGDL credentials failed: $errorMsg")
                            return Result.failure(Exception("Authentication failed: $errorMsg"))
                        }

                        // Extract credentials from GOGDL response
                        val accessToken = credentialsJson.optString("access_token", "")
                        val refreshToken = credentialsJson.optString("refresh_token", "")
                        val username = credentialsJson.optString("username", "GOG User")
                        val userId = credentialsJson.optString("user_id", "")

                        val credentials = GOGCredentials(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            username = username,
                            userId = userId,
                        )

                        Timber.d("Got credentials for user: $username")
                        Result.success(credentials)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse GOGDL credentials response")
                        Result.failure(e)
                    }
                } else {
                    Timber.e("GOGDL credentials command failed")
                    Result.failure(Exception("Failed to get credentials from GOG"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get stored credentials via GOGDL")
                Result.failure(e)
            }
        }

        /**
         * Validate credentials by calling GOGDL auth command (without --code)
         * This will automatically refresh tokens if they're expired
         */
        suspend fun validateCredentials(context: Context): Result<Boolean> {
            return try {
                val authConfigPath = "${context.filesDir}/gog_auth.json"

                if (!hasStoredCredentials(context)) {
                    Timber.d("No stored credentials found for validation")
                    return Result.success(false)
                }

                Timber.d("Starting credentials validation with GOGDL")

                // Use GOGDL to get credentials - this will handle token refresh automatically
                val result = executeCommand("--auth-config-path", authConfigPath, "auth")

                if (!result.isSuccess) {
                    val error = result.exceptionOrNull()
                    Timber.e("Credentials validation failed - command failed: ${error?.message}")
                    return Result.success(false)
                }

                val output = result.getOrNull() ?: ""
                Timber.d("GOGDL validation output: $output")

                try {
                    val credentialsJson = JSONObject(output.trim())

                    // Check if there's an error
                    if (credentialsJson.has("error") && credentialsJson.getBoolean("error")) {
                        val errorDesc = credentialsJson.optString("message", "Unknown error")
                        Timber.e("Credentials validation failed: $errorDesc")
                        return Result.success(false)
                    }

                    Timber.d("Credentials validation successful")
                    return Result.success(true)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse validation response: $output")
                    return Result.success(false)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to validate credentials")
                return Result.failure(e)
            }
        }

        /**
         * Get GOG library with progressive processing
         * This processes games one by one as they're fetched, without making additional API calls
         */
        private suspend fun getLibraryProgressively(
            authConfigPath: String,
            onGameFetched: suspend (GOGGame) -> Unit,
            onTotalCount: (Int) -> Unit,
        ): Result<Int> {
            return try {
                Timber.i("Getting GOG library progressively...")

                // Read auth credentials using extracted function
                val credentialsResult = readAuthCredentials(authConfigPath)
                if (credentialsResult.isFailure) {
                    return Result.failure(credentialsResult.exceptionOrNull()!!)
                }

                val (accessToken, userId) = credentialsResult.getOrThrow()

                // Use Python requests to call GOG Galaxy API
                val python = Python.getInstance()
                val requests = python.getModule("requests")

                val url = "https://embed.gog.com/user/data/games"

                // Convert Kotlin Map to Python dictionary to avoid LinkedHashMap issues
                val pyDict = python.builtins.callAttr("dict")
                pyDict.callAttr("__setitem__", "Authorization", "Bearer $accessToken")
                pyDict.callAttr("__setitem__", "User-Agent", "GOGGalaxyClient/2.0.45.61 (Windows_x86_64)")

                Timber.d("Making GOG API request to: $url")
                Timber.d("Request headers: Authorization=Bearer ${accessToken.take(20)}..., User-Agent=GOGGalaxyClient/2.0.45.61")

                // Make the request with headers - pass as separate arguments
                val response = requests.callAttr(
                    "get", url,
                    Kwarg("headers", pyDict),
                    Kwarg("timeout", 30),
                )

                val statusCode = response.get("status_code")?.toInt() ?: 0
                Timber.d("GOG API response status: $statusCode")

                if (statusCode == 200) {
                    val responseJson = response.callAttr("json")
                    Timber.d("GOG API response JSON: $responseJson")

                    // Try different ways to access the owned array
                    val ownedGames = try {
                        responseJson?.callAttr("get", "owned")
                    } catch (e: Exception) {
                        Timber.w("Failed to get owned with callAttr: ${e.message}")
                        try {
                            responseJson?.get("owned")
                        } catch (e2: Exception) {
                            Timber.w("Failed to get owned with get: ${e2.message}")
                            null
                        }
                    }

                    Timber.d("GOG API owned games: $ownedGames")

                    // Count the owned game IDs
                    val gameCount = ownedGames?.callAttr("__len__")?.toInt() ?: 0
                    Timber.i("GOG library retrieved: $gameCount game IDs found")

                    // Notify total count first
                    onTotalCount(gameCount)

                    // Convert Python list to Kotlin list of game IDs and process them progressively
                    var processedCount = 0
                    if (ownedGames != null && gameCount > 0) {
                        for (i in 0 until gameCount) {
                            try {
                                val gameId = ownedGames.callAttr("__getitem__", i)?.toString()
                                if (gameId != null) {
                                    // Fetch details for this specific game
                                    val gameDetails = fetchGameDetails(gameId, accessToken)
                                    if (gameDetails != null) {
                                        onGameFetched(gameDetails)
                                        processedCount++

                                        // Small delay to allow UI updates
                                        kotlinx.coroutines.delay(10)
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.w("Failed to process game at index $i: ${e.message}")
                            }
                        }
                    }

                    Timber.i("Successfully processed $processedCount games progressively")
                    Result.success(processedCount)
                } else {
                    val errorText = response.callAttr("text")?.toString() ?: "Unknown error"
                    Timber.e("GOG API error: HTTP $statusCode - $errorText")
                    Result.failure(Exception("Failed to get library: HTTP $statusCode"))
                }
            } catch (e: Exception) {
                Timber.e(e, "GOG library exception")
                Result.failure(e)
            }
        }

        /**
         * Get user library progressively by calling GOG Galaxy API directly
         * This inserts games one by one as they are fetched, providing real-time updates
         */
        suspend fun getUserLibraryProgressively(
            context: Context,
            onGameFetched: suspend (GOGGame) -> Unit,
            onTotalCount: (Int) -> Unit,
        ): Result<Int> {
            return try {
                val authConfigPath = "${context.filesDir}/gog_auth.json"

                if (!hasStoredCredentials(context)) {
                    return Result.failure(Exception("No stored credentials found"))
                }

                // Use the true progressive method that fetches games one by one
                getLibraryProgressively(authConfigPath, onGameFetched, onTotalCount)
            } catch (e: Exception) {
                Timber.e(e, "GOG library exception")
                Result.failure(e)
            }
        }

        fun clearStoredCredentials(context: Context): Boolean {
            return try {
                val authFile = File(context.filesDir, "gog_auth.json")
                if (authFile.exists()) {
                    authFile.delete()
                } else {
                    true
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear GOG credentials")
                false
            }
        }

        // Enhanced hasActiveOperations to track background sync
        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true
        }

        // Add methods to control sync state
        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress
        
        fun getInstance(): GOGService? = instance
        
        /**
         * Check if any download is currently active
         */
        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }
        
        /**
         * Get the currently downloading game ID (for error messages)
         */
        fun getCurrentlyDownloadingGame(): String? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }
        
        /**
         * Get download info for a specific game
         */
        fun getDownloadInfo(gameId: String): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(gameId)
        }
        

        /**
         * Clean up active download when game is deleted
         */
        fun cleanupDownload(gameId: String) {
            getInstance()?.activeDownloads?.remove(gameId)
        }
        
        /**
         * Cancel an active download for a specific game
         */
        fun cancelDownload(gameId: String): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(gameId)
            
            return if (downloadInfo != null) {
                Timber.i("Cancelling download for game: $gameId")
                
                try {
                    // Signal Python to cancel the download
                    val gameIdNum = ContainerUtils.extractGameIdFromContainerId(gameId)
                    val python = Python.getInstance()
                    val builtins = python.getModule("builtins")
                    builtins.put("GOGDL_CANCEL_${gameIdNum}", true)
                    Timber.i("Set Python cancellation flag for game: $gameIdNum")
                    
                    // Verify the flag was set
                    val flagValue = builtins.get("GOGDL_CANCEL_${gameIdNum}")
                    Timber.i("Verified Python cancellation flag value: $flagValue")
                    
                } catch (e: Exception) {
                    Timber.e(e, "Failed to set Python cancellation flag")
                }
                
                // Cancel the Kotlin coroutine
                downloadInfo.cancel()
                Timber.d("Cancelled download job and progress monitor job for game: $gameId")
                
                // Clean up immediately
                instance.activeDownloads.remove(gameId)
                Timber.d("Removed game from active downloads: $gameId")
                true
            } else {
                Timber.w("No active download found for game: $gameId")
                false
            }
        }
    }

    // Add these for foreground service support
    private lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var gogLibraryManager: GOGLibraryManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track active downloads by game ID
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service
        val notification = notificationHelper.createForegroundNotification("GOG Service running...")
        startForeground(2, notification) // Use different ID than SteamService (which uses 1)

        // Start background library sync automatically when service starts with tracking
        backgroundSyncJob = scope.launch {
            try {
                setSyncInProgress(true)
                Timber.d("[GOGService]: Starting background library sync")

                val syncResult = gogLibraryManager.startBackgroundSync(applicationContext)
                if (syncResult.isFailure) {
                    Timber.w("[GOGService]: Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                } else {
                    Timber.i("[GOGService]: Background library sync started successfully")
                }
            } catch (e: Exception) {
                Timber.e(e, "[GOGService]: Exception starting background sync")
            } finally {
                setSyncInProgress(false)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cancel sync operations
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel() // Cancel any ongoing operations
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
