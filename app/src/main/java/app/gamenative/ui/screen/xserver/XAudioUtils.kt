package app.gamenative.ui.screen.xserver

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.data.GameSource
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.FileUtils
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object XAudioUtils {
    const val BATCH_SUCCESS_CHECK = "__GN_XAUDIO_EXTRACT_OK__"

    /**
     * Replace DLLs from DirectX Redistributable
     */
    fun replaceXAudioDllsFromRedistributable(context: Context, guestProgramLauncherComponent: GuestProgramLauncherComponent, appId: String) {
        val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
        val appDirPath = try {
            when (gameSource) {
                GameSource.STEAM -> {
                    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                    SteamService.getAppDirPath(gameId)
                }
                GameSource.GOG -> GOGService.getInstallPath(appId)
                GameSource.EPIC -> {
                    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                    EpicService.getInstallPath(gameId)
                }
                GameSource.AMAZON -> AmazonService.getInstallPath(appId)
                GameSource.CUSTOM_GAME -> CustomGameScanner.getFolderPathFromAppId(appId)
            }
        } catch (e: Exception) {
            Timber.tag("XAudioUtils")
                .w(e, "Failed to resolve install path for appId=%s source=%s", appId, gameSource)
            null
        }

        // Not Support Type
        if (appDirPath.isNullOrBlank()) {
            return
        }

        val appDir = File(appDirPath)
        if (!appDir.isDirectory) {
            Timber.tag("XAudioUtils").w("Install path is not a directory: %s", appDir.absolutePath)
            return
        }

        // Check the common path first, otherwise scan the game dir for DXSETUP.exe
        var directXDir = File(appDirPath, "_CommonRedist/DirectX")
        if (!directXDir.exists()) {
            val dxSetupFile = FileUtils.findFilesRecursive(
                rootPath = appDir.toPath(),
                pattern = "DXSETUP.exe",
                maxDepth = 5,
            ).findFirst().orElse(null)

            if (dxSetupFile != null) {
                directXDir = dxSetupFile.parent.toFile()
            }
        }

        if (directXDir.exists()) {
            val imageFs = ImageFs.find(context)
            val rootDir = imageFs.rootDir
            val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
            val cDriveDir = windowsDir.parentFile!!

            val tempDir = File(windowsDir, "temp")
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                Timber.tag("XAudioUtils")
                    .w("Failed to create temp extraction directory: %s", tempDir.absolutePath)
                return
            }

            val tempDirWow64 = File(windowsDir, "temp/syswow64")
            val tempDirSys32 = File(windowsDir, "temp/system32")

            // Pre-clean to ensure no stale files from interrupted runs remain
            cleanupDirs(tempDirWow64, tempDirSys32)

            val targetDirWow64 = File(windowsDir, "syswow64")
            val targetDirSys32 = File(windowsDir, "system32")

            if (!tempDirWow64.exists() && !tempDirWow64.mkdirs()) {
                Timber.tag("XAudioUtils")
                    .w("Failed to create temp extraction directory: %s", tempDirWow64.absolutePath)
                return
            }

            if (!tempDirSys32.exists() && !tempDirSys32.mkdirs()) {
                Timber.tag("XAudioUtils")
                    .w("Failed to create temp extraction directory: %s", tempDirSys32.absolutePath)
                return
            }

            val cabFilesWow64 = mutableListOf<File>()
            val cabFilesSys32 = mutableListOf<File>()

            directXDir.walkTopDown()
                .filter { file ->
                    val name = file.name.lowercase()
                    val isAudioType =
                        name.contains("xaudio") ||
                                name.contains("xact") ||
                                name.contains("x3daudio")

                    isAudioType && file.extension.equals("cab", ignoreCase = true)
                }
                .forEach { cabFile ->
                    Timber.tag("XAudioUtils").d("Processing cabinet: ${cabFile.name}")

                    if (cabFile.name.lowercase().contains("x86")) {
                        cabFilesWow64.add(cabFile)
                    } else if (cabFile.name.lowercase().contains("x64")) {
                        cabFilesSys32.add(cabFile)
                    }
                }

            if (cabFilesWow64.isEmpty() && cabFilesSys32.isEmpty()) {
                Timber.tag("XAudioUtils")
                    .d("No matching DirectX CABs found for XAudio/XACT/X3DAudio under: %s", directXDir.absolutePath)
                return
            }

            PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Extracting XAudio DLLs..."))

            val batFile = File(tempDir, "extract_dx_audio_dlls.bat")
            val batContent = buildCabarcBatchScript(
                appDir = appDir,
                cDriveDir = cDriveDir,
                cabFilesWow64 = cabFilesWow64,
                cabFilesSys32 = cabFilesSys32,
                tempDirWow64 = tempDirWow64,
                tempDirSys32 = tempDirSys32,
            )

            try {
                batFile.writeText(batContent)
            } catch (e: Exception) {
                Timber.tag("XAudioUtils")
                    .w(e, "Failed to write batch file: %s", batFile.absolutePath)
                return
            }

            val batchCommand = "wine cmd /c ${batFile.absolutePath}"
            val batchResult = guestProgramLauncherComponent.execShellCommand(batchCommand, false)

            if (batchResult.contains(BATCH_SUCCESS_CHECK)) {
                try {
                    if (batFile.exists()) {
                        val deleted = batFile.delete()
                        Timber.tag("XAudioUtils")
                            .d("Deleted batch file: %s (deleted=%s)", batFile.absolutePath, deleted)
                    }
                } catch (e: Exception) {
                    Timber.tag("XAudioUtils")
                        .w(e, "Failed to delete batch file: %s", batFile.absolutePath)
                }

                moveDllsFromTempToTarget(tempDirWow64, targetDirWow64)
                moveDllsFromTempToTarget(tempDirSys32, targetDirSys32)
            } else {
                Timber.tag("XAudioUtils")
                    .w("Batch extraction did not report success. Expected marker: %s", BATCH_SUCCESS_CHECK)
                Timber.tag("XAudioUtils")
                    .w("Batch output:\n%s", batchResult)
            }

            // Cleanup the temp dirs
            cleanupDirs(tempDirWow64, tempDirSys32)
        }
    }

    private fun cleanupDirs(tempDirWow64: File, tempDirSys32: File) {
        try {
            if (tempDirWow64.exists()) {
                val deleted = tempDirWow64.deleteRecursively()
                Timber.tag("XAudioUtils")
                    .d("Cleanup temp dir (wow64): %s (deleted=%s)", tempDirWow64.absolutePath, deleted)
            } else {
                Timber.tag("XAudioUtils")
                    .d("Cleanup temp dir (wow64): %s (skipped; not found)", tempDirWow64.absolutePath)
            }

            if (tempDirSys32.exists()) {
                val deleted = tempDirSys32.deleteRecursively()
                Timber.tag("XAudioUtils")
                    .d("Cleanup temp dir (system32): %s (deleted=%s)", tempDirSys32.absolutePath, deleted)
            } else {
                Timber.tag("XAudioUtils")
                    .d("Cleanup temp dir (system32): %s (skipped; not found)", tempDirSys32.absolutePath)
            }
        } catch (e: Exception) {
            Timber.tag("XAudioUtils")
                .w(e, "Failed during temp dir cleanup")
        }
    }

    private fun buildCabarcBatchScript(
        appDir: File,
        cDriveDir: File,
        cabFilesWow64: List<File>,
        cabFilesSys32: List<File>,
        tempDirWow64: File,
        tempDirSys32: File,
    ): String {
        val lines = mutableListOf<String>()
        lines.add("@echo off")
        lines.add("")

        if (cabFilesWow64.isNotEmpty()) {
            lines.add("echo Extracting (wow64) to ${tempDirWow64.name}")
            lines.add("pushd \"${toWindowsPathForWine("C", cDriveDir, tempDirWow64)}\" || exit /b 1")
            cabFilesWow64.forEach { cab ->
                lines.add("echo Extracting (wow64) ${cab.name}")
                lines.add("cabarc -r -p X \"${toWindowsPathForWine("A", appDir, cab)}\" || exit /b 1")
            }
            lines.add("popd || exit /b 1")
            lines.add("")
        }

        if (cabFilesSys32.isNotEmpty()) {
            lines.add("echo Extracting (system32) to ${tempDirSys32.name}")
            lines.add("pushd \"${toWindowsPathForWine("C", cDriveDir, tempDirSys32)}\" || exit /b 1")
            cabFilesSys32.forEach { cab ->
                lines.add("echo Extracting (system32) ${cab.name}")
                lines.add("cabarc -r -p X \"${toWindowsPathForWine("A", appDir, cab)}\" || exit /b 1")
            }
            lines.add("popd || exit /b 1")
        }

        lines.add("")
        lines.add("echo $BATCH_SUCCESS_CHECK")
        return lines.joinToString("\r\n")
    }

    private fun toWindowsPathForWine(driveName: String, removePrefixFile: File, file: File): String {
        val unix = file.absolutePath.removePrefix(removePrefixFile.absolutePath)
        return "$driveName:\\" + unix.substring(1).replace("/", "\\")
    }

    private fun moveDllsFromTempToTarget(tempDir: File, targetDir: File) {
        if (!tempDir.exists()) {
            Timber.tag("XAudioUtils")
                .d("Temp dir not found, skipping move: %s", tempDir.absolutePath)
            return
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            Timber.tag("XAudioUtils")
                .w("Failed to create target directory: %s", targetDir.absolutePath)
            return
        }

        val dllFiles = tempDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }
            .toList()

        if (dllFiles.isEmpty()) {
            Timber.tag("XAudioUtils")
                .d("No DLLs found in temp dir after cabarc extraction: %s", tempDir.absolutePath)
            return
        }

        // Index the target directory once to avoid repeated full disk scans
        val existingFilesByLower = targetDir.listFiles()?.groupBy { it.name.lowercase() } ?: emptyMap()

        dllFiles.forEach { dllFile ->
            val desiredName = dllFile.name.lowercase()

            // Check our pre-indexed map for case-variant siblings (e.g., "XAudio2_7.dll" vs "xaudio2_7.dll")
            existingFilesByLower[desiredName]?.forEach { existing ->
                if (existing.name != desiredName) {
                    if (existing.delete()) {
                        Timber.tag("XAudioUtils")
                            .d("Removed case-variant sibling: %s", existing.name)
                    }
                }
            }

            val outFile = File(targetDir, desiredName)
            try {
                Files.move(
                    dllFile.toPath(),
                    outFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                Timber.tag("XAudioUtils").d("Extracted: %s", outFile.name)
            } catch (e: Exception) {
                Timber.tag("XAudioUtils")
                    .w(e, "Failed to extract %s -> %s", dllFile.absolutePath, outFile.absolutePath)
            }
        }
    }
}
