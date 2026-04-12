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
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class XServerScreenUtils {
    /**
     * Replace DLLs from DirectX Redistributable
     */
    companion object {
        fun replaceXAudioDllsFromRedistributable(context: Context, appId: String) {
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
            if (appDirPath == null) {
                return
            }

            val appDir = File(appDirPath)

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

                // Do this once at app startup or before the loop
                SevenZip.initSevenZipFromPlatformJAR()

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
                            val targetDir = File(windowsDir, "syswow64")
                            extractDllsFromCab(cabFile, targetDir)
                        } else if (cabFile.name.lowercase().contains("x64")) {
                            val targetDir = File(windowsDir, "system32")
                            extractDllsFromCab(cabFile, targetDir)
                        }
                    }
            }
        }

        private fun extractDllsFromCab(cabFile: File, targetDir: File) {
            val raf = RandomAccessFile(cabFile, "r")
            val inStream = RandomAccessFileInStream(raf)
            val archive: IInArchive = SevenZip.openInArchive(null, inStream)

            try {
                val numberOfItems = archive.numberOfItems
                for (i in 0 until numberOfItems) {
                    val name = archive.getProperty(i, net.sf.sevenzipjbinding.PropID.PATH) as String

                    if (name.endsWith(".dll", ignoreCase = true)) {
                        val outFile = File(targetDir, name.lowercase())
                        if (outFile.exists()) {
                            outFile.delete()
                        }

                        FileOutputStream(outFile).use { fos ->
                            archive.extract(
                                intArrayOf(i), false,
                                object : IArchiveExtractCallback {
                                    override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
                                        if (extractAskMode != ExtractAskMode.EXTRACT) return null

                                        return ISequentialOutStream { data ->
                                            fos.write(data)
                                            data.size // Return the number of bytes written
                                        }
                                    }

                                    override fun prepareOperation(extractAskMode: ExtractAskMode) {}
                                    override fun setOperationResult(extractOperationResult: ExtractOperationResult) {}
                                    override fun setCompleted(completeValue: Long) {}
                                    override fun setTotal(total: Long) {}
                                }
                            )
                        }

                        Timber.tag("replaceXAudioDllsFromRedistributable").d("Extracted: ${outFile.name}")
                    }
                }
            } finally {
                archive.close()
                inStream.close()
                raf.close()
            }
        }
    }
}
