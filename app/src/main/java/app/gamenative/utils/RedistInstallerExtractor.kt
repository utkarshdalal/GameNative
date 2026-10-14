package app.gamenative.utils

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.gamefixes.CabArchive
import app.gamenative.gamefixes.LibarchiveCabEntryExtractor
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.FileUtils
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Supplies a vcrun wincomponent from the Visual C++ redistributable installer the game already
 * has on disk instead of the hosted archive. The installer is never run: every cabinet inside it
 * is unpacked (one level into members that are cabinets or carry cabinets), each PE member is
 * identified by its machine type and VERSIONINFO, and only the DLLs the wincomponent lists in
 * wincomponents.json with the family's major version are placed.
 *
 * Installer contract ([installersFor]) is the de-duplicated union, in this order, of:
 * - Container extra [INSTALLERS_EXTRA] (`redist_installers`): newline-separated absolute host
 *   paths of redistributable installers (.exe, .msi or .cab; .cmd/.bat are expanded as below).
 * - For Steam containers, the existing files named by `process N` of the Run Process steps in
 *   the game's install script and in every installscript.vdf under `_CommonRedist`. A .cmd or
 *   .bat step contributes every .exe/.msi it names (`%~dp0` and relative paths resolve against
 *   its own folder) plus every .exe/.msi next to it.
 * - Every .exe in the Steamworks layout `<gameDir>/_CommonRedist/vcredist/<year>/`.
 * Anything that is not .exe, .msi or .cab after expansion is dropped. Installers of any family
 * may be listed together; each is matched to a family by its own version resource and by the
 * versions of the DLLs inside it.
 */
object RedistInstallerExtractor {
    const val INSTALLERS_EXTRA = "redist_installers"

    private const val COMPONENTS_ASSET = "wincomponents/wincomponents.json"
    private const val WORK_DIR_NAME = "redist-installer-extract"
    private const val MAX_INSTALLER_BYTES = 200L * 1024 * 1024
    private const val MAX_EXTRACTED_BYTES = 512L * 1024 * 1024
    private const val MAX_PE_BYTES = 32L * 1024 * 1024
    private const val MAX_MEMBER_DEPTH = 2
    private const val PROBE_DIR_NAME = "redist-installer-probe"
    private const val MAX_PROBE_BYTES = 64L * 1024 * 1024
    private const val MAX_PROBE_MEMBERS = 6
    private const val MAX_SCRIPT_BYTES = 64 * 1024
    private val INSTALLER_EXTENSIONS = setOf("exe", "msi", "cab")
    private val SCRIPT_EXTENSIONS = setOf("cmd", "bat")
    private val SCRIPT_TARGET_EXTENSIONS = setOf("exe", "msi")
    private val SCRIPT_TOKEN = Regex("\"([^\"]+)\"|(\\S+)")
    private val SCRIPT_DIR_VARIABLE = Regex("(?i)%~dp0")
    private val MEMBER_PREFIXES = listOf("f_central_", "nosxs_", "ul_", "f_")
    private val MEMBER_SUFFIXES = listOf(".dll_amd64", ".dll_x86", "_amd64", "_x64", "_x86", ".dll")
    private const val CLR_SUFFIX = "_clr0400"
    private val INSTALLER_NAME_PREFIXES = listOf("vc_redist", "vcredist", "dxsetup", "oalinst", "physx")
    private val NON_VC_PRODUCT = Regex("(?i)\\.NET|Framework")
    private val NOT_A_VC_RUNTIME = Regex("(?i)\\.NET|Framework|DirectX|PhysX|OpenAL")
    private val VC_PRODUCT = Regex("(?i)Visual C\\+\\+ (20\\d\\d)")
    private val UNREAL_REDIST_PATH = listOf("Engine", "Extras", "Redist", "en-us")
    private val UNREAL_INSTALLERS = setOf(
        "vc_redist.x64.exe",
        "vc_redist.x86.exe",
        "ue4prereqsetup_x64.exe",
        "ue4prereqsetup_x86.exe",
        "ueprereqsetup_x64.exe",
        "ueprereqsetup_x86.exe",
    )

