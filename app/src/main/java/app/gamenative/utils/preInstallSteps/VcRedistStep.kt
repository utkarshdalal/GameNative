package app.gamenative.utils

import app.gamenative.enums.Marker
import app.gamenative.data.GameSource
import com.winlator.container.Container
import java.io.File

/** Windows path -> installer args, checked against host filesystem to see which exist. */
private val vcRedistMap: Map<String, String> = mapOf(
    "A:\\_CommonRedist\\vcredist\\2005\\vcredist_x86.exe" to "/Q",
    "A:\\_CommonRedist\\vcredist\\2005\\vcredist_x64.exe" to "/Q",
    "A:\\_CommonRedist\\vcredist\\2008\\vcredist_x86.exe" to "/qb!",
    "A:\\_CommonRedist\\vcredist\\2008\\vcredist_x64.exe" to "/qb!",
    "A:\\_CommonRedist\\vcredist\\2010\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2010\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2012\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2012\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2013\\vcredist_x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2013\\vcredist_x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2015\\vc_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2015\\vc_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2017\\vc_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2017\\vc_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2019\\vc_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\vcredist\\2019\\vc_redist.x64.exe" to "/install /passive /norestart",
    "A:\\redist\\vcredist_x86.exe" to "",
    "A:\\redist\\vcredist_x64.exe" to "",
    "A:\\_CommonRedist\\MSVC2005\\vcredist_x86.exe" to "/Q",
    "A:\\_CommonRedist\\MSVC2005_x64\\vcredist_x64.exe" to "/Q",
    "A:\\_CommonRedist\\MSVC2008\\vcredist_x86.exe" to "/qb!",
    "A:\\_CommonRedist\\MSVC2008_x64\\vcredist_x64.exe" to "/qb!",
    "A:\\_CommonRedist\\MSVC2010\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2010_x64\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2012\\vcredist_x86.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2012_x64\\vcredist_x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\MSVC2013\\vcredist_x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2013_x64\\vcredist_x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2015\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2015_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2017\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2017_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2019\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\MSVC2019_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\VC_redist.x86.exe" to "/install /passive /norestart",
    "A:\\_CommonRedist\\VC_redist.x64.exe" to "/install /passive /norestart",
)

object VcRedistStep : PreInstallStep {
    override val marker: Marker = Marker.VCREDIST_INSTALLED

    val system32Dlls = listOf(
        "msvcp60.dll",
        "msvcp70.dll",
        "msvcp71.dll",
        "msvcp80.dll",
        "msvcr80.dll",
        "msvcp90.dll",
        "msvcr90.dll",
        "msvcp100.dll",
        "msvcr100.dll",
        "msvcp110.dll",
        "msvcr110.dll",
        "vccorlib110.dll",
        "msvcp120.dll",
        "msvcr120.dll",
        "vccorlib120.dll",
        "msvcp140.dll",
        "msvcp140_1.dll",
        "msvcp140_2.dll",
        "msvcp140_atomic_wait.dll",
        "msvcp140_codecvt_ids.dll",
        "vcruntime140.dll",
        "vcruntime140_1.dll",
        "vccorlib140.dll",
        "concrt140.dll"
    )

    val syswow64Dlls = listOf(
        "msvcp60.dll",
        "msvcp70.dll",
        "msvcp71.dll",
        "msvcp80.dll",
        "msvcr80.dll",
        "msvcp90.dll",
        "msvcr90.dll",
        "msvcp100.dll",
        "msvcr100.dll",
        "msvcp110.dll",
        "msvcr110.dll",
        "vccorlib110.dll",
        "msvcp120.dll",
        "msvcr120.dll",
        "vccorlib120.dll",
        "msvcp140.dll",
        "msvcp140_1.dll",
        "msvcp140_2.dll",
        "msvcp140_atomic_wait.dll",
        "msvcp140_codecvt_ids.dll",
        "vcruntime140.dll",
        "vccorlib140.dll",
        "concrt140.dll"
    )

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return !MarkerUtils.hasMarker(gameDirPath, Marker.VCREDIST_INSTALLED)
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val parts = mutableListOf<String>()
        for ((winPath, args) in vcRedistMap) {
            if (winPath.length < 4 || winPath[1] != ':' || winPath[2] != '\\') continue
            val rest = winPath.substring(3)
            val lastSep = rest.lastIndexOf('\\')
            if (lastSep < 0) continue
            val hostFile = File(gameDir, rest.replace('\\', '/'))
            if (!hostFile.isFile) continue
            parts.add(if (args.isEmpty()) winPath else "$winPath $args")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }
}

