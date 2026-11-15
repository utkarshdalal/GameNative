package app.gamenative.utils.protonFixes

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.WineInfo
import com.winlator.xenvironment.ImageFs
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs the Microsoft.Windows.Common-Controls 6.0 side-by-side assembly into the prefix.
 *
 * Wine 11 splits comctl32 in two: `comctl32.dll` stays the v5 DLL in system32, and
 * `TaskDialog`/`TaskDialogIndirect` (ordinals 344/345) move to `comctl32_v6.dll`, reachable only as
 * the SxS assembly `winsxs\<arch>_microsoft.windows.common-controls_6595b64144ccf1df_6.0.2600.2982_none_deadbeef`,
 * which setupapi writes from the DLL's embedded manifest when the prefix is provisioned. In the v5
 * DLL those two ordinals are empty export slots, so an import of either binds to the loader's
 * unimplemented-function stub and the first call kills the thread:
 * `wine: Call from ... to unimplemented function COMCTL32.dll.345, aborting`. RE9 hits exactly that.
 *
 * Builds from before the split (Proton 9 and 10 here) ship no `comctl32_v6.dll`; their single
 * `comctl32.dll` is the v6 implementation, and setupapi copies that same file into the assembly.
 * The assembly is still required there: every program whose manifest depends on Common-Controls 6.0
 * loses its whole activation context without it, and with it the compatibility section, so
 * `GetVersionEx` reports Windows 8.
 *
 * So the assembly is re-created on every launch from the selected build's own v6 DLL —
 * `comctl32_v6.dll` when the build ships one, its `comctl32.dll` otherwise — which keeps the prefix in
 * step with the wine version: an assembly left behind by another build is replaced, and a missing
 * one is restored. The version in the directory name only has to be 6.0.x.y:
 * ntdll matches the manifest with a `<arch>_<name>_<key>_6.0.*.*_*_*.manifest` filter, so
 * build/revision differences never break the lookup.
 */
object CommonControlsV6 {
    private const val TAG = "CommonControlsV6"

    private const val SRC_DLL = "comctl32_v6.dll"
    private const val DST_DLL = "comctl32.dll"
    private const val TMP_SUFFIX = "gnnew"
    private const val TMP_DLL = "$DST_DLL.$TMP_SUFFIX"
    private const val ASSEMBLY = "microsoft.windows.common-controls_6595b64144ccf1df_6.0.2600.2982_none_deadbeef"

    /** Every name a process may resolve on the builds we ship (x86_64, arm64ec, aarch64). */
    private val ALL_ARCHES = listOf("amd64", "arm64", "x86")

    /**
     * Window classes the v6 assembly must list: user32 redirects these to the v6 DLL only when the
     * active activation context names them. Same list Wine's own comctl32.manifest carries.
     */
    private val WINDOW_CLASSES = listOf(
        "Button", "ButtonListBox", "ComboBoxEx32", "ComboLBox", "ComboBox", "Edit", "ListBox",
        "NativeFontCtl", "ReBarWindow32", "ScrollBar", "Static", "SysAnimate32", "SysDateTimePick32",
        "SysHeader32", "SysIPAddress32", "SysLink", "SysListView32", "SysMonthCal32", "SysPager",
        "SysTabControl32", "SysTreeView32", "ToolbarWindow32", "msctls_hotkey32", "msctls_progress32",
        "msctls_statusbar32", "msctls_trackbar32", "msctls_updown32", "tooltips_class32",
    )