    internal val FAMILY_MAJOR_VERSIONS: Map<String, Int> = mapOf(
        "vcrun2005" to 8,
        "vcrun2008" to 9,
        "vcrun2010" to 10,
        "vcrun2012" to 11,
        "vcrun2013" to 12,
        "vcrun2022" to 14,
    )

    private val NEVER_PLACED = Regex("(?i)^(ucrtbase|api-ms-win-.*|mfc.*|vcruntime140_threads)\\.dll$")

    fun interface CabinetUnpacker {
        fun unpackAll(cabinet: File, destinationDir: File, maxBytes: Long): List<Pair<String, File>>
    }

    internal data class Candidate(val bytes: ByteArray, val name: String, val machine: Int, val version: Long)

    internal class InventoryEntry(val memberName: String, val info: PeVersionReader.Info, val bytes: ByteArray)

    private class Budget(var remaining: Long)

    private val inventories = ConcurrentHashMap<String, List<InventoryEntry>>()
    private val contentKeys = ConcurrentHashMap<String, String>()
    private val vc2022InstallerVersions = ConcurrentHashMap.newKeySet<Long>()
    private const val VC2022_FAMILY = "vcrun2022"
    private val VC_RUNTIME_DLLS = listOf("msvcp140.dll", "vcruntime140.dll")

    fun clearCache() {
        inventories.clear()
        contentKeys.clear()
        vc2022InstallerVersions.clear()
    }

    private fun observeInstaller(info: PeVersionReader.Info?) {
        val version = info?.fileVersion ?: return
        if (info.majorVersion == FAMILY_MAJOR_VERSIONS.getValue(VC2022_FAMILY)) vc2022InstallerVersions += version
    }

    internal fun vcRuntimeVersionFor(fileVersions: Collection<Long>): SteamInstallScriptRegistry.VcRuntimeVersion? {
        val major = FAMILY_MAJOR_VERSIONS.getValue(VC2022_FAMILY)
        return fileVersions
            .filter { (it ushr 48).toInt() == major }
            .maxOrNull()
            ?.let(SteamInstallScriptRegistry.VcRuntimeVersion::fromFileVersion)
    }

    /**
     * After vcrun2022 was placed (from a game installer or the hosted archive), records the highest
     * VC++ 2015-2022 version seen this launch in the prefix's VC Runtimes keys, so bootstrappers
     * comparing them against their bundled vc_redist do not prompt.
     */
    fun recordVcRuntime(identifier: String, windowsDir: File, prefixDir: File) {
        if (identifier != VC2022_FAMILY) return
        try {
            val placed = listOf("system32", "syswow64")
                .flatMap { dir -> VC_RUNTIME_DLLS.map { File(File(windowsDir, dir), it) } }
                .filter { it.isFile }
                .mapNotNull { PeVersionReader.read(it)?.fileVersion }
            val version = vcRuntimeVersionFor(placed + vc2022InstallerVersions) ?: return
            SteamInstallScriptRegistry.raiseVcRuntimeVersion(prefixDir, version)
        } catch (e: Exception) {
            Timber.w(e, "Failed to record the VC runtime version")
        }
    }

    private fun contentKey(installer: File): String {
        val path = runCatching { installer.canonicalPath }.getOrDefault(installer.absolutePath)
        return contentKeys.getOrPut("$path:${installer.length()}:${installer.lastModified()}") {
            val digest = MessageDigest.getInstance("SHA-256")
            installer.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            "${installer.length()}:${digest.digest().joinToString("") { "%02x".format(it) }}"
        }
    }

    fun placeComponent(context: Context, container: Container, identifier: String, windowsDir: File): Boolean {
        if (identifier !in FAMILY_MAJOR_VERSIONS) return false
        return try {
            val installers = installersFor(container, PreInstallSteps.getGameDir(container))
            if (installers.isEmpty()) {
                Timber.i("No redistributable installers on disk for $identifier, using hosted archive")
                return false
            }
            val components = JSONObject(FileUtils.readString(context, COMPONENTS_ASSET))
            val allowedByFamily = FAMILY_MAJOR_VERSIONS.keys.associateWith { allowedDlls(components, it) }
            val workDir = File(context.cacheDir, WORK_DIR_NAME)
            val placed = place(
                installers,
                identifier,
                allowedByFamily,
                windowsDir,
                workDir,
                unpacker = LibarchiveCabEntryExtractor::extractAll,
            )
            if (placed.isEmpty()) {
                Timber.i("Redistributable installers gave no $identifier DLLs, using hosted archive")
                false
            } else {
                Timber.i("Placed $identifier from game installers: ${placed.joinToString()}")
                true
            }
        } catch (e: Exception) {
            Timber.w(e, "Extracting $identifier from game installers failed, using hosted archive")
            false
        }
    }

