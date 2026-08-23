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

class WindowsVrPayloadManager(
    private val context: Context,
    private val diagnostics: WindowsVrDiagnostics,
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
        recoverSharedPayload(prefixDirectory)
        recoverOpenComposite(container, prefixDirectory)
        val runtime64 = File(prefixDirectory, "gamenative_openxr_runtime64.dll")
        val runtime32 = File(prefixDirectory, "gamenative_openxr_runtime32.dll")
        copyAssetIfChanged("gamenative_openxr_runtime64.dll", runtime64)
        copyAssetIfChanged("gamenative_openxr_runtime32.dll", runtime32)
        installSharedFile(
            "gamenative_openxr_runtime64.dll",
            File(container.rootDir, ".wine/drive_c/windows/system32/gamenative_openxr.dll"),
            prefixDirectory,
            "runtime64",
        )
        installSharedFile(
            "gamenative_openxr_runtime32.dll",
            File(container.rootDir, ".wine/drive_c/windows/syswow64/gamenative_openxr.dll"),
            prefixDirectory,
            "runtime32",
        )
        val winePath = ImageFs.find(context).winePath
        val bridge = File(winePath, "lib/wine/aarch64-windows/gamenative_xr_unixbridge.dll")
        check(bridge.parentFile?.exists() == true || bridge.parentFile?.mkdirs() == true)
        installSharedFile("gamenative_xr_unixbridge.dll", bridge, prefixDirectory, "bridge")
        val prefixBridge = File(container.rootDir, ".wine/drive_c/windows/system32/gamenative_xr_unixbridge.dll")
        installSharedFile("gamenative_xr_unixbridge.dll", prefixBridge, prefixDirectory, "bridgePrefix")
        val bridge32 = File(winePath, "lib/wine/i386-windows/gamenative_xr_unixbridge.dll")
        installSharedFile("gamenative_xr_unixbridge32.dll", bridge32, prefixDirectory, "bridge32")
        val prefixBridge32 = File(container.rootDir, ".wine/drive_c/windows/syswow64/gamenative_xr_unixbridge.dll")
        installSharedFile("gamenative_xr_unixbridge32.dll", prefixBridge32, prefixDirectory, "bridge32Prefix")
        val unixlib = File(winePath, "lib/wine/aarch64-unix/gamenative_xr_unixbridge.so")
        check(unixlib.parentFile?.exists() == true || unixlib.parentFile?.mkdirs() == true)
        installSharedFile("gamenative_xr_unixbridge.so", unixlib, prefixDirectory, "unixlib")
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
        val gameRoot = Container.drivesIterator(container.drives).asSequence()
            .firstOrNull { it[0].equals("A", ignoreCase = true) }
            ?.get(1)
            ?.let(::File)
            ?.canonicalFile
            ?: error("OpenComposite requires the launched game's A: drive")
        check(gameRoot.isDirectory)
        val candidates = gameRoot.walkTopDown()
            .onEnter { it.canonicalFile.path.startsWith(gameRoot.path + File.separator) || it.canonicalFile == gameRoot }
            .take(20001)
            .toList()
        check(candidates.size <= 20000) { "OpenComposite scan exceeded 20000 files" }
        val targets = candidates.filter { it.isFile && it.name.equals("openvr_api.dll", ignoreCase = true) }
        check(targets.isNotEmpty()) { "No openvr_api.dll was found under the launched game" }
        val adapter = context.assets.open("opencomposite_x64.dll").use { it.readBytes() }
        val record = File(File(container.rootDir, ".wine/drive_c/gamenative-xr"), "opencomposite.targets")
        val encodedTargets = targets.map { checkNotNull(it.parentFile).canonicalPath }
            .distinct()
            .joinToString("\n") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) }
        writeIfChanged(record, encodedTargets.toByteArray())
        openCompositeRecord = record
        targets.forEach { target ->
            check(peMachine(target.readBytes()) == 0x8664) { "OpenVR library is not x64: ${target.path}" }
            val directory = checkNotNull(target.parentFile).canonicalFile
            val backup = File(directory, "openvr_api.dll.gamenative-original")
            val owner = File(directory, "openvr_api.dll.gamenative-owner")
            val ini = File(directory, "opencomposite.ini")
            val iniBackup = File(directory, "opencomposite.ini.gamenative-original")
            val iniMissing = File(directory, "opencomposite.ini.gamenative-missing")
            check(!backup.exists() && !owner.exists())
            writeIfChanged(backup, target.readBytes())
            if (ini.isFile) writeIfChanged(iniBackup, ini.readBytes()) else writeIfChanged(iniMissing, byteArrayOf(1))
            writeIfChanged(owner, "2\n".toByteArray())
            writeIfChanged(target, adapter)
            writeIfChanged(ini, "initUsingVulkan=false\nlogAllOpenVRCalls=false\n".toByteArray())
            openCompositeDirectories += directory
            diagnostics.record("opencomposite", "installed path=${target.path}")
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

    private fun recoverSharedPayload(payloadDirectory: File) {
        listOf(
            "runtime64", "runtime32", "bridge", "bridgePrefix", "bridge32", "bridge32Prefix", "unixlib",
        ).forEach { name ->
            val backup = File(payloadDirectory, "$name.backup")
            val missing = File(payloadDirectory, "$name.missing")
            val targetRecord = File(payloadDirectory, "$name.target")
            val target = targetRecord.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
            if (target != null) {
                val canonicalTarget = target.canonicalFile
                validateSharedTarget(canonicalTarget)
                when {
                    backup.isFile -> atomicReplace(backup, canonicalTarget)
                    missing.isFile -> canonicalTarget.delete()
                }
            }
            backup.delete()
            missing.delete()
            targetRecord.delete()
        }
    }

    private fun recoverOpenComposite(container: Container, payloadDirectory: File) {
        val gameRoot = Container.drivesIterator(container.drives).asSequence()
            .firstOrNull { it[0].equals("A", ignoreCase = true) }
            ?.get(1)
            ?.let(::File)
            ?.canonicalFile
            ?: return
        if (!gameRoot.isDirectory) return
        val record = File(payloadDirectory, "opencomposite.targets")
        val recorded = record.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { value ->
            runCatching { File(String(Base64.getUrlDecoder().decode(value))).canonicalFile }.getOrNull()
        }
        val discovered = gameRoot.walkTopDown()
            .onEnter { it.canonicalFile.path.startsWith(gameRoot.path + File.separator) || it.canonicalFile == gameRoot }
            .take(20000)
            .filter { it.isFile && it.name == "openvr_api.dll.gamenative-owner" }
            .mapNotNull { it.parentFile }
            .toList()
        (recorded + discovered).distinctBy { it.path }.filter {
            it.path.startsWith(gameRoot.path + File.separator)
        }.forEach(::restoreOpenCompositeDirectory)
        record.delete()
    }

    private fun restoreOpenCompositeDirectory(directory: File) {
        val owner = File(directory, "openvr_api.dll.gamenative-owner")
        if (!owner.isFile || owner.readText().trim() != "2") return
        val target = File(directory, "openvr_api.dll")
        val backup = File(directory, "openvr_api.dll.gamenative-original")
        val ini = File(directory, "opencomposite.ini")
        val iniBackup = File(directory, "opencomposite.ini.gamenative-original")
        val iniMissing = File(directory, "opencomposite.ini.gamenative-missing")
        if (backup.isFile) atomicReplace(backup, target)
        if (iniBackup.isFile) atomicReplace(iniBackup, ini) else if (iniMissing.isFile) ini.delete()
        backup.delete()
        iniBackup.delete()
        iniMissing.delete()
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
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
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