    /**
     * @return the number of assemblies (re)installed.
     */
    fun ensureInstalled(context: Context, container: Container, wineInfo: WineInfo?): Int {
        if (wineInfo == null) return 0
        val libDir = wineLibDir(context, wineInfo)
        val upperWine = File(container.rootDir, ".wine")
        val prefixWine = targetPrefix(container)
        val target = File(prefixWine, "drive_c/windows/winsxs")
        // A thin container's own .wine can carry an assembly from the container pattern that shadows
        // the shared base prefix; it has to be checked whenever the base is the write target.
        val upper = if (prefixWine != upperWine) File(upperWine, "drive_c/windows/winsxs") else null

        val arches = targetArches(wineInfo)
        // Decided per build, not per arch: on a split build `comctl32.dll` is the v5 DLL and must
        // never become the assembly, even for an arch whose v6 DLL is missing.
        val split = arches.any { (_, peDir) -> File(File(libDir, peDir), SRC_DLL).isFile }
        val srcName = if (split) SRC_DLL else DST_DLL
        val sources = linkedMapOf<String, File>()
        for ((arch, peDir) in arches) {
            val src = File(File(libDir, peDir), srcName)
            if (src.isFile) sources[arch] = src
            else Timber.tag(TAG).d("No $srcName in ${src.parent}")
        }

        var installed = 0
        for ((arch, src) in sources) {
            val name = "${arch}_$ASSEMBLY"
            val dst = File(File(target, name), DST_DLL)
            if (!isCurrentBuild(dst, src)) {
                if (!linkOrCopy(src, dst)) {
                    Timber.tag(TAG).w("Could not install $name from $src")
                    continue
                }
                Timber.tag(TAG).i("Installed $name (${src.parentFile?.name}) into ${dst.parent}")
                installed++
            }
            // The manifest is what makes the directory discoverable, so it is repaired even when the
            // DLL was already in place — and a prefix-local copy is only dropped once it is published.
            if (!writeManifest(File(target, "manifests/$name.manifest"), arch)) {
                Timber.tag(TAG).w("Manifest for $name not published, keeping any prefix-local copy")
                continue
            }
            if (upper == null) continue
            if (prune(upper, name, src) != Prune.KEPT) continue
            // The local copy is this build's and stays, so its manifest has to agree with it or it
            // shadows the one just written under the base prefix.
            val localManifest = File(upper, "manifests/$name.manifest")
            if (writeManifest(localManifest, arch)) continue
            // Not repairable: drop the local manifest so the base pair is what Wine resolves, and
            // if that file cannot be removed either, drop the local assembly along with it.
            if (!localManifest.exists() || localManifest.delete()) continue
            Timber.tag(TAG).w("Could not repair or drop ${localManifest.name}; removing the local assembly")
            if (FileUtils.delete(File(upper, name))) localManifest.delete()
        }

        // Arches this build has no v6 DLL for: an assembly left by another build would win the
        // lookup with a DLL this one cannot vouch for.
        for (arch in ALL_ARCHES) {
            if (sources.containsKey(arch)) continue
            val name = "${arch}_$ASSEMBLY"
            prune(target, name, null)
            if (upper != null) prune(upper, name, null)
        }
        return installed
    }

    /** Overlay containers keep the shared base prefix; writing there serves every container of that Wine. */
    private fun targetPrefix(container: Container): File =
        if (container.isOverlay && container.basePrefix.isNotEmpty()) File(container.basePrefix)
        else File(container.rootDir, ".wine")

    private fun wineLibDir(context: Context, wineInfo: WineInfo): File {
        val path = wineInfo.path
        return if (!path.isNullOrEmpty()) File(path, "lib/wine")
        else File(ImageFs.find(context).rootDir, "opt/wine/lib/wine")
    }

    /**
     * (winsxs arch prefix, source PE directory) pairs.
     *
     * arm64ec needs both: setupapi names the directory `amd64_`, ntdll resolves `*` to `arm64`, and
     * only an explicit `amd64` dependency is rescued by the `amd64_` -> `a??64_` wildcard.
     */
    private fun targetArches(wineInfo: WineInfo): List<Pair<String, String>> = when {
        wineInfo.isArm64EC -> listOf(
            "amd64" to "aarch64-windows",
            "arm64" to "aarch64-windows",
            "x86" to "i386-windows",
        )
        wineInfo.arch == "x86_64" -> listOf(
            "amd64" to "x86_64-windows",
            "x86" to "i386-windows",
        )
        wineInfo.arch == "aarch64" -> listOf(
            "arm64" to "aarch64-windows",
            "x86" to "i386-windows",
        )
        else -> listOf("x86" to "i386-windows")
    }

