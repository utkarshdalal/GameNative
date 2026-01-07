package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.DownloadInfo
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Progress callback that Python code can invoke to report download progress
 */
class ProgressCallback(private val downloadInfo: DownloadInfo) {
    @JvmOverloads
    fun update(percent: Float = 0f, downloadedMB: Float = 0f, totalMB: Float = 0f, downloadSpeedMBps: Float = 0f, eta: String = "") {
        try {
            val progress = (percent / 100.0f).coerceIn(0.0f, 1.0f)

            // Update byte-level progress for more accurate tracking
            // GOGDL uses binary mebibytes (MiB), so convert using 1024*1024 not 1_000_000
            val downloadedBytes = (downloadedMB * 1024 * 1024).toLong()
            val totalBytes = (totalMB * 1024 * 1024).toLong()

            // Set total bytes if we haven't already and it's available
            if (totalBytes > 0 && downloadInfo.getTotalExpectedBytes() == 0L) {
                downloadInfo.setTotalExpectedBytes(totalBytes)
            }

            // Update bytes downloaded (delta from previous update)
            val previousBytes = downloadInfo.getBytesDownloaded()
            if (downloadedBytes > previousBytes) {
                val deltaBytes = downloadedBytes - previousBytes
                downloadInfo.updateBytesDownloaded(deltaBytes)
            }

            // Also set percentage-based progress for compatibility
            downloadInfo.setProgress(progress)

            // Update status message with ETA or progress info
            if (eta.isNotEmpty() && eta != "00:00:00") {
                downloadInfo.updateStatusMessage("ETA: $eta")
            } else if (percent > 0f) {
                downloadInfo.updateStatusMessage(String.format("%.1f%%", percent))
            } else {
                downloadInfo.updateStatusMessage("Starting...")
            }

            if (percent > 0f) {
                Timber.d("Download progress: %.1f%% (%.1f/%.1f MB) Speed: %.2f MB/s ETA: %s",
                    percent, downloadedMB, totalMB, downloadSpeedMBps, eta)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error updating download progress")
        }
    }
}

/**
 * This an execution Bridge for Python GOGDL functionality
 *
 * This is purely to initialize and execute GOGDL commands as an abstraction layer to reduce duplication.
 */
object GOGPythonBridge {
    private var python: Python? = null
    private var isInitialized = false

    /**
     * Initialize the Chaquopy Python environment
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) return true

        return try {
            Timber.i("Initializing GOGPythonBridge with Chaquopy...")

            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            python = Python.getInstance()

            isInitialized = true
            Timber.i("GOGPythonBridge initialized successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize GOGPythonBridge")
            false
        }
    }

    /**
     * Check if Python environment is initialized
     */
    fun isReady(): Boolean = isInitialized && Python.isStarted()

    /**
     * Executes Python GOGDL commands using Chaquopy (Java-Python lib)
     * @param args Command line arguments to pass to gogdl CLI
     * @return Result containing command output or error
     */
    suspend fun executeCommand(vararg args: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                if (!Python.isStarted()) {
                    Timber.e("Python is not started! Cannot execute GOGDL command")
                    return@withContext Result.failure(Exception("Python environment not initialized"))
                }

                val python = Python.getInstance()
                val sys = python.getModule("sys")
                val io = python.getModule("io")
                val originalArgv = sys.get("argv")

                try {
                    val gogdlCli = python.getModule("gogdl.cli")

                    // Set up arguments for argparse
                    val argsList = listOf("gogdl") + args.toList()
                    val pythonList = python.builtins.callAttr("list", argsList.toTypedArray())
                    sys.put("argv", pythonList)

                    // Capture stdout
                    val stdoutCapture = io.callAttr("StringIO")
                    val originalStdout = sys.get("stdout")
                    sys.put("stdout", stdoutCapture)

                    // Execute the main function
                    gogdlCli.callAttr("main")

                    // Get the captured output
                    val output = stdoutCapture.callAttr("getvalue").toString()

                    // Restore original stdout
                    sys.put("stdout", originalStdout)

                    if (output.isNotEmpty()) {
                        Result.success(output)
                    } else {
                        Timber.w("GOGDL execution completed but output is empty")
                        Result.success("GOGDL execution completed")
                    }

                } catch (e: Exception) {
                    Timber.e(e, "GOGDL execution failed: ${e.message}")
                    Result.failure(Exception("GOGDL execution failed: ${e.message}", e))
                } finally {
                    // Restore original sys.argv
                    sys.put("argv", originalArgv)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to execute GOGDL command: ${args.joinToString(" ")}")
                Result.failure(Exception("GOGDL execution failed: ${e.message}", e))
            }
        }
    }

