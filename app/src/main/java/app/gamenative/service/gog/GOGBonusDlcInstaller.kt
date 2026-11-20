package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.utils.Net
import com.winlator.core.FileUtils as WinlatorFileUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.w3c.dom.Element
import timber.log.Timber

/**
 * DLC that ship outside the build's depots: a Windows offline installer (`downloads.installers`)
 * or a "game add-ons" bonus item (`downloads.bonus_content`) of the DLC product. Files land in
 * `<installDir>/_gog_bonus/<dlcId>/`; a `.zip` is extracted over the game root, an `.exe` is
 * left for [app.gamenative.utils.GogBonusInstallerStep] to run before the next launch.
 */
object GOGBonusDlcInstaller {
    const val BONUS_DIR = "_gog_bonus"
    const val PENDING_INSTALLER_FILE = "pending_installer.txt"
    const val INSTALLER_FAILED_FILE = "installer_failed.txt"
    const val APPLIED_FILE = "applied"
    private const val APPLIED_LANGUAGE_FILE = "applied_language"
    private const val ADD_ON_TYPE = "game add-ons"
    private const val WINDOWS_OS = "windows"
    private const val PROGRESS_INTERVAL_MS = 200L

    data class Resolution(
        val kind: GogDlcKind,
        val items: List<GogDownloadItem>,
        val downloadSize: Long,
    )

    data class Plan(
        val dlcId: String,
        val title: String,
        val items: List<GogDownloadItem>,
        val totalBytes: Long,
        val reapplyOnly: Boolean,
        val language: String,
    )

    data class ChecksumChunk(val id: Int, val from: Long, val to: Long, val method: String, val hash: String)

    data class ChecksumInfo(val name: String, val md5: String, val totalSize: Long, val chunks: List<ChecksumChunk>)

    /**
     * Windows installer (the one for [language], else English, else the first) wins over
     * "game add-ons" bonus items; nothing usable is [GogDlcKind.NONE].
     */
    fun resolve(downloads: GogProductDownloads, language: String): Resolution {
        val installers = downloads.installers.filter { it.os.equals(WINDOWS_OS, ignoreCase = true) && it.files.isNotEmpty() }
        if (installers.isNotEmpty()) {
            val requested = GOGConstants.containerLanguageToGogCodes(language)
            val english = GOGConstants.CONTAINER_LANGUAGE_TO_GOG_CODES.getValue(GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE)
            fun matching(codes: List<String>) = installers.firstOrNull { item -> codes.any { it.equals(item.language, ignoreCase = true) } }
            val installer = matching(requested) ?: matching(english) ?: installers.first()
            return Resolution(GogDlcKind.INSTALLER, listOf(installer), itemSize(installer))
        }
        val addOns = downloads.bonusContent.filter { it.type.equals(ADD_ON_TYPE, ignoreCase = true) && it.files.isNotEmpty() }
        if (addOns.isNotEmpty()) {
            return Resolution(GogDlcKind.BONUS, addOns, addOns.sumOf { itemSize(it) })
        }
        return Resolution(GogDlcKind.NONE, emptyList(), 0L)
    }

    private fun itemSize(item: GogDownloadItem): Long =
        item.files.sumOf { it.size }.takeIf { it > 0L } ?: item.totalSize

    fun dlcDir(installDir: File, dlcId: String): File = File(File(installDir, BONUS_DIR), safeName(dlcId))

    fun pendingInstallerMarkers(installDir: File): List<File> =
        File(installDir, BONUS_DIR).listFiles()
            ?.filter { it.isDirectory }
            ?.map { File(it, PENDING_INSTALLER_FILE) }
            ?.filter { it.isFile }
            ?.sortedBy { it.parentFile?.name }
            .orEmpty()

    /**
     * Bonus/installer DLC among [dlcIds] to fetch or re-apply. A DLC already applied for [language]
     * needs no network: its kept files are re-applied, since a verify restores the base files it patched.
     */
    suspend fun plan(context: Context, installDir: File, dlcIds: Set<String>, language: String): Result<List<Plan>> {
        val plans = mutableListOf<Plan>()
        for (dlcId in dlcIds.sorted()) {
            val dir = dlcDir(installDir, dlcId)
            if (File(dir, APPLIED_FILE).isFile && appliedLanguageMatches(dir, language)) {
                plans.add(Plan(dlcId, dlcId, emptyList(), 0L, reapplyOnly = true, language = language))
                continue
            }
            val downloads = GOGApiClient.getProductDownloads(context, dlcId).getOrElse {
                return Result.failure(Exception("Failed to fetch downloads of DLC $dlcId: ${it.message}", it))
            }
            val resolution = resolve(downloads, language)
            if (resolution.kind == GogDlcKind.NONE) {
                Timber.tag("GOG").i("DLC $dlcId has no depots, Windows installer or add-on bonus content; skipping")
                continue
            }
            val files = resolution.items.flatMap { it.files }
            val pending = files.sumOf { file -> (file.size - downloadedBytes(dir, file)).coerceAtLeast(0L) }
            val title = resolution.items.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: dlcId
            Timber.tag("GOG").i("DLC $dlcId is ${resolution.kind}: ${files.size} file(s), $pending byte(s) pending")
            plans.add(Plan(dlcId, title, resolution.items, resolution.downloadSize, reapplyOnly = false, language = language))
        }
        return Result.success(plans)
    }

