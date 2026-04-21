package app.gamenative.ui.screen.xserver

import android.content.Context
import app.gamenative.data.GameSource
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

class XServerScreenUtils {
    /**
     * Replace DLLs from DirectX Redistributable
     */
    companion object {
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
                Timber.tag("replaceXAudioDllsFromRedistributable")
                    .w(e, "Failed to resolve install path for appId=%s source=%s", appId, gameSource)
                null
            }

            // Not Support Type
            if (appDirPath.isNullOrBlank()) {
                return
            }

            val appDir = File(appDirPath)
            if (!appDir.isDirectory) {
                Timber.tag("replaceXAudioDllsFromRedistributable").w("Install path is not a directory: %s", appDir.absolutePath)
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
                    Timber.tag("replaceXAudioDllsFromRedistributable")
                        .w("Failed to create temp extraction directory: %s", tempDir.absolutePath)
                    return
                }

                val tempDirWow64 = File(windowsDir, "temp/syswow64")
                val tempDirSys32 = File(windowsDir, "temp/system32")

                val targetDirWow64 = File(windowsDir, "syswow64")
                val targetDirSys32 = File(windowsDir, "system32")

                if (!tempDirWow64.exists() && !tempDirWow64.mkdirs()) {
                    Timber.tag("replaceXAudioDllsFromRedistributable")
                        .w("Failed to create temp extraction directory: %s", tempDirWow64.absolutePath)
                    return
                }

                if (!tempDirSys32.exists() && !tempDirSys32.mkdirs()) {
                    Timber.tag("replaceXAudioDllsFromRedistributable")
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
                        Timber.tag("replaceXAudioDllsFromRedistributable").d("Processing cabinet: ${cabFile.name}")

                        if (cabFile.name.lowercase().contains("x86")) {
                            cabFilesWow64.add(cabFile)
                        } else if (cabFile.name.lowercase().contains("x64")) {
                            cabFilesSys32.add(cabFile)
                        }
                    }

                if (cabFilesWow64.isEmpty() && cabFilesSys32.isEmpty()) {
                    Timber.tag("replaceXAudioDllsFromRedistributable")
                        .d("No matching DirectX CABs found for XAudio/XACT/X3DAudio under: %s", directXDir.absolutePath)
                    return
                }

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
                    Timber.tag("replaceXAudioDllsFromRedistributable")
                        .w(e, "Failed to write batch file: %s", batFile.absolutePath)
                    return
                }

                val batchCommand = "wine cmd /c ${batFile.absolutePath}"
                val batchResult = guestProgramLauncherComponent.execShellCommand(batchCommand, false)
                Timber.tag("replaceXAudioDllsFromRedistributable")
                    .d("Batch extraction result: \n%s", batchResult)

                try {
                    if (batFile.exists()) {
                        val deleted = batFile.delete()
                        Timber.tag("replaceXAudioDllsFromRedistributable")
                            .d("Deleted batch file: %s (deleted=%s)", batFile.absolutePath, deleted)
                    }
                } catch (e: Exception) {
                    Timber.tag("replaceXAudioDllsFromRedistributable")
                        .w(e, "Failed to delete batch file: %s", batFile.absolutePath)
                }

                moveDllsFromTempToTarget(tempDirWow64, targetDirWow64)
                moveDllsFromTempToTarget(tempDirSys32, targetDirSys32)

                try {
                    if (tempDirWow64.exists()) {
                        val deleted = tempDirWow64.deleteRecursively()
                        Timber.tag("replaceXAudioDllsFromRedistributable")
                            .d("Cleanup temp dir (wow64): %s (deleted=%s)", tempDirWow64.absolutePath, deleted)
                    } else {
                        Timber.tag("replaceXAudioDllsFromRedistributable")
                            .d("Cleanup temp dir (wow64): %s (skipped; not found)", tempDirWow64.absolutePath)
                    }

                    if (tempDirSys32.exists()) {
                        val deleted = tempDirSys32.deleteRecursively()
                        Timber.tag("replaceXAudioDllsFromRedistributable")
                            .d("Cleanup temp dir (system32): %s (deleted=%s)", tempDirSys32.absolutePath, deleted)
                    } else {
                        Timber.tag("replaceXAudioDllsFromRedistributable")
                            .d("Cleanup temp dir (system32): %s (skipped; not found)", tempDirSys32.absolutePath)
                    }
                } catch (e: Exception) {
                    Timber.tag("replaceXAudioDllsFromRedistributable")
                        .w(e, "Failed during temp dir cleanup")
                }
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
                lines.add("pushd \"${toWindowsPathForWine("C", cDriveDir, tempDirWow64)}\"")
                cabFilesWow64.forEach { cab ->
                    lines.add("echo Extracting (wow64) ${cab.name}")
                    lines.add("cabarc -r -p X \"${toWindowsPathForWine("A", appDir, cab)}\"")
                }
                lines.add("popd")
                lines.add("")
            }

            if (cabFilesSys32.isNotEmpty()) {
                lines.add("echo Extracting (system32) to ${tempDirSys32.name}")
                lines.add("pushd \"${toWindowsPathForWine("C", cDriveDir, tempDirSys32)}\"")
                cabFilesSys32.forEach { cab ->
                    lines.add("echo Extracting (system32) ${cab.name}")
                    lines.add("cabarc -r -p X \"${toWindowsPathForWine("A", appDir, cab)}\"")
                }
                lines.add("popd")
                lines.add("")
            }

            lines.add("")
            return lines.joinToString("\r\n")
        }

        private fun toWindowsPathForWine(driveName: String, removePrefixFile: File, file: File): String {
            val unix = file.absolutePath.removePrefix(removePrefixFile.absolutePath)
            return "$driveName:\\" + unix.substring(1).replace("/", "\\")
        }

        private fun moveDllsFromTempToTarget(tempDir: File, targetDir: File) {
            if (!tempDir.exists()) {
                Timber.tag("replaceXAudioDllsFromRedistributable")
                    .d("Temp dir not found, skipping move: %s", tempDir.absolutePath)
                return
            }

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                Timber.tag("replaceXAudioDllsFromRedistributable")
                    .w("Failed to create target directory: %s", targetDir.absolutePath)
                return
            }

            val dllFiles = tempDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }
                .toList()

            if (dllFiles.isEmpty()) {
                Timber.tag("replaceXAudioDllsFromRedistributable")
                    .d("No DLLs found in temp dir after cabarc extraction: %s", tempDir.absolutePath)
                return
            }

            dllFiles.forEach { dllFile ->
                val outFile = File(targetDir, dllFile.name.lowercase())
                try {
                    Files.move(
                        dllFile.toPath(),
                        outFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    Timber.tag("replaceXAudioDllsFromRedistributable").d("Extracted: %s", outFile.name)
                } catch (e: Exception) {
                    Timber.tag("replaceXAudioDllsFromRedistributable")
                        .w(e, "Failed to extract %s -> %s", dllFile.absolutePath, outFile.absolutePath)
                }
            }
        }
    }
}