    /**
     * Execute GOGDL command with progress callback for downloads
     *
     * This variant allows Python code to report progress via a callback object.
     *
     * @param downloadInfo DownloadInfo object to track progress
     * @param args Command line arguments to pass to gogdl CLI
     * @return Result containing command output or error
     */
    suspend fun executeCommandWithCallback(downloadInfo: DownloadInfo, vararg args: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val python = Python.getInstance()
                val sys = python.getModule("sys")
                val originalArgv = sys.get("argv")

                try {
                    // Create progress callback that Python can invoke
                    val progressCallback = ProgressCallback(downloadInfo)

                    // Get the gogdl module and set up callback
                    val gogdlModule = python.getModule("gogdl")

                    // Try to set progress callback if gogdl supports it
                    try {
                        gogdlModule.put("_progress_callback", progressCallback)
                    } catch (e: Exception) {
                        Timber.w(e, "Could not register progress callback, will use estimation")
                    }

                    val gogdlCli = python.getModule("gogdl.cli")

                    // Set up arguments for argparse
                    val argsList = listOf("gogdl") + args.toList()
                    val pythonList = python.builtins.callAttr("list", argsList.toTypedArray())
                    sys.put("argv", pythonList)

                    // Check for cancellation before starting
                    ensureActive()

                    // Start a simple progress estimator in case callback doesn't work
                    val estimatorJob = CoroutineScope(Dispatchers.IO).launch {
                        estimateProgress(downloadInfo)
                    }

                    try {
                        // Execute the main function
                        gogdlCli.callAttr("main")
                        Timber.i("GOGDL execution completed successfully")
                        Result.success("Download completed")
                    } finally {
                        estimatorJob.cancel()
                    }
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
            }
        }
    }

    /**
     * Estimate progress when callback isn't available
     * Shows gradual progress to indicate activity
     */
    private suspend fun estimateProgress(downloadInfo: DownloadInfo) {
        try {
            var lastProgress = 0.0f
            var lastBytesDownloaded = 0L
            val startTime = System.currentTimeMillis()
            var callbackDetected = false
            val CHECK_INTERVAL = 3000L

            while (downloadInfo.getProgress() < 1.0f && downloadInfo.getProgress() >= 0.0f) {
                delay(CHECK_INTERVAL)

                val currentBytes = downloadInfo.getBytesDownloaded()
                val currentProgress = downloadInfo.getProgress()

                // Check if the callback is actively updating (bytes are increasing)
                if (currentBytes > lastBytesDownloaded) {
                    if (!callbackDetected) {
                        Timber.d("Progress callback detected, disabling estimator")
                        callbackDetected = true
                    }
                    lastBytesDownloaded = currentBytes
                    lastProgress = currentProgress
                    continue  // Don't override real progress
                }

                // Also check if progress increased significantly without estimator intervention
                if (currentProgress > lastProgress + 0.02f) {
                    if (!callbackDetected) {
                        Timber.d("Progress callback detected (progress jump), disabling estimator")
                        callbackDetected = true
                    }
                    lastProgress = currentProgress
                    continue  // Don't override real progress
                }

                // Only estimate if callback hasn't been detected
                if (!callbackDetected) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val estimatedProgress = when {
                        elapsed < 5000 -> 0.05f
                        elapsed < 15000 -> 0.15f
                        elapsed < 30000 -> 0.30f
                        elapsed < 60000 -> 0.50f
                        elapsed < 120000 -> 0.70f
                        elapsed < 180000 -> 0.85f
                        else -> 0.95f
                    }.coerceAtLeast(lastProgress)

                    downloadInfo.setProgress(estimatedProgress)
                    lastProgress = estimatedProgress
                    Timber.d("Estimated progress: %.1f%%", estimatedProgress * 100)
                }
            }
        } catch (e: CancellationException) {
            Timber.d("Progress estimation cancelled")
        } catch (e: Exception) {
            Timber.w(e, "Error in progress estimation")
        }
    }
}