    internal fun appliedLanguageMatches(dir: File, language: String): Boolean {
        val applied = File(dir, APPLIED_LANGUAGE_FILE).takeIf { it.isFile }?.readText()?.trim() ?: return false
        return applied.equals(language, ignoreCase = true)
    }

    suspend fun install(context: Context, installDir: File, plans: List<Plan>, downloadInfo: DownloadInfo): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                for (plan in plans) {
                    if (!downloadInfo.isActive()) return@withContext Result.failure(Exception("Download cancelled"))
                    val dir = dlcDir(installDir, plan.dlcId)
                    dir.mkdirs()
                    val names = if (plan.reapplyOnly) {
                        File(dir, APPLIED_FILE).readLines().filter { it.isNotBlank() }
                    } else {
                        val downloaded = mutableListOf<String>()
                        for (item in plan.items) {
                            for ((index, file) in item.files.withIndex()) {
                                downloadInfo.updateStatusMessage(
                                    if (item.files.size > 1) "Downloading ${plan.title} (${index + 1}/${item.files.size})..." else "Downloading ${plan.title}...",
                                )
                                val result = downloadFile(context, dir, file, downloadInfo)
                                downloaded.add(result.getOrElse { return@withContext Result.failure(it) }.name)
                            }
                        }
                        downloaded
                    }
                    downloadInfo.updateStatusMessage("Applying ${plan.title}...")
                    applyFiles(installDir, dir, names, downloadInfo)
                    if (!plan.reapplyOnly) {
                        File(dir, APPLIED_FILE).writeText(names.joinToString("\n"))
                        File(dir, APPLIED_LANGUAGE_FILE).writeText(plan.language)
                    }
                    Timber.tag("GOG").i("Applied DLC ${plan.dlcId}: ${names.joinToString()}")
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Bonus DLC install failed: ${e.message}")
                Result.failure(e)
            }
        }

    private fun doneRecord(dir: File, file: GogDownloadFile) = File(dir, "${safeName(file.id)}.done")

    private fun partFile(dir: File, file: GogDownloadFile) = File(dir, ".part-${safeName(file.id)}")

    private fun downloadedBytes(dir: File, file: GogDownloadFile): Long {
        completedFile(dir, file)?.let { return it.length() }
        return partFile(dir, file).takeIf { it.isFile }?.length() ?: 0L
    }

    private fun completedFile(dir: File, file: GogDownloadFile): File? {
        val record = doneRecord(dir, file)
        if (!record.isFile) return null
        val done = File(dir, record.readText().trim())
        return done.takeIf { it.isFile && (file.size <= 0L || it.length() == file.size) }
    }

    private suspend fun downloadFile(
        context: Context,
        dir: File,
        file: GogDownloadFile,
        downloadInfo: DownloadInfo,
    ): Result<File> {
        completedFile(dir, file)?.let { return Result.success(it) }

        val link = GOGApiClient.resolveDownlink(context, file.downlink).getOrElse { return Result.failure(it) }
        val url = link.optString("downlink")
        val checksumUrl = link.optString("checksum", "")

        val part = partFile(dir, file)
        var offset = if (part.isFile) part.length() else 0L
        if (file.size > 0L && offset > file.size) {
            part.delete()
            offset = 0L
        }

        val requestBuilder = Request.Builder().url(url).header("User-Agent", "GOG Galaxy")
        if (offset > 0L) requestBuilder.header("Range", "bytes=$offset-")
        val resolved = Net.http.newCall(requestBuilder.build()).execute().use { response ->
            val resolvedName = fileNameFor(
                response.header("Content-Disposition"),
                response.request.url.encodedPath,
                "dlc-${safeName(file.id)}",
            )
            if (response.code == 416 && offset > 0L && offset == file.size) return@use resolvedName
            if (!response.isSuccessful) {
                if (response.code == 416) part.delete()
                return Result.failure(Exception("HTTP ${response.code} downloading DLC file ${file.id}"))
            }
            val append = response.code == 206 && offset > 0L
            val body = response.body ?: return Result.failure(Exception("Empty response for DLC file ${file.id}"))
            val buffer = ByteArray(256 * 1024)
            var lastEmit = System.currentTimeMillis()
            try {
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            if (!downloadInfo.isActive()) return Result.failure(Exception("Download cancelled"))
                            out.write(buffer, 0, n)
                            downloadInfo.updateBytesDownloaded(n.toLong())
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                downloadInfo.emitProgressChange()
                                lastEmit = now
                            }
                        }
                    }
                }
            } finally {
                downloadInfo.persistProgressSnapshot()
            }
            downloadInfo.emitProgressChange()
            resolvedName
        }

        if (file.size > 0L && part.length() != file.size) {
            return Result.failure(Exception("Size mismatch for DLC file ${file.id}: ${part.length()} != ${file.size}"))
        }
        val checksum = fetchChecksum(checksumUrl).getOrElse { return Result.failure(it) }
        verifyChecksum(part, checksum).onFailure {
            part.delete()
            return Result.failure(it)
        }

        val name = targetNameFor(dir, file, resolved)
        val target = File(dir, name)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        doneRecord(dir, file).writeText(name)
        return Result.success(target)
    }

    /** Checksum behind [checksumUrl]; null when none is offered or it is unparseable, a failure when the fetch fails. */
    private fun fetchChecksum(checksumUrl: String): Result<ChecksumInfo?> {
        if (checksumUrl.isEmpty()) return Result.success(null)
        return try {
            Net.http.newCall(Request.Builder().url(checksumUrl).header("User-Agent", "GOG Galaxy").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                Result.success(parseChecksumXml(response.body?.string().orEmpty()))
            }
        } catch (e: Exception) {
            Timber.tag("GOG").w(e, "Checksum fetch failed: $checksumUrl")
            Result.failure(IOException("Failed to fetch DLC checksum: ${e.message}", e))
        }
    }

    private fun verifyChecksum(file: File, info: ChecksumInfo?): Result<Unit> {
        if (info == null || info.md5.isEmpty()) {
            Timber.tag("GOG").i("Checksum unavailable for ${file.name}; skipping verification")
            return Result.success(Unit)
        }
        if (info.totalSize > 0L && file.length() != info.totalSize) {
            return Result.failure(Exception("Size mismatch for ${file.name}: ${file.length()} != ${info.totalSize}"))
        }
        val actual = md5Of(file)
        if (!actual.equals(info.md5, ignoreCase = true)) {
            return Result.failure(Exception("MD5 mismatch for ${file.name}: expected ${info.md5}, got $actual"))
        }
        return Result.success(Unit)
    }

    private fun md5Of(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** lgogdownloader format: `<file name md5 chunks total_size><chunk id from to method>hash</chunk></file>`. */
    internal fun parseChecksumXml(xml: String): ChecksumInfo? {
        if (xml.isBlank()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.isExpandEntityReferences = false }
            val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())).documentElement
            if (root.tagName != "file") return null
            val chunkNodes = root.getElementsByTagName("chunk")
            val chunks = (0 until chunkNodes.length).mapNotNull { i ->
                val chunk = chunkNodes.item(i) as? Element ?: return@mapNotNull null
                ChecksumChunk(
                    id = chunk.getAttribute("id").toIntOrNull() ?: i,
                    from = chunk.getAttribute("from").toLongOrNull() ?: return@mapNotNull null,
                    to = chunk.getAttribute("to").toLongOrNull() ?: return@mapNotNull null,
                    method = chunk.getAttribute("method"),
                    hash = chunk.textContent.trim(),
                )
            }
            ChecksumInfo(
                name = root.getAttribute("name"),
                md5 = root.getAttribute("md5"),
                totalSize = root.getAttribute("total_size").toLongOrNull() ?: 0L,
                chunks = chunks,
            )
        } catch (e: Exception) {
            Timber.tag("GOG").w(e, "Unparseable checksum XML")
            null
        }
    }

    /** [resolved], prefixed with the file id (and a counter) until no other file of the DLC took that name. */
    internal fun targetNameFor(dir: File, file: GogDownloadFile, resolved: String): String {
        val own = doneRecord(dir, file)
        val taken = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".done") && it != own }
            ?.map { it.readText().trim() }
            ?.toSet()
            .orEmpty()
        if (resolved !in taken) return resolved
        val prefix = safeName(file.id)
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) "$prefix-$resolved" else "$prefix-$it-$resolved" }
            .first { it !in taken }
    }

    /** File name from Content-Disposition, else the URL's last path segment, else [fallback]; reduced to [A-Za-z0-9._-]. */
    internal fun fileNameFor(contentDisposition: String?, encodedPath: String, fallback: String): String {
        val fromHeader = contentDisposition?.let { header ->
            Regex("""filename\*\s*=\s*[^']*''([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
                ?: Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        }
        val fromPath = encodedPath.substringAfterLast('/').takeIf { it.isNotEmpty() }
        val raw = (fromHeader ?: fromPath)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val safe = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.let { safeName(it) }
        return safe?.takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: fallback
    }

    private fun safeName(name: String): String = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun applyFiles(installDir: File, dir: File, names: List<String>, downloadInfo: DownloadInfo) {
        val installers = mutableListOf<String>()
        File(dir, PENDING_INSTALLER_FILE).delete()
        for (name in names) {
            val file = File(dir, name)
            if (!file.isFile) throw IOException("Downloaded DLC file missing: ${file.absolutePath}")
            when (file.extension.lowercase()) {
                "zip" -> extractZip(file, installDir, downloadInfo)
                "exe" -> {
                    installers.add(file.absolutePath)
                    Timber.tag("GOG").i("Installer ${file.name} will run before the next launch")
                }
                else -> Timber.tag("GOG").i("Left ${file.name} in ${dir.absolutePath}; not an archive or installer")
            }
        }
        if (installers.isNotEmpty()) {
            installers.forEach { installedRecord(dir, File(it).name).delete() }
            File(dir, PENDING_INSTALLER_FILE).writeText(installers.joinToString("\n"))
            File(dir, INSTALLER_FAILED_FILE).delete()
        }
    }

    /**
     * Extracts [zip] over [root], overwriting; fails before writing anything if an entry escapes [root],
     * and stops with a failure once [downloadInfo] is no longer active.
     */
    internal fun extractZip(zip: File, root: File, downloadInfo: DownloadInfo? = null) {
        fun checkActive() {
            if (downloadInfo != null && !downloadInfo.isActive()) throw IOException("Download cancelled")
        }
        val rootCanonical = root.canonicalFile
        fun target(entryName: String): File {
            val out = File(rootCanonical, entryName).canonicalFile
            if (out != rootCanonical && !out.path.startsWith(rootCanonical.path + File.separator)) {
                throw IOException("Zip entry escapes the install directory: $entryName")
            }
            return out
        }
        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList()
            entries.forEach { target(it.name) }
            for (entry in entries) {
                checkActive()
                val out = target(entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                val temp = File.createTempFile(".gog-", ".part", out.parentFile)
                try {
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                checkActive()
                                val n = input.read(buffer)
                                if (n == -1) break
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    Files.move(temp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    temp.delete()
                }
            }
        }
    }

    private fun installedRecord(dir: File, exeName: String) = File(dir, "$exeName.installed")

    /**
     * Command running the pending installers of [marker] in order, or null when an exe is gone or
     * the `rootdir` link cannot be created. InnoSetup refuses a drive root for /DIR, so /DIR goes
     * through a `rootdir` link to the game root (A:), as the support-command installers do. Each
     * installer exiting with code 0 is recorded so a retry skips it; the marker is deleted only
     * when every installer has succeeded.
     */
    fun installerCommand(gameDir: File, marker: File): String? {
        val dir = marker.parentFile ?: return null
        val listed = marker.readLines().filter { it.isNotBlank() }.map { File(dir, File(it.trim()).name) }
        val exes = listed.filter { it.isFile }
        (listed - exes.toSet()).forEach { Timber.tag("GOG").w("Pending DLC installer missing: ${it.absolutePath}") }
        if (exes.isEmpty()) {
            Timber.tag("GOG").w("No pending DLC installer left, dropping marker: ${marker.absolutePath}")
            marker.delete()
            return null
        }
        if (exes.size != listed.size) {
            Timber.tag("GOG").w("DLC installers of ${dir.name} are incomplete; staying pending")
            return null
        }
        val remaining = exes.filterNot { installedRecord(dir, it.name).isFile }
        if (remaining.isEmpty()) {
            marker.delete()
            return null
        }
        val failed = File(dir, INSTALLER_FAILED_FILE)
        if (failed.exists()) {
            Timber.tag("GOG").w("DLC installer of ${dir.name} exited non-zero last launch; retrying")
            failed.delete()
        }
        val rootDirLink = File(dir, "rootdir")
        if (!WinlatorFileUtils.isSymlink(rootDirLink)) {
            if (rootDirLink.exists()) rootDirLink.delete()
            WinlatorFileUtils.symlink(gameDir, rootDirLink)
            if (!WinlatorFileUtils.isSymlink(rootDirLink)) {
                Timber.tag("GOG").e("Failed to create ${rootDirLink.absolutePath}; DLC installer of ${dir.name} stays pending")
                return null
            }
        }
        val base = "A:\\$BONUS_DIR\\${dir.name}"
        val args = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /NOCANCEL /DIR=$base\\rootdir"
        val run = remaining.joinToString(" && ") {
            "$base\\${it.name} $args && echo done> $base\\${installedRecord(dir, it.name).name}"
        }
        return "$run && del /Q $base\\$PENDING_INSTALLER_FILE || echo failed> $base\\$INSTALLER_FAILED_FILE"
    }
}
