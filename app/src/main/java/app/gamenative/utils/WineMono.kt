package app.gamenative.utils

import com.winlator.container.BasePrefix
import com.winlator.container.Container
import com.winlator.container.ContainerFiles
import com.winlator.container.ContainerOverlay
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import java.io.File
import timber.log.Timber

object WineMono {
    private const val MONO_DIR = "drive_c/windows/mono"
    private const val PRODUCT_MARK = "\"ProductName\"=\"Wine Mono"
    private val REGISTRY_FILES = listOf("system.reg", "user.reg")

    fun markOwnInstall(container: Container, imageFs: ImageFs) {
        if (!container.isOverlay) return
        val upper = File(imageFs.wineprefix)
        val upperLib = File(upper, "$MONO_DIR/mono-2.0/bin/libmono-2.0-x86.dll")
        if (!upperLib.isFile) return
        val baseWine = File(container.basePrefix)
        val baseLib = File(baseWine, "$MONO_DIR/mono-2.0/bin/libmono-2.0-x86.dll")
        val overlayDir = File(upper, ContainerOverlay.OVERLAY_DIR)
        val opaque = File(File(overlayDir, ContainerOverlay.OPAQUE_DIR), MONO_DIR)
        if (baseLib.isFile && FileUtils.contentEquals(upperLib, baseLib)) {
            val removed = dropDuplicates(File(upper, MONO_DIR), File(baseWine, MONO_DIR))
            if (!upperLib.exists()) {
                opaque.delete()
                File(File(overlayDir, ContainerOverlay.WHITEOUT_DIR), MONO_DIR).deleteRecursively()
            }
            Timber.i("Container ${container.id} Mono matches the base, removed $removed duplicate files")
            return
        }
        if (!opaque.exists()) {
            Timber.i("Container ${container.id} has its own Mono, shadowing the base copy")
            ContainerFiles.markOpaque(upper, MONO_DIR)
        }
    }

    private fun dropDuplicates(upperDir: File, baseDir: File): Int {
        var removed = 0
        upperDir.walkBottomUp().forEach { file ->
            if (!file.isFile) return@forEach
            val baseFile = File(baseDir, file.relativeTo(upperDir).path)
            if (baseFile.isFile && file.length() == baseFile.length() && FileUtils.contentEquals(file, baseFile) && file.delete()) removed++
        }
        return removed
    }

    fun ensureBase(container: Container, monoMsi: File, launcher: GuestProgramLauncherComponent) {
        if (!container.isOverlay || launcher !is BionicProgramLauncherComponent) return
        val baseWine = File(container.basePrefix)
        val fragmentDir = File(baseWine.parentFile, ".mono-" + monoMsi.name)
        if (!fragmentDir.isDirectory || !File(baseWine, "$MONO_DIR/mono-2.0").isDirectory) {
            installIntoBase(baseWine, fragmentDir, monoMsi, launcher)
        }
    }

    fun install(container: Container, imageFs: ImageFs, monoMsi: File, launcher: GuestProgramLauncherComponent): String {
        val upper = File(imageFs.wineprefix)
        if (container.isOverlay && launcher is BionicProgramLauncherComponent) {
            ensureBase(container, monoMsi, launcher)
            val baseWine = File(container.basePrefix)
            val fragmentDir = File(baseWine.parentFile, ".mono-" + monoMsi.name)
            val baseMono = File(baseWine, "$MONO_DIR/mono-2.0")
            if (isRegistered(upper)) {
                Timber.i("Mono already registered in ${container.id}, skipping msiexec")
                return ""
            }
            if (fragmentDir.isDirectory && baseMono.isDirectory && mergeFragments(fragmentDir, upper)) {
                Timber.i("Registered the base Mono in ${container.id}")
                return ""
            }
            val output = runMsiexec("Z:\\opt\\mono-gecko-offline\\${monoMsi.name}", launcher, null, null)
            markOwnInstall(container, imageFs)
            return output
        }
        return runMsiexec("Z:\\opt\\mono-gecko-offline\\${monoMsi.name}", launcher, null, null)
    }

    private fun installIntoBase(baseWine: File, fragmentDir: File, monoMsi: File, launcher: BionicProgramLauncherComponent) {
        fragmentDir.deleteRecursively()
        if (!BasePrefix.ensureRootDrive(baseWine)) return
        File(baseWine, ".update-timestamp").writeText("disable")
        val before = REGISTRY_FILES.associateWith { sections(File(baseWine, it)) }
        val env = EnvVars()
        env.put("WINEPREFIX", baseWine.absolutePath)
        val unset = arrayOf(ContainerOverlay.ENV_UPPER, ContainerOverlay.ENV_LOWER, ContainerOverlay.ENV_ALIASES)
        val output = runMsiexec("Z:" + monoMsi.absolutePath.replace('/', '\\'), launcher, env, unset)
        File(baseWine, ".update-timestamp").writeText("disable")
        BasePrefix.normalize(baseWine)
        if (!File(baseWine, "$MONO_DIR/mono-2.0").isDirectory) {
            Timber.w("Mono install into base $baseWine left no mono-2.0 directory: $output")
            return
        }
        val staging = File(fragmentDir.path + ".tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        for (name in REGISTRY_FILES) {
            val after = sections(File(baseWine, name))
            val added = after.filterKeys { it !in before.getValue(name) }
            if (added.isEmpty()) continue
            File(staging, name).writeText(added.values.joinToString(""))
        }
        WineMsiCache.deleteCachedCopies(File(baseWine, "drive_c/windows/Installer"), monoMsi)
        if (!staging.renameTo(fragmentDir)) Timber.w("Could not publish $fragmentDir")
        Timber.i("Installed Mono into base $baseWine")
    }

    private fun runMsiexec(dosPath: String, launcher: GuestProgramLauncherComponent, env: EnvVars?, unset: Array<String>?): String {
        val cmd = "wine msiexec /i $dosPath"
        Timber.i("Install mono command $cmd" + (env?.let { " with $it" } ?: ""))
        if (launcher is BionicProgramLauncherComponent && env != null) {
            val output = launcher.execShellCommand(cmd, true, env, unset)
            launcher.execShellCommand("wineserver -w", true, env, unset)
            return output
        }
        return launcher.execShellCommand(cmd)
    }

    fun isRegistered(wineDir: File): Boolean {
        val reg = File(wineDir, "system.reg")
        if (!reg.isFile) return false
        return reg.bufferedReader().useLines { lines -> lines.any { it.startsWith(PRODUCT_MARK) } }
    }

    private fun mergeFragments(fragmentDir: File, wineDir: File): Boolean {
        for (name in REGISTRY_FILES) {
            val fragment = File(fragmentDir, name)
            if (!fragment.isFile) continue
            val target = File(wineDir, name)
            if (!target.isFile) return false
            val present = sections(target).keys
            val toAdd = sections(fragment).filterKeys { it !in present }
            if (toAdd.isEmpty()) continue
            val text = target.readText()
            target.writeText(text.trimEnd('\n') + "\n\n" + toAdd.values.joinToString(""))
        }
        return true
    }

    fun sections(regFile: File): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (!regFile.isFile) return result
        var key: String? = null
        val body = StringBuilder()
        fun flush() {
            key?.let { result[it] = body.toString() }
            key = null
            body.setLength(0)
        }
        regFile.forEachLine { line ->
            if (line.startsWith("[")) {
                flush()
                key = line.substring(0, line.lastIndexOf(']') + 1)
            }
            if (key != null) body.append(line).append('\n')
        }
        flush()
        return result
    }
}
