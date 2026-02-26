package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File

/**
 * Installs Visual C++ Redistributables by contributing to the main Wine session's cmd /c chain.
 * Map: full Windows path (directory + exe) -> args. If the exe exists on the host, that command is added.
 */
object VcRedistPreLaunchStep : LaunchStep {

    /** Drive letter -> (context, container, pathUnderDrive) -> host directory. */
    private val driveMap: Map<String, (Context, Container, String) -> File?> = mapOf(
        "Z" to { ctx, _, path ->
            File(ImageFs.find(ctx).getRootDir(), path.replace('\\', '/'))
        },
        "A" to { _, container, path ->
            var out: File? = null
            for (drive in Container.drivesIterator(container.drives)) {
                if (drive[0] == "A") {
                    out = File(drive[1], path.replace('\\', '/'))
                    break
                }
            }
            out
        },
    )

    /** Directory + exe (Windows path) -> args. Only entries whose exe exists on host are added. */
    private val vcRedistMap: Map<String, String> = mapOf(
        // Steam
        "A:\\_CommonRedist\\vcredist\\2013\\vcredist_x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2013\\vcredist_x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2015\\vc_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2015\\vc_redist.x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2017\\vc_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2017\\vc_redist.x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2019\\vc_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2019\\vc_redist.x64.exe" to "/install /passive /norestart",
        // GOG
        "A:\\_CommonRedist\\MSVC2013\\vcredist_x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2013_x64\\vcredist_x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2015\\VC_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2015_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2017\\VC_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2017_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2019\\VC_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2019_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    )

    override fun appliesTo(container: Container, appId: String, gameSource: GameSource): Boolean = true

    override fun run(
        context: Context,
        appId: String,
        container: Container,
        stepRunner: StepRunner,
        gameSource: GameSource,
    ): Boolean {
        val parts = mutableListOf<String>()
        for ((winPath, args) in vcRedistMap) {
            if (winPath.length < 4 || winPath[1] != ':' || winPath[2] != '\\') continue
            val driveLetter = winPath.substring(0, 1)
            val rest = winPath.substring(3)
            val lastSep = rest.lastIndexOf('\\')
            if (lastSep < 0) continue
            val dirPath = rest.substring(0, lastSep)
            val exeName = rest.substring(lastSep + 1)
            val resolveDir = driveMap[driveLetter] ?: continue
            val dir = resolveDir(context, container, dirPath) ?: continue
            if (!File(dir, exeName).isFile) continue
            parts.add("$winPath $args")
        }
        val content = if (parts.isEmpty()) null else parts.joinToString(" & ")
        if (content.isNullOrBlank()) return false
        stepRunner.runStepContent(content)
        return true
    }
}
