package app.gamenative.workshop.compatibility

import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object SlayTheSpireModTheSpireCompatibility {
    const val APP_ID = 646570
    const val HEADLESS_LAUNCHER_JAR = "GameNativeModTheSpireLauncher.jar"

    private const val MOD_THE_SPIRE_WORKSHOP_ID = "1605060445"
    private const val MOD_JAR_MANIFEST = ".gamenative_workshop_jars"
    private const val MOD_THE_SPIRE_MARKER = ".gamenative_modthespire"
    private const val MOD_THE_SPIRE_ARGS = ".gamenative_modthespire_args"
    private const val HEADLESS_LAUNCHER_CLASS =
        "com.evacipated.cardcrawl.modthespire.GameNativeLauncher"
    private const val MAX_METADATA_ENTRY_BYTES = 64 * 1024
    private val MOD_THE_SPIRE_JVM_ARGS = listOf(
        "-Xmx1G",
        "-Dsun.java2d.dpiaware=true",
        "-Dsun.java2d.d3d=false",
        "-Dsun.java2d.noddraw=true",
    )

    data class LaunchConfig(
        val executablePath: String,
        val exeCommandLine: String,
        val exeRunDirOverride: String,
    )

    fun resolveLaunchConfig(
        gameRootDir: File,
        fallbackCommandLine: String,
    ): LaunchConfig? {
        if (!isLauncherReady(gameRootDir)) return null

        val managedModIds = readManagedModIds(gameRootDir)
        if (!File(gameRootDir, "ModTheSpire.jar").isFile || managedModIds.isEmpty()) {
            return null
        }

        val gameRootColdClientPath = "steamapps\\common\\${gameRootDir.name}"
        return LaunchConfig(
            executablePath = "jre/bin/java.exe",
            exeCommandLine = buildCommandLine(
                jarArgument = "ModTheSpire.jar",
                modIds = managedModIds,
                fallbackCommandLine = fallbackCommandLine,
            ),
            exeRunDirOverride = gameRootColdClientPath,
        )
    }

    fun cleanupManagedWorkshopFiles(gameRootDir: File) {
        val modsDir = File(gameRootDir, "mods")
        val manifestFile = File(modsDir, MOD_JAR_MANIFEST)
        cleanupManagedJarFiles(modsDir, manifestFile)
        try {
            Files.deleteIfExists(manifestFile.toPath())
            if (modsDir.isDirectory && modsDir.listFiles()?.isEmpty() == true) {
                modsDir.delete()
            }
        } catch (_: Exception) { }
        cleanupManagedLauncher(gameRootDir)
    }

    fun configureModTheSpireLayout(
        gameRootDir: File,
        modDirs: List<File>,
        headlessLauncherJarBase64: String,
    ) {
        val modTheSpireDir = modDirs.firstOrNull {
            it.name == MOD_THE_SPIRE_WORKSHOP_ID
        } ?: return
        val modTheSpireJar = File(modTheSpireDir, "ModTheSpire.jar")
            .takeIf { it.isFile }
            ?: workshopJarPayloads(modTheSpireDir)
                .firstOrNull { it.name.equals("ModTheSpire.jar", ignoreCase = true) }
            ?: return

        materializeWorkshopFile(modTheSpireJar, File(gameRootDir, "ModTheSpire.jar"))
        writeHeadlessLauncher(gameRootDir, headlessLauncherJarBase64)
        File(gameRootDir, MOD_THE_SPIRE_MARKER)
            .writeText(modTheSpireJar.absolutePath)

        val modsDir = File(gameRootDir, "mods")
        val managedFiles = mutableListOf<String>()
        var currentOutFile: File? = null
        try {
            modDirs
                .filter { it.name != MOD_THE_SPIRE_WORKSHOP_ID }
                .sortedBy { it.name.toLongOrNull() ?: Long.MAX_VALUE }
                .forEach { itemDir ->
                    workshopJarPayloads(itemDir).forEach { jarFile ->
                        val outName = managedJarName(itemDir.name, jarFile.name)
                        val outFile = File(modsDir, outName)
                        currentOutFile = outFile
                        if (materializeModJar(jarFile, outFile)) {
                            managedFiles += outName
                            currentOutFile = null
                        }
                    }
                }

            val manifestFile = File(modsDir, MOD_JAR_MANIFEST)
            if (managedFiles.isNotEmpty()) {
                manifestFile.writeText(managedFiles.joinToString("\n", postfix = "\n"))
            } else {
                try {
                    Files.deleteIfExists(manifestFile.toPath())
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            (managedFiles.map { File(modsDir, it) } + listOfNotNull(currentOutFile))
                .forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
            throw e
        }

        Timber.tag("WorkshopManager").i(
            "Configured Slay the Spire ModTheSpire root layout: " +
                "${managedFiles.size} managed mod jar(s)"
        )
    }

    private fun isLauncherReady(gameRootDir: File): Boolean {
        if (!File(gameRootDir, "jre/bin/java.exe").isFile) return false
        if (!File(gameRootDir, HEADLESS_LAUNCHER_JAR).isFile) return false
        return File(gameRootDir, "ModTheSpire.jar").isFile &&
            readManagedModIds(gameRootDir).isNotEmpty()
    }

    private fun buildCommandLine(
        jarArgument: String,
        modIds: List<String>,
        fallbackCommandLine: String,
    ): String = buildString {
        val classPath = listOf(
            HEADLESS_LAUNCHER_JAR,
            jarArgument,
        ).joinToString(";")
        append(MOD_THE_SPIRE_JVM_ARGS.joinToString(" "))
        append(' ')
        append("-cp ")
        append(quoteWindowsArgument(classPath))
        append(' ')
        append(HEADLESS_LAUNCHER_CLASS)
        append(" --mods ")
        append(modIds.joinToString(","))
        if (fallbackCommandLine.isNotBlank()) {
            append(' ')
            append(fallbackCommandLine)
        }
    }

    private fun readManagedModIds(gameRootDir: File): List<String> {
        val modsDir = File(gameRootDir, "mods")
        val manifestFile = File(modsDir, MOD_JAR_MANIFEST)
        if (!manifestFile.isFile) return emptyList()

        return manifestFile.readText().lineSequence()
            .mapNotNull { fileName -> managedManifestFile(modsDir, fileName) }
            .filter { it.isFile }
            .mapNotNull { readModId(it) }
            .distinct()
            .toList()
    }

    private fun cleanupManagedJarFiles(modsDir: File, manifestFile: File) {
        if (manifestFile.isFile) {
            manifestFile.readText().lineSequence()
                .mapNotNull { fileName -> managedManifestFile(modsDir, fileName) }
                .forEach { jarFile ->
                    try {
                        Files.deleteIfExists(jarFile.toPath())
                    } catch (_: Exception) { }
                }
        }
    }

    private fun managedManifestFile(modsDir: File, manifestEntry: String): File? {
        val fileName = manifestEntry.trim()
        if (fileName.isEmpty()) return null

        val modsPath = modsDir.toPath().toAbsolutePath().normalize()
        val filePath = modsPath.resolve(fileName).normalize()
        if (!filePath.startsWith(modsPath) || filePath.parent != modsPath) {
            Timber.tag("WorkshopManager").w(
                "Ignoring unsafe Slay the Spire managed mod manifest entry: $fileName"
            )
            return null
        }
        return filePath.toFile()
    }

    private fun cleanupManagedLauncher(gameRootDir: File) {
        val marker = File(gameRootDir, MOD_THE_SPIRE_MARKER)
        if (!marker.isFile) return
        listOf(
            File(gameRootDir, "ModTheSpire.jar"),
            File(gameRootDir, HEADLESS_LAUNCHER_JAR),
            marker,
            File(gameRootDir, MOD_THE_SPIRE_ARGS),
        ).forEach { file ->
            try {
                Files.deleteIfExists(file.toPath())
            } catch (_: Exception) { }
        }
    }

    private fun writeHeadlessLauncher(gameRootDir: File, headlessLauncherJarBase64: String) {
        val launcherFile = File(gameRootDir, HEADLESS_LAUNCHER_JAR)
        launcherFile.parentFile?.mkdirs()
        Files.write(
            launcherFile.toPath(),
            Base64.getDecoder().decode(headlessLauncherJarBase64),
        )
    }

    private fun materializeModJar(source: File, destination: File): Boolean =
        if (jarNeedsMetadataBomFix(source)) {
            rewriteJarWithoutMetadataBom(source, destination)
        } else {
            materializeWorkshopFile(source, destination)
        }

    private fun materializeWorkshopFile(source: File, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        try {
            Files.deleteIfExists(destination.toPath())
        } catch (_: Exception) { }

        val srcPath = source.toPath()
        val destPath = destination.toPath()

        return try {
            Files.createLink(destPath, srcPath)
            true
        } catch (_: Exception) {
            Files.copy(srcPath, destPath)
            true
        }
    }

    private fun jarNeedsMetadataBomFix(source: File): Boolean = runCatching {
        ZipFile(source).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && isMetadataEntry(it.name) }
                .any { entry ->
                    zip.getInputStream(entry).use { input ->
                        val header = ByteArray(3)
                        input.read(header) == 3 &&
                            header[0] == 0xEF.toByte() &&
                            header[1] == 0xBB.toByte() &&
                            header[2] == 0xBF.toByte()
                    }
                }
        }
    }.getOrDefault(false)

    private fun rewriteJarWithoutMetadataBom(source: File, destination: File): Boolean {
        val destinationPath = destination.toPath()
        val tempDir = destination.parentFile?.toPath()
            ?: destinationPath.toAbsolutePath().parent
            ?: return false
        Files.createDirectories(tempDir)
        val tempPath = Files.createTempFile(tempDir, "${destination.name}.", ".tmp")

        try {
            ZipInputStream(source.inputStream()).use { input ->
                ZipOutputStream(Files.newOutputStream(tempPath)).use { output ->
                    var entry = input.nextEntry
                    while (entry != null) {
                        val outEntry = ZipEntry(entry.name).apply {
                            time = entry.time
                            comment = entry.comment
                        }
                        output.putNextEntry(outEntry)
                        if (!entry.isDirectory) {
                            if (isMetadataEntry(entry.name)) {
                                copyEntryWithoutUtf8Bom(input, output)
                            } else {
                                input.copyTo(output)
                            }
                        }
                        output.closeEntry()
                        input.closeEntry()
                        entry = input.nextEntry
                    }
                }
            }
            moveReplacing(tempPath, destinationPath)
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(tempPath)
            } catch (_: Exception) { }
            throw e
        }
        return true
    }

    private fun moveReplacing(source: Path, destination: Path) {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun copyEntryWithoutUtf8Bom(input: ZipInputStream, output: ZipOutputStream) {
        val header = ByteArray(3)
        val read = input.read(header)
        val hasUtf8Bom = read == 3 &&
            header[0] == 0xEF.toByte() &&
            header[1] == 0xBB.toByte() &&
            header[2] == 0xBF.toByte()
        if (!hasUtf8Bom && read > 0) {
            output.write(header, 0, read)
        }
        input.copyTo(output)
    }

    private fun readModId(jarFile: File): String? = runCatching {
        ZipFile(jarFile).use { zip ->
            val jsonEntry = zip.getEntry("ModTheSpire.json")
                ?: zip.entries().asSequence()
                    .firstOrNull { !it.isDirectory && it.name.endsWith("/ModTheSpire.json") }
            if (jsonEntry != null) {
                val jsonText = readZipEntryText(zip, jsonEntry)
                    ?.trimStart('\uFEFF')
                    ?: return@use null
                return@use JSONObject(jsonText)
                    .optString("modid")
                    .takeIf { it.isNotBlank() }
            }

            val configEntry = zip.getEntry("ModTheSpire.config")
                ?: zip.entries().asSequence()
                    .firstOrNull { !it.isDirectory && it.name.endsWith("/ModTheSpire.config") }
            if (configEntry != null) {
                val configText = readZipEntryText(zip, configEntry)
                    ?: return@use null
                return@use configText.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("ID=", ignoreCase = true) }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }

            null
        }
    }.getOrNull()

    private fun readZipEntryText(zip: ZipFile, entry: ZipEntry): String? {
        if (entry.size > MAX_METADATA_ENTRY_BYTES) {
            Timber.tag("WorkshopManager").w(
                "Skipping oversized Slay the Spire mod metadata entry: ${entry.name}"
            )
            return null
        }

        return zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                if (totalBytes > MAX_METADATA_ENTRY_BYTES) {
                    Timber.tag("WorkshopManager").w(
                        "Skipping oversized Slay the Spire mod metadata entry: ${entry.name}"
                    )
                    return@use null
                }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun isMetadataEntry(name: String): Boolean =
        name == "ModTheSpire.json" ||
            name.endsWith("/ModTheSpire.json") ||
            name == "ModTheSpire.config" ||
            name.endsWith("/ModTheSpire.config")

    private fun workshopJarPayloads(itemDir: File): List<File> =
        itemDir.listFiles()
            ?.filter {
                it.isFile &&
                    !it.name.startsWith(".") &&
                    it.extension.equals("jar", ignoreCase = true)
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private fun managedJarName(itemId: String, fileName: String): String {
        val sanitized = fileName
            .replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")
            .trim()
            .ifEmpty { "mod.jar" }
        return "${itemId}_$sanitized"
    }

    private fun quoteWindowsArgument(argument: String): String =
        if (argument.any { it.isWhitespace() }) {
            "\"" + argument.replace("\"", "\\\"") + "\""
        } else {
            argument
        }
}