    /**
     * True when [dst] already is this build's DLL. Size first (free), then a link/inode or content
     * check — the steady state is a hard link to the wine tree, which `isSameFile` settles without
     * reading a byte.
     */
    private fun isCurrentBuild(dst: File, src: File): Boolean {
        if (!dst.isFile || dst.length() != src.length()) return false
        return try {
            Files.isSameFile(dst.toPath(), src.toPath()) || FileUtils.contentEquals(src, dst)
        } catch (_: Exception) {
            false
        }
    }

    private enum class Prune { NONE, REMOVED, KEPT }

    /**
     * Removes an assembly directory that is not [keep]: empty, holding only the temp file an
     * interrupted install left behind, or holding another build's DLL — the container pattern ships
     * an x86_64 v6 DLL under `amd64_`, which an arm64ec process cannot load and which wins the
     * lookup over the base prefix. Returns [Prune.KEPT] when the directory holds [keep] itself.
     *
     * The manifest goes with the directory: a manifest whose directory is gone makes the whole
     * lookup fail. A manifest without a directory is left alone for that same reason — under the
     * overlay the other layer may be serving both — so the pair is only ever edited together.
     */
    private fun prune(winsxs: File, name: String, keep: File?): Prune {
        val dir = File(winsxs, name)
        if (!dir.isDirectory) return Prune.NONE
        val dll = File(dir, DST_DLL)
        if (dll.isFile && keep != null && isCurrentBuild(dll, keep)) return Prune.KEPT
        val entries = dir.list() ?: return Prune.NONE
        if (dll.isFile) {
            Timber.tag(TAG).i(
                if (keep == null) "Removing $name at ${dir.parent}: this build has no v6 DLL for that arch"
                else "Removing stale $name at ${dir.parent}: ${dll.length()} bytes, not this build's ${keep.name}",
            )
        } else if (entries.isEmpty() || (entries.size == 1 && entries[0] == TMP_DLL)) {
            File(dir, TMP_DLL).delete()
            Timber.tag(TAG).d("Removing leftover $name at ${dir.parent}")
        } else {
            return Prune.NONE
        }
        if (!FileUtils.delete(dir)) {
            Timber.tag(TAG).w("Could not remove $dir")
            return Prune.NONE
        }
        File(winsxs, "manifests/$name.manifest").delete()
        return Prune.REMOVED
    }

    /**
     * Creates [dst] beside itself first and moves it into place, so a failed link or copy cannot
     * leave the prefix without the DLL its manifest points at.
     */
    private fun linkOrCopy(src: File, dst: File): Boolean {
        val dir = dst.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, TMP_DLL)
        tmp.delete()
        val created = try {
            Files.createLink(tmp.toPath(), src.toPath())
            true
        } catch (_: Throwable) {
            try {
                src.copyTo(tmp, overwrite = true)
                true
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "copy $src -> $tmp failed")
                false
            }
        }
        if (!created) {
            tmp.delete()
            return false
        }
        return try {
            Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "move $tmp -> $dst failed")
            tmp.delete()
            false
        }
    }

    /**
     * Publishes [file] through a sibling temp file and an atomic move, so a failed write cannot
     * leave a truncated manifest where a working one was. An implementation that cannot move
     * atomically throws `AtomicMoveNotSupportedException`, which surfaces here as a failed
     * publication rather than a partial file.
     */
    private fun writeManifest(file: File, arch: String): Boolean {
        val xml = manifestXml(arch)
        val current = try {
            file.isFile && file.readText() == xml
        } catch (_: Exception) {
            false
        }
        if (current) return true
        val dir = file.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, "${file.name}.$TMP_SUFFIX")
        return try {
            tmp.writeText(xml)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "writing $file failed")
            tmp.delete()
            false
        }
    }

    private fun manifestXml(arch: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<assembly xmlns=\"urn:schemas-microsoft-com:asm.v1\" manifestVersion=\"1.0\">\n")
        append("  <assemblyIdentity type=\"win32\" name=\"Microsoft.Windows.Common-Controls\" ")
        append("version=\"6.0.2600.2982\" processorArchitecture=\"$arch\" publicKeyToken=\"6595b64144ccf1df\"/>\n")
        append("  <file name=\"$DST_DLL\">\n")
        for (windowClass in WINDOW_CLASSES) append("    <windowClass>$windowClass</windowClass>\n")
        append("  </file>\n")
        append("</assembly>\n")
    }
}