    fun installersFor(container: Container, gameDir: File?): List<File> {
        val listed = container.getExtra(INSTALLERS_EXTRA, "")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::File)
        val usableGameDir = gameDir?.takeIf { it.isDirectory }
        val fromSteps = if (usableGameDir != null && isSteam(container)) {
            val scriptName = runCatching {
                SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(container.id))?.installScript
            }.getOrNull()
            scriptStepInstallers(usableGameDir, scriptName)
        } else {
            emptyList()
        }
        val fromLayout = usableGameDir?.let(::steamworksLayoutInstallers).orEmpty()
        if (listed.isNotEmpty()) Timber.d("Redistributable installers from container extra: $listed")
        if (fromSteps.isNotEmpty()) Timber.d("Redistributable installers from install scripts: $fromSteps")
        if (fromLayout.isNotEmpty()) Timber.d("Redistributable installers from _CommonRedist/vcredist: $fromLayout")
        return expandInstallers(listed + fromSteps + fromLayout)
    }

    fun scriptStepInstallers(gameDir: File, scriptName: String?): List<File> =
        expandInstallers(SteamInstallScriptRegistry.runProcessSteps(gameDir, scriptName).mapNotNull { it.file })

    fun familiesOf(
        installers: List<File>,
        workDir: File = File(System.getProperty("java.io.tmpdir") ?: ".", PROBE_DIR_NAME),
        unpacker: CabinetUnpacker = CabinetUnpacker(LibarchiveCabEntryExtractor::extractAll),
    ): Set<String> {
        val families = linkedSetOf<String>()
        for (installer in installers) {
            val info = PeVersionReader.read(installer) ?: continue
            if (isNotAVcRuntime(installer.name, info)) continue
            observeInstaller(info)
            val family = vcFamily(info)
                ?: if (installer.length() <= MAX_INSTALLER_BYTES) nestedFamily(installer, workDir, unpacker) else null
            if (family == null) {
                Timber.d("${installer.name} version ${info.majorVersion} is not a VC runtime")
                continue
            }
            Timber.i("${installer.name} is a $family installer")
            families += family
        }
        return families
    }

    private fun isNotAVcRuntime(name: String, info: PeVersionReader.Info): Boolean {
        val product = listOfNotNull(info.strings["ProductName"], info.strings["FileDescription"])
            .firstOrNull { NOT_A_VC_RUNTIME.containsMatchIn(it) }
            ?: return false
        Timber.d("$name installs '$product', not a VC runtime")
        return true
    }

    private fun vcFamily(info: PeVersionReader.Info): String? {
        val byMajor = info.majorVersion?.let { major -> FAMILY_MAJOR_VERSIONS.entries.firstOrNull { it.value == major }?.key }
        if (byMajor != null) return byMajor
        val year = VC_PRODUCT.find(info.strings["ProductName"].orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return VcRedistYears.component(year)
    }

    private fun nestedFamily(installer: File, workDir: File, unpacker: CabinetUnpacker): String? {
        workDir.deleteRecursively()
        return try {
            if (!workDir.mkdirs()) return null
            var budget = MAX_PROBE_BYTES
            var probed = 0
            for (location in CabArchive.locateAll(installer)) {
                if (budget <= 0 || probed >= MAX_PROBE_MEMBERS) break
                val cabinet = File(workDir, "probe-${location.offset}.cab")
                val members = try {
                    CabArchive.slice(installer, location, cabinet)
                    CabArchive.normalizeFileTable(cabinet)
                    unpacker.unpackAll(cabinet, File(workDir, "probe-${location.offset}"), budget)
                } catch (e: Exception) {
                    Timber.d(e, "Probing cabinet at ${location.offset} in ${installer.name} failed")
                    emptyList()
                } finally {
                    cabinet.delete()
                }
                budget -= members.sumOf { it.second.length() }
                val installers = members
                    .filter { (name, _) -> name.endsWith(".exe", ignoreCase = true) || name.endsWith(".msi", ignoreCase = true) }
                    .sortedBy { (name, _) -> if (name.endsWith(".exe", ignoreCase = true)) 0 else 1 }
                for ((memberName, member) in installers) {
                    if (probed >= MAX_PROBE_MEMBERS) break
                    probed++
                    if (!isPortableExecutable(member)) continue
                    val info = PeVersionReader.read(member) ?: continue
                    if (isNotAVcRuntime(memberName, info)) continue
                    vcFamily(info)?.let {
                        Timber.d("${installer.name} carries $memberName, a $it installer")
                        return it
                    }
                }
            }
            null
        } finally {
            workDir.deleteRecursively()
        }
    }

    internal fun expandInstallers(files: List<File>): List<File> {
        val seen = HashSet<String>()
        val result = ArrayList<File>()
        for (file in files.flatMap { if (it.extension.lowercase() in SCRIPT_EXTENSIONS) scriptTargets(it) else listOf(it) }) {
            if (!file.isFile || file.extension.lowercase() !in INSTALLER_EXTENSIONS) continue
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (seen.add(key)) result += file
        }
        return result
    }

    internal fun scriptTargets(script: File): List<File> {
        val dir = script.parentFile ?: return emptyList()
        val text = runCatching {
            script.inputStream().use { input ->
                val buffer = ByteArray(MAX_SCRIPT_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                String(buffer, 0, total, Charsets.ISO_8859_1)
            }
        }.getOrNull()
        val named = text.orEmpty().let { body ->
            SCRIPT_TOKEN.findAll(body)
                .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .filter { token -> SCRIPT_TARGET_EXTENSIONS.any { token.lowercase().endsWith(".$it") } }
                .mapNotNull { token -> resolveScriptPath(dir, token) }
                .toList()
        }
        val siblings = dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in SCRIPT_TARGET_EXTENSIONS }
            .filter { named.isNotEmpty() || looksLikeInstaller(it) }
            .sortedBy { it.name }
        Timber.d("${script.name} names $named, siblings $siblings")
        return named + siblings
    }

    internal fun looksLikeInstaller(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".msi") || INSTALLER_NAME_PREFIXES.any { name.startsWith(it) }
    }

    private fun resolveScriptPath(dir: File, token: String): File? {
        val relative = SCRIPT_DIR_VARIABLE.replace(token, "").trimStart('\\', '/')
        if (relative.contains('%') || (relative.length >= 2 && relative[1] == ':')) return null
        var current = dir
        for (segment in relative.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }) {
            if (segment == "..") return null
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
        }
        return current.takeIf { it.isFile }
    }

    private fun isSteam(container: Container): Boolean =
        runCatching { ContainerUtils.extractGameSourceFromContainerId(container.id) }.getOrNull() == GameSource.STEAM

    internal fun steamworksLayoutInstallers(gameDir: File): List<File> {
        val vcredist = childDir(gameDir, "_CommonRedist")?.let { childDir(it, "vcredist") } ?: return emptyList()
        return vcredist.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .flatMap { year ->
                year.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
                    .sortedBy { it.name }
            }
    }

    fun unrealLayoutInstallers(gameDir: File): List<File> {
        val redist = UNREAL_REDIST_PATH.fold<String, File?>(gameDir) { dir, name -> dir?.let { childDir(it, name) } }
            ?: return emptyList()
        return redist.listFiles().orEmpty()
            .filter { it.isFile && it.name.lowercase() in UNREAL_INSTALLERS }
            .sortedBy { it.name.lowercase() }
    }

    internal fun allowedDlls(components: JSONObject, family: String): Set<String> {
        val names = components.optJSONArray(family) ?: return emptySet()
        return (0 until names.length())
            .map { names.getString(it).lowercase() }
            .filterNot { it.endsWith(".exe") }
            .map { "$it.dll" }
            .filterNot { NEVER_PLACED.matches(it) }
            .toSet()
    }

    internal fun place(
        installers: List<File>,
        family: String,
        allowedByFamily: Map<String, Set<String>>,
        windowsDir: File,
        workDir: File,
        cache: MutableMap<String, List<InventoryEntry>> = inventories,
        unpacker: CabinetUnpacker,
    ): List<String> {
        val major = FAMILY_MAJOR_VERSIONS[family] ?: return emptyList()
        val allowed = allowedByFamily[family].orEmpty()
        if (allowed.isEmpty()) return emptyList()
        val inventoryStems = allowedByFamily.values.flatten().map { it.removeSuffix(".dll") }.toSet()
        val candidates = ArrayList<Candidate>()
        for (installer in installers) {
            if (!installer.isFile) {
                Timber.d("Skipping missing installer ${installer.absolutePath}")
                continue
            }
            if (installer.length() > MAX_INSTALLER_BYTES) {
                Timber.i("Skipping ${installer.name}, larger than $MAX_INSTALLER_BYTES bytes")
                continue
            }
            val installerInfo = PeVersionReader.read(installer)
            observeInstaller(installerInfo)
            val installerMajor = installerInfo?.majorVersion
            if (installerMajor != null && installerMajor in FAMILY_MAJOR_VERSIONS.values && installerMajor != major) {
                Timber.d("Skipping ${installer.name}, version $installerMajor is not $family")
                continue
            }
            val product = listOfNotNull(installerInfo?.strings?.get("ProductName"), installerInfo?.strings?.get("FileDescription"))
                .firstOrNull { NON_VC_PRODUCT.containsMatchIn(it) }
            if (product != null) {
                Timber.i("Skipping ${installer.name} for $family, it installs '$product'")
                continue
            }
            val inventory = cache.getOrPut(contentKey(installer)) { inventory(installer, inventoryStems, workDir, unpacker) }
            val found = inventory.mapNotNull { identify(it, family, major, allowed) }
            Timber.i("Found ${found.size} $family DLLs in ${installer.absolutePath}")
            candidates += found
        }
        val selected = select(candidates)
        for (candidate in selected) {
            val dir = if (candidate.machine == PeVersionReader.MACHINE_AMD64) "system32" else "syswow64"
            val destination = File(File(windowsDir, dir), candidate.name)
            destination.parentFile?.mkdirs()
            destination.writeBytes(candidate.bytes)
            Timber.d("Placed ${candidate.name} ${versionString(candidate.version)} into $dir")
        }
        return selected.map { it.name.removeSuffix(".dll") }.distinct()
    }

    private fun inventory(installer: File, stems: Set<String>, workDir: File, unpacker: CabinetUnpacker): List<InventoryEntry> {
        workDir.deleteRecursively()
        try {
            if (!workDir.mkdirs()) {
                Timber.w("Cannot create ${workDir.absolutePath}")
                return emptyList()
            }
            val entries = ArrayList<InventoryEntry>()
            try {
                collect(installer, 0, stems, workDir, unpacker, Budget(MAX_EXTRACTED_BYTES), entries)
            } catch (e: Exception) {
                Timber.w(e, "Unpacking ${installer.name} failed")
            }
            Timber.i("Unpacked ${installer.absolutePath}: ${entries.size} runtime DLLs")
            return entries
        } finally {
            workDir.deleteRecursively()
        }
    }

    internal fun select(candidates: List<Candidate>): List<Candidate> =
        candidates
            .groupBy { it.name to it.machine }
            .values
            .map { group -> group.maxBy { it.version } }
            .sortedWith(compareBy({ it.machine }, { it.name }))

    internal fun identify(entry: InventoryEntry, family: String, major: Int, allowed: Set<String>): Candidate? {
        val info = entry.info
        val allowedStems = allowed.map { it.removeSuffix(".dll") }.toSet()
        val stem = nameCandidates(info, entry.memberName).firstOrNull { it in allowedStems } ?: return null
        val name = "$stem.dll"
        if (NEVER_PLACED.matches(name)) return null
        val version = info.fileVersion ?: return null
        if (info.majorVersion != major) {
            Timber.d("Ignoring $name ${versionString(version)} from ${entry.memberName}, not $family")
            return null
        }
        return Candidate(entry.bytes, name, info.machine, version)
    }

    private fun inventoryEntry(file: File, memberName: String, stems: Set<String>): InventoryEntry? {
        val info = PeVersionReader.read(file) ?: return null
        if (info.machine != PeVersionReader.MACHINE_AMD64 && info.machine != PeVersionReader.MACHINE_I386) return null
        if (info.majorVersion !in FAMILY_MAJOR_VERSIONS.values) return null
        if (nameCandidates(info, memberName).none { it in stems }) return null
        if (info.hybrid) {
            Timber.d("Ignoring $memberName, ARM64EC/ARM64X build")
            return null
        }
        return InventoryEntry(memberName, info, file.readBytes())
    }

    internal fun nameCandidates(info: PeVersionReader.Info, memberName: String): List<String> {
        val original = info.strings["OriginalFilename"]?.let(::fileStem)
        val internal = info.strings["InternalName"]?.let(::fileStem)
        return listOfNotNull(
            original,
            internal,
            original?.removeSuffix(CLR_SUFFIX),
            internal?.removeSuffix(CLR_SUFFIX),
            normalizeMemberName(memberName),
        ).filter { it.isNotEmpty() }.distinct()
    }

    internal fun normalizeMemberName(memberName: String): String {
        var name = memberName.substringAfterLast('/').substringAfterLast('\\').lowercase()
        MEMBER_PREFIXES.firstOrNull { name.startsWith(it) }?.let { name = name.removePrefix(it) }
        val dll = name.indexOf(".dll.")
        if (dll > 0) name = name.substring(0, dll)
        MEMBER_SUFFIXES.firstOrNull { name.endsWith(it) }?.let { name = name.removeSuffix(it) }
        return name
    }

    private fun fileStem(name: String): String =
        name.trim().substringAfterLast('\\').lowercase().removeSuffix(".dll")

    private fun collect(
        source: File,
        depth: Int,
        stems: Set<String>,
        workDir: File,
        unpacker: CabinetUnpacker,
        budget: Budget,
        entries: MutableList<InventoryEntry>,
    ) {
        val cabinets = CabArchive.locateAll(source)
        if (cabinets.isNotEmpty()) Timber.d("${source.name}: ${cabinets.size} cabinets at depth $depth")
        for (location in cabinets) {
            if (budget.remaining <= 0) {
                Timber.w("Extraction budget used up, stopping at ${source.name}")
                return
            }
            val slot = File(workDir, "d$depth-${location.offset}-${System.nanoTime()}")
            val cabinet = File("${slot.path}.cab")
            val members = try {
                CabArchive.slice(source, location, cabinet)
                if (CabArchive.normalizeFileTable(cabinet)) {
                    Timber.i("Collapsed aliased entries of the cabinet at ${location.offset} in ${source.name}")
                }
                unpacker.unpackAll(cabinet, slot, budget.remaining)
            } catch (e: Exception) {
                Timber.w(e, "Cabinet at ${location.offset} in ${source.name} could not be unpacked")
                emptyList()
            } finally {
                cabinet.delete()
            }
            if (members.isEmpty()) Timber.w("Cabinet at ${location.offset} in ${source.name} gave no entries")
            budget.remaining -= members.sumOf { it.second.length() }
            for ((memberName, member) in members) {
                val isPe = member.length() <= MAX_PE_BYTES && isPortableExecutable(member)
                val entry = if (isPe) inventoryEntry(member, memberName, stems) else null
                if (entry != null) {
                    entries += entry
                } else if (depth < MAX_MEMBER_DEPTH) {
                    try {
                        collect(member, depth + 1, stems, workDir, unpacker, budget, entries)
                    } catch (e: Exception) {
                        Timber.d(e, "Scanning $memberName from ${source.name} failed")
                    }
                }
                member.delete()
            }
        }
    }

    private fun isPortableExecutable(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(2)
            input.read(header) == 2 && header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()
        }
    }.getOrDefault(false)

    private fun childDir(parent: File, name: String): File? =
        parent.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }

    private fun versionString(version: Long): String =
        listOf(version ushr 48, (version ushr 32) and 0xFFFF, (version ushr 16) and 0xFFFF, version and 0xFFFF)
            .joinToString(".")
}
