package app.gamenative.ui.screen.xr.windows

import android.content.Context
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

private const val OPEN_COMPOSITE_SCAN_LIMIT = 200000

class WindowsVrPayloadManager(
    private val context: Context,
    private val diagnostics: WindowsVrDiagnostics,
    private val readAsset: (String) -> ByteArray = { name -> context.assets.open(name).use { it.readBytes() } },
) {
    data class PreparedPayload(val prefixDirectory: File, val manifest: File)
    private data class RegistryMutation(val registry: File, val backup: File, val missing: File)
    private data class FileMutation(val target: File, val backup: File, val missing: File, val targetRecord: File)
    private val registryMutations = mutableListOf<RegistryMutation>()
    private val fileMutations = mutableListOf<FileMutation>()
    private val openCompositeDirectories = mutableListOf<File>()
    private var openCompositeRecord: File? = null
    private var activeMarker: File? = null

    fun prepare(container: Container): PreparedPayload {
        val prefixDirectory = File(container.rootDir, ".wine/drive_c/gamenative-xr")
        check(prefixDirectory.exists() || prefixDirectory.mkdirs())
        recoverRegistry(container, prefixDirectory)
        recoverSharedPayload(container, prefixDirectory)
        recoverOpenComposite(container, prefixDirectory)
        val runtime64 = File(prefixDirectory, "gamenative_openxr_runtime64.dll")
        val runtime32 = File(prefixDirectory, "gamenative_openxr_runtime32.dll")
        copyAssetIfChanged("gamenative_openxr_runtime64.dll", runtime64)
        copyAssetIfChanged("gamenative_openxr_runtime32.dll", runtime32)
        val targets = sharedTargets(container)
        val bridge = targets.getValue("bridge")
        val bridge32 = targets.getValue("bridge32")
        val unixlib = targets.getValue("unixlib")
        check(bridge.parentFile?.exists() == true || bridge.parentFile?.mkdirs() == true)
        check(unixlib.parentFile?.exists() == true || unixlib.parentFile?.mkdirs() == true)
        sharedAssets.forEach { (name, assetPath) ->
            installSharedFile(assetPath, targets.getValue(name), prefixDirectory, name)
        }
        val manifest = File(prefixDirectory, "active_runtime.json")
        val manifest64 = File(prefixDirectory, "active_runtime64.json")
        val manifest32 = File(prefixDirectory, "active_runtime32.json")
        val commonJson = "{\"file_format_version\":\"1.0.0\",\"runtime\":{\"library_path\":\"C:\\\\windows\\\\system32\\\\gamenative_openxr.dll\",\"name\":\"GameNative Windows OpenXR\"}}"
        val json64 = "{\"file_format_version\":\"1.0.0\",\"runtime\":{\"library_path\":\"C:\\\\gamenative-xr\\\\gamenative_openxr_runtime64.dll\",\"name\":\"GameNative Windows OpenXR\"}}"
        val json32 = "{\"file_format_version\":\"1.0.0\",\"runtime\":{\"library_path\":\"C:\\\\gamenative-xr\\\\gamenative_openxr_runtime32.dll\",\"name\":\"GameNative Windows OpenXR\"}}"
        writeIfChanged(manifest, commonJson.toByteArray())
        writeIfChanged(manifest64, json64.toByteArray())
        writeIfChanged(manifest32, json32.toByteArray())
        val marker = File(prefixDirectory, "payload.version")
        copyAssetIfChanged("payload.version", marker)
        installRegistry(container, prefixDirectory)
        diagnostics.record("payload", "prepared path=${prefixDirectory.path} runtime64=${runtime64.length()} runtime32=${runtime32.length()} bridge64=${bridge.length()} bridge32=${bridge32.length()} unixlib=${unixlib.length()} manifest=${manifest.length()}")
        return PreparedPayload(prefixDirectory, manifest)
    }

    fun installOpenComposite(container: Container) {
        val gameRoot = launchedGameRoot(container) ?: error("OpenComposite requires the launched game's A: drive")
        check(gameRoot.isDirectory)
        val payloadDirectory = File(container.rootDir, ".wine/drive_c/gamenative-xr")
        val cache = File(payloadDirectory, "opencomposite.cache")
        val cachedTargets = cachedOpenCompositeFiles(cache, gameRoot)?.let { files ->
            openCompositeTargets(files).takeIf { it.size == files.size }
        }
        val targets = cachedTargets ?: openCompositeTargets(scanOpenComposite(gameRoot))
        diagnostics.record("opencomposite", "targets=${targets.size} source=${if (cachedTargets != null) "cache" else "scan"}")
        check(targets.isNotEmpty()) { "No x64 or x86 openvr_api.dll was found under the launched game" }
        val adapters = targets.map { it.second }.distinct().associateWith(readAsset)
        val record = File(payloadDirectory, "opencomposite.targets")
        val directories = targets.map { checkNotNull(it.first.parentFile).canonicalPath }.distinct()
        writeIfChanged(record, directories.joinToString("\n", transform = ::encodePath).toByteArray())
        writeIfChanged(cache, (listOf(gameRoot.path) + directories).joinToString("\n", transform = ::encodePath).toByteArray())
        openCompositeRecord = record
        targets.forEach { (target, adapterName) ->
            val adapter = checkNotNull(adapters[adapterName])
            val directory = checkNotNull(target.parentFile).canonicalFile
            val backup = File(directory, "openvr_api.dll.gamenative-original")
            val owner = File(directory, "openvr_api.dll.gamenative-owner")
            check(!backup.exists() && !owner.exists())
            writeIfChanged(owner, "2\n".toByteArray())
            openCompositeDirectories += directory
            writeIfChanged(backup, target.readBytes())
            writeIfChanged(target, adapter)
            if (adapterName == "opencomposite_x86.dll") installVulkanInitConfig(directory)
            diagnostics.record("opencomposite", "installed path=${target.path} adapter=$adapterName")
        }
    }

    fun restore() {
        registryMutations.asReversed().forEach { mutation ->
            when {
                mutation.backup.isFile -> {
                    atomicReplace(mutation.backup, mutation.registry)
                }
                mutation.missing.isFile -> mutation.registry.delete()
            }
            mutation.backup.delete()
            mutation.missing.delete()
        }
        registryMutations.clear()
        fileMutations.asReversed().forEach { mutation ->
            when {
                mutation.backup.isFile -> atomicReplace(mutation.backup, mutation.target)
                mutation.missing.isFile -> mutation.target.delete()
            }
            mutation.backup.delete()
            mutation.missing.delete()
            mutation.targetRecord.delete()
        }
        fileMutations.clear()
        openCompositeDirectories.asReversed().forEach(::restoreOpenCompositeDirectory)
        openCompositeDirectories.clear()
        openCompositeRecord?.delete()
        openCompositeRecord = null
        activeMarker?.delete()
        activeMarker = null
        diagnostics.record("payload", "restored")
    }

    private fun recoverRegistry(container: Container, payloadDirectory: File) {
        listOf("system.reg", "user.reg").forEach { name ->
            val registry = File(container.rootDir, ".wine/$name")
            val backup = File(payloadDirectory, "$name.backup")
            val missing = File(payloadDirectory, "$name.missing")
            when {
                backup.isFile -> atomicReplace(backup, registry)
                missing.isFile -> registry.delete()
            }
            backup.delete()
            missing.delete()
        }
        File(payloadDirectory, "registry.active").delete()
    }

    private fun installRegistry(container: Container, payloadDirectory: File) {
        val runtimePath64 = "C:\\\\gamenative-xr\\\\active_runtime64.json"
        val runtimePath32 = "C:\\\\gamenative-xr\\\\active_runtime32.json"
        val sections = """

[Software\\Khronos\\OpenXR\\1]
"ActiveRuntime"="$runtimePath64"

[Software\\Wow6432Node\\Khronos\\OpenXR\\1]
"ActiveRuntime"="$runtimePath32"
""".trimIndent().toByteArray()
        listOf("system.reg", "user.reg").forEach { name ->
            val registry = File(container.rootDir, ".wine/$name")
            val backup = File(payloadDirectory, "$name.backup")
            val missing = File(payloadDirectory, "$name.missing")
            if (registry.isFile) {
                writeIfChanged(backup, registry.readBytes())
            } else {
                writeIfChanged(missing, byteArrayOf(1))
            }
            val existing = if (registry.isFile) registry.readBytes() else "WINE REGISTRY Version 2\n".toByteArray()
            writeIfChanged(registry, existing + sections)
            registryMutations += RegistryMutation(registry, backup, missing)
        }
        activeMarker = File(payloadDirectory, "registry.active").also { writeIfChanged(it, "2\n".toByteArray()) }
        diagnostics.record("registry", "installed views=64,32")
    }

    private val sharedAssets = linkedMapOf(
        "runtime64" to "gamenative_openxr_runtime64.dll",
        "runtime32" to "gamenative_openxr_runtime32.dll",
        "bridge" to "gamenative_xr_unixbridge.dll",
        "bridgePrefix" to "gamenative_xr_unixbridge.dll",
        "bridge32" to "gamenative_xr_unixbridge32.dll",
        "bridge32Prefix" to "gamenative_xr_unixbridge32.dll",
        "unixlib" to "gamenative_xr_unixbridge.so",
    )

    private fun sharedTargets(container: Container): Map<String, File> {
        val winePath = ImageFs.find(context).winePath
        return mapOf(
            "runtime64" to File(container.rootDir, ".wine/drive_c/windows/system32/gamenative_openxr.dll"),
            "runtime32" to File(container.rootDir, ".wine/drive_c/windows/syswow64/gamenative_openxr.dll"),
            "bridge" to File(winePath, "lib/wine/aarch64-windows/gamenative_xr_unixbridge.dll"),
            "bridgePrefix" to File(container.rootDir, ".wine/drive_c/windows/system32/gamenative_xr_unixbridge.dll"),
            "bridge32" to File(winePath, "lib/wine/i386-windows/gamenative_xr_unixbridge.dll"),
            "bridge32Prefix" to File(container.rootDir, ".wine/drive_c/windows/syswow64/gamenative_xr_unixbridge.dll"),
            "unixlib" to File(winePath, "lib/wine/aarch64-unix/gamenative_xr_unixbridge.so"),
        )
    }

    private fun recoverSharedPayload(container: Container, payloadDirectory: File) {
        val targets = sharedTargets(container)
        sharedAssets.keys.forEach { name ->
            val backup = File(payloadDirectory, "$name.backup")
            val missing = File(payloadDirectory, "$name.missing")
            val targetRecord = File(payloadDirectory, "$name.target")
            val canonicalTarget = targets.getValue(name).canonicalFile
            val recorded = targetRecord.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)?.canonicalFile
            val restoreTarget = recorded?.takeIf {
                it == canonicalTarget && runCatching { validateSharedTarget(it) }.isSuccess
            }
            if (restoreTarget != null) {
                when {
                    backup.isFile -> atomicReplace(backup, restoreTarget)
                    missing.isFile -> restoreTarget.delete()
                }
            } else if (recorded != null) {
                diagnostics.record("payload", "ignoring unexpected recovery record for $name")
            }
            backup.delete()
            missing.delete()
            targetRecord.delete()
        }
    }

    private fun recoverOpenComposite(container: Container, payloadDirectory: File) {
        val gameRoot = launchedGameRoot(container) ?: return
        if (!gameRoot.isDirectory) return
        val record = File(payloadDirectory, "opencomposite.targets")
        val recorded = record.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull(::decodePath)
        recorded.distinctBy { it.path }.filter {
            it.path.startsWith(gameRoot.path + File.separator)
        }.forEach(::restoreOpenCompositeDirectory)
        record.delete()
    }

    private fun launchedGameRoot(container: Container): File? {
        return Container.drivesIterator(container.drives).asSequence()
            .firstOrNull { it[0].equals("A", ignoreCase = true) }
            ?.get(1)
            ?.let(::File)
            ?.canonicalFile
    }

    private fun cachedOpenCompositeFiles(cache: File, gameRoot: File): List<File>? {
        val lines = cache.takeIf { it.isFile }?.readLines()?.filter(String::isNotEmpty) ?: return null
        val paths = lines.map { decodePath(it) ?: return null }
        if (paths.size < 2 || paths.first() != gameRoot) return null
        val directories = paths.drop(1)
        val valid = directories.all {
            it.path.startsWith(gameRoot.path + File.separator) &&
                (File(it, "openvr_api.dll").isFile || File(it, "openvr_api.dll.gamenative-original").isFile)
        }
        return if (valid) directories.map { File(it, "openvr_api.dll") } else null
    }

    private fun scanOpenComposite(gameRoot: File): List<File> {
        var scanned = 0
        return gameRoot.walkTopDown()
            .onEnter { it.canonicalFile.path.startsWith(gameRoot.path + File.separator) || it.canonicalFile == gameRoot }
            .onEach { check(++scanned <= OPEN_COMPOSITE_SCAN_LIMIT) { "OpenComposite scan exceeded $OPEN_COMPOSITE_SCAN_LIMIT files" } }
            .filter { it.isFile && it.name.equals("openvr_api.dll", ignoreCase = true) }
            .toList()
    }

    private fun openCompositeTargets(candidates: List<File>): List<Pair<File, String>> {
        val adapterAssets = mapOf(0x8664 to "opencomposite_x64.dll", 0x14c to "opencomposite_x86.dll")
        return candidates.mapNotNull { file ->
            runCatching { peMachineOf(file) }.getOrNull()?.let { machine -> adapterAssets[machine]?.let { file to it } }
        }
    }

    private fun encodePath(path: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray())

    private fun decodePath(value: String): File? = runCatching { File(String(Base64.getUrlDecoder().decode(value))).canonicalFile }.getOrNull()

    private fun installVulkanInitConfig(directory: File) {
        val config = File(directory, "opencomposite.ini")
        val owner = File(directory, "opencomposite.ini.gamenative-owner")
        if (config.exists() && !owner.isFile) return
        writeIfChanged(owner, "1\n".toByteArray())
        writeIfChanged(config, "initUsingVulkan=true\r\n".toByteArray())
    }

    private fun restoreOpenCompositeDirectory(directory: File) {
        val configOwner = File(directory, "opencomposite.ini.gamenative-owner")
        if (configOwner.isFile) {
            File(directory, "opencomposite.ini").delete()
            configOwner.delete()
        }
        val owner = File(directory, "openvr_api.dll.gamenative-owner")
        if (!owner.isFile || owner.readText().trim() != "2") return
        val target = File(directory, "openvr_api.dll")
        val backup = File(directory, "openvr_api.dll.gamenative-original")
        if (backup.isFile) atomicReplace(backup, target)
        backup.delete()
        owner.delete()
        diagnostics.record("opencomposite", "restored path=${target.path}")
    }

    private fun installSharedFile(assetPath: String, target: File, payloadDirectory: File, name: String) {
        val backup = File(payloadDirectory, "$name.backup")
        val missing = File(payloadDirectory, "$name.missing")
        val targetRecord = File(payloadDirectory, "$name.target")
        val canonicalTarget = target.canonicalFile
        validateSharedTarget(canonicalTarget)
        if (canonicalTarget.isFile) writeIfChanged(backup, canonicalTarget.readBytes()) else writeIfChanged(missing, byteArrayOf(1))
        writeIfChanged(targetRecord, canonicalTarget.path.toByteArray())
        copyAssetIfChanged(assetPath, canonicalTarget)
        fileMutations += FileMutation(canonicalTarget, backup, missing, targetRecord)
    }

    private fun validateSharedTarget(target: File) {
        val roots = listOfNotNull(
            ImageFs.find(context).rootDir,
            context.filesDir,
            context.getExternalFilesDir(null),
        ).map { it.canonicalFile }
        check(roots.any { target.path.startsWith(it.path + File.separator) })
    }

    private fun copyAssetIfChanged(assetPath: String, destination: File) {
        val bytes = readAsset(assetPath)
        writeIfChanged(destination, bytes)
        diagnostics.record("payload-file", "${destination.name} size=${bytes.size} sha256=${sha256(bytes)}")
    }

    private fun writeIfChanged(destination: File, bytes: ByteArray) {
        check(destination.parentFile?.exists() == true || destination.parentFile?.mkdirs() == true)
        if (destination.isFile && destination.readBytes().contentEquals(bytes)) return
        val temporary = File(destination.parentFile, "${destination.name}.new")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        replaceFile(temporary, destination)
    }

    private fun atomicReplace(source: File, destination: File) {
        val temporary = File(destination.parentFile, "${destination.name}.restore")
        source.copyTo(temporary, overwrite = true)
        FileOutputStream(temporary, true).use { it.fd.sync() }
        replaceFile(temporary, destination)
    }

    private fun replaceFile(source: File, destination: File) {
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            check(source.renameTo(destination) || run {
                destination.delete()
                source.renameTo(destination)
            })
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun peMachineOf(file: File): Int {
        java.io.RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(0x40)
            input.readFully(header)
            check(header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte())
            val offset = (header[0x3c].toInt() and 0xff) or
                ((header[0x3d].toInt() and 0xff) shl 8) or
                ((header[0x3e].toInt() and 0xff) shl 16) or
                ((header[0x3f].toInt() and 0xff) shl 24)
            check(offset >= 0x40 && offset.toLong() + 6 <= input.length())
            input.seek(offset.toLong())
            val pe = ByteArray(6)
            input.readFully(pe)
            check(pe[0] == 'P'.code.toByte() && pe[1] == 'E'.code.toByte() && pe[2] == 0.toByte() && pe[3] == 0.toByte())
            return (pe[4].toInt() and 0xff) or ((pe[5].toInt() and 0xff) shl 8)
        }
    }

    private fun peMachine(bytes: ByteArray): Int {
        check(bytes.size >= 256)
        val offset = (bytes[0x3c].toInt() and 0xff) or
            ((bytes[0x3d].toInt() and 0xff) shl 8) or
            ((bytes[0x3e].toInt() and 0xff) shl 16) or
            ((bytes[0x3f].toInt() and 0xff) shl 24)
        check(offset >= 0x40 && offset + 6 <= bytes.size)
        return (bytes[offset + 4].toInt() and 0xff) or ((bytes[offset + 5].toInt() and 0xff) shl 8)
    }
}
