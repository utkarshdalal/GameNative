package app.gamenative.mods

import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.ZipInputStream

data class NexusUserInfo(
    val name: String,
    val userId: Long,
    val isPremium: Boolean,
)

data class NexusModInfo(
    val modId: Long,
    val name: String,
    val summary: String,
    val version: String,
)

data class NexusModFile(
    val fileId: Long,
    val name: String,
    val version: String,
    val fileName: String,
    val sizeBytes: Long,
    val uploadedTimestamp: Long,
    val uploadedTime: String = "",
    val categoryId: Int = 0,
    val categoryName: String = "",
    val isPrimary: Boolean = false,
)

data class NexusDownloadLink(
    val name: String,
    val uri: String,
)

data class NexusCollectionFile(
    val gameDomain: String,
    val modId: Long,
    val fileId: Long,
    val modName: String = "",
    val fileName: String = "",
    val version: String = "",
    val sizeBytes: Long = 0L,
    val position: Int = 0,
    val required: Boolean = true,
    val dependencyModIds: List<Long> = emptyList(),
    val classification: NexusCollectionInstallClassification = NexusCollectionInstallClassification.AUTO_INSTALLABLE,
    val notes: List<String> = emptyList(),
    val expectedDestination: String = "",
    val externalUrl: String = "",
)

data class NexusCollectionInfo(
    val reference: NexusCollectionReference,
    val name: String,
    val revision: Int? = null,
    val files: List<NexusCollectionFile>,
    val manifestInfo: NexusCollectionManifestInfo = NexusCollectionManifestInfo.EMPTY,
)

class NexusApiException(
    message: String,
    val statusCode: Int? = null,
    val hourlyRemaining: Int? = null,
    val dailyRemaining: Int? = null,
) : IOException(message)

class NexusApiClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.nexusmods.com/v1",
    private val nexusBaseUrl: String = "https://www.nexusmods.com",
    private val graphUrls: List<String> = listOf(
        "https://api.nexusmods.com/v2/graphql",
        "https://api-router.nexusmods.com/graphql",
    ),
) {
    private companion object {
        private const val MAX_COLLECTION_PAYLOAD_BYTES = 256L * 1024L * 1024L
        private const val MAX_COLLECTION_JSON_BYTES = 64L * 1024L * 1024L
    }

    private val jsonMediaType = "application/json".toMediaType()
    private val configuredHosts: Set<String> =
        (listOf(baseUrl, nexusBaseUrl) + graphUrls)
            .mapNotNull { it.toHttpUrlOrNull()?.host?.lowercase() }
            .toSet()
    private val configuredHttpHosts: Set<String> =
        (listOf(baseUrl, nexusBaseUrl) + graphUrls)
            .mapNotNull { it.toHttpUrlOrNull()?.takeIf { url -> url.scheme == "http" }?.host?.lowercase() }
            .toSet()

    suspend fun validateKey(apiKey: String = PrefManager.nexusApiKey): NexusUserInfo =
        withContext(Dispatchers.IO) {
            val json = getObject("/users/validate.json", apiKey)
            NexusUserInfo(
                name = json.optString("name"),
                userId = json.optLong("user_id", 0L),
                isPremium = json.optBoolean("is_premium", false),
            )
        }

    suspend fun getModInfo(gameDomain: String, modId: Long, apiKey: String = PrefManager.nexusApiKey): NexusModInfo =
        withContext(Dispatchers.IO) {
            val json = getObject("/games/$gameDomain/mods/$modId.json", apiKey)
            NexusModInfo(
                modId = json.optLong("mod_id", modId),
                name = json.optString("name", "Nexus mod $modId"),
                summary = json.optString("summary"),
                version = json.optString("version"),
            )
        }

    suspend fun getModFiles(gameDomain: String, modId: Long, apiKey: String = PrefManager.nexusApiKey): List<NexusModFile> =
        withContext(Dispatchers.IO) {
            val json = getObject("/games/$gameDomain/mods/$modId/files.json", apiKey)
            val files = json.optJSONArray("files") ?: JSONArray()
            buildList {
                for (i in 0 until files.length()) {
                    val file = files.optJSONObject(i) ?: continue
                    add(
                        NexusModFile(
                            fileId = file.optLong("file_id"),
                            name = file.optString("name", file.optString("file_name")),
                            version = file.optString("version"),
                            fileName = file.optString("file_name", file.optString("name", "mod_${file.optLong("file_id")}")),
                            sizeBytes = file.optLong("size", 0L) * 1024L,
                            uploadedTimestamp = parseUploadedTimestamp(file),
                            uploadedTime = file.optString("uploaded_time"),
                            categoryId = file.optInt("category_id", 0),
                            categoryName = file.optString("category_name"),
                            isPrimary = file.optBooleanFlexible("is_primary"),
                        ),
                    )
                }
            }
        }

    suspend fun getDownloadLinks(
        gameDomain: String,
        modId: Long,
        fileId: Long,
        apiKey: String = PrefManager.nexusApiKey,
    ): List<NexusDownloadLink> = withContext(Dispatchers.IO) {
        val array = getArray("/games/$gameDomain/mods/$modId/files/$fileId/download_link.json", apiKey)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val uri = item.optString("URI").ifBlank { item.optString("uri") }
                if (uri.isNotBlank()) {
                    add(
                        NexusDownloadLink(
                            name = item.optString("name", "download"),
                            uri = uri,
                        ),
                    )
                }
            }
        }
    }

    suspend fun getCollectionRevision(
        reference: NexusCollectionReference,
        apiKey: String = PrefManager.nexusApiKey,
    ): NexusCollectionInfo = withContext(Dispatchers.IO) {
        var lastError: NexusApiException? = null
        try {
            return@withContext getCollectionRevisionGraph(reference, apiKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NexusApiException) {
            if (e.statusCode == 401 || e.statusCode == 403 || e.statusCode == 429) throw e
            lastError = e
        } catch (e: Exception) {
            lastError = e.toNexusApiException()
        }

        val revisionPaths = buildList {
            reference.revision?.let {
                add("/games/${reference.gameDomain}/collections/${reference.slug}/revisions/$it.json")
            }
            add("/games/${reference.gameDomain}/collections/${reference.slug}/revisions/latest.json")
            add("/games/${reference.gameDomain}/collections/${reference.slug}.json")
        }.distinct()

        for (path in revisionPaths) {
            try {
                return@withContext parseCollectionRevision(getObject(path, apiKey), reference)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NexusApiException) {
                if (e.statusCode == 404 || e.statusCode == 500 || e.statusCode == 502 || e.statusCode == 503) {
                    lastError = e
                    continue
                }
                throw e
            } catch (e: Exception) {
                lastError = e.toNexusApiException()
                continue
            }
        }
        throw lastError ?: NexusApiException("Nexus collection was not found", 404)
    }

    private fun getCollectionRevisionGraph(
        reference: NexusCollectionReference,
        apiKey: String,
    ): NexusCollectionInfo {
        val payload = collectionRevisionGraphPayload(reference)
        var lastError: NexusApiException? = null
        for (url in graphUrls.distinct()) {
            try {
                val response = postObject(url, payload, apiKey)
                val errors = response.optJSONArray("errors")
                val data = response.optJSONObject("data")
                val revision = data?.optJSONObject("collectionRevision")
                if (revision == null) {
                    val message = errors?.optJSONObject(0)?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: "Nexus collection was not found"
                    throw NexusApiException(message, 404)
                }
                val graphInfo = parseCollectionRevisionGraph(data, reference)
                val downloadLink = revision.optStringFromAny("downloadLink", "download_link")
                val manifestInfo = if (downloadLink.isNotBlank()) {
                    try {
                        getCollectionManifest(downloadLink, graphInfo, apiKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                return when {
                    manifestInfo == null -> graphInfo
                    manifestInfo.files.size >= graphInfo.files.size -> manifestInfo
                    else -> graphInfo.copy(manifestInfo = manifestInfo.manifestInfo)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NexusApiException) {
                if (e.statusCode == 401 || e.statusCode == 403 || e.statusCode == 429) throw e
                lastError = e
            } catch (e: Exception) {
                lastError = e.toNexusApiException()
            }
        }
        throw lastError ?: NexusApiException("Nexus collection was not found", 404)
    }

    private fun collectionRevisionGraphPayload(reference: NexusCollectionReference): JSONObject {
        val variables = JSONObject()
            .put("domainName", reference.gameDomain)
            .put("slug", reference.slug)
            .put("viewAdultContent", true)
        reference.revision?.let { variables.put("revision", it) }
        return JSONObject()
            .put("operationName", "CollectionRevisionMods")
            .put("variables", variables)
            .put(
                "query",
                """
                query CollectionRevisionMods(${'$'}domainName: String, ${'$'}revision: Int, ${'$'}slug: String!, ${'$'}viewAdultContent: Boolean) {
                  collection(domainName: ${'$'}domainName, slug: ${'$'}slug) {
                    name
                  }
                  collectionRevision(domainName: ${'$'}domainName, revision: ${'$'}revision, slug: ${'$'}slug, viewAdultContent: ${'$'}viewAdultContent) {
                    downloadLink
                    modCount
                    revisionNumber
                    modFiles {
                      fileId
                      optional
                      file {
                        fileId
                        name
                        uri
                        size
                        sizeInBytes
                        version
                        mod {
                          modId
                          name
                          game {
                            domainName
                          }
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            )
    }

    private fun getObject(path: String, apiKey: String): JSONObject =
        JSONObject(execute(path, apiKey))

    private fun getArray(path: String, apiKey: String): JSONArray =
        JSONArray(execute(path, apiKey))

    private fun postObject(url: String, payload: JSONObject, apiKey: String): JSONObject {
        val request = baseRequest(url, apiKey)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        return JSONObject(execute(request, url))
    }

    private fun getCollectionManifest(
        downloadLink: String,
        graphInfo: NexusCollectionInfo,
        apiKey: String,
    ): NexusCollectionInfo? {
        val raw = fetchCollectionPayload(resolveNexusUrl(downloadLink), apiKey)
        val body = raw.toString(StandardCharsets.UTF_8).trimStart()
        val payload = if (body.startsWith("{")) {
            val json = JSONObject(body)
            val nextLink = json.firstDownloadLink()
            if (nextLink != null) {
                fetchCollectionPayload(resolveNexusUrl(nextLink), apiKey)
            } else {
                raw
            }
        } else {
            raw
        }
        val jsonText = collectionJsonText(payload) ?: return null
        return parseCollectionManifest(JSONObject(jsonText), graphInfo)
    }

    private fun fetchCollectionPayload(url: String, apiKey: String): ByteArray {
        val parsed = url.toHttpUrlOrNull()
            ?: throw NexusApiException("Invalid Nexus collection download URL")
        if (!parsed.isAllowedCollectionScheme()) {
            throw NexusApiException("Nexus collection download URL must use HTTPS")
        }
        val request = if (parsed.shouldAttachApiKey()) {
            baseRequest(url, apiKey)
        } else {
            publicRequest(url)
        }.build()
        return executeBytes(request, MAX_COLLECTION_PAYLOAD_BYTES)
    }

    private fun JSONObject.firstDownloadLink(): String? =
        optJSONArray("download_links")?.let { links ->
            (0 until links.length()).firstNotNullOfOrNull { index ->
                when (val value = links.opt(index)) {
                    is String -> value
                    is JSONObject -> value.optStringFromAny("URI", "uri", "url", "download_link", "downloadLink")
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }
        } ?: optStringFromAny("download_link", "downloadLink", "url", "uri").takeIf { it.isNotBlank() }

    private fun resolveNexusUrl(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("http://", ignoreCase = true) || pathOrUrl.startsWith("https://", ignoreCase = true) -> pathOrUrl
        pathOrUrl.startsWith("/") -> nexusBaseUrl.trimEnd('/') + pathOrUrl
        else -> nexusBaseUrl.trimEnd('/') + "/" + pathOrUrl
    }

    private fun collectionJsonText(bytes: ByteArray): String? {
        if (looksLikeJsonObject(bytes)) {
            return bytes.toString(StandardCharsets.UTF_8).trimStart()
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (!entry.isDirectory && entry.name.endsWith("collection.json", ignoreCase = true)) {
                    return zip.readLimitedBytes(MAX_COLLECTION_JSON_BYTES).toString(StandardCharsets.UTF_8)
                }
            }
        }
    }

    private fun execute(path: String, apiKey: String): String {
        return execute(
            request = baseRequest(baseUrl.trimEnd('/') + path, apiKey).build(),
            displayPath = path,
        )
    }

    private fun baseRequest(url: String, apiKey: String): Request.Builder {
        if (apiKey.isBlank()) {
            throw NexusApiException("Nexus API key is required")
        }
        return Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("APIKEY", apiKey)
            .addHeader("Protocol-Version", "1.0")
            .addHeader("Application-Name", "GameNative")
            .addHeader("Application-Version", BuildConfig.VERSION_NAME)
            .addHeader("User-Agent", "GameNative/${BuildConfig.VERSION_NAME}")
    }

    private fun publicRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("Application-Name", "GameNative")
            .addHeader("Application-Version", BuildConfig.VERSION_NAME)
            .addHeader("User-Agent", "GameNative/${BuildConfig.VERSION_NAME}")

    private fun execute(request: Request, displayPath: String): String {
        client.newCall(request).execute().use { response ->
            val hourly = response.header("x-rl-hourly-remaining")?.toIntOrNull()
            val daily = response.header("x-rl-daily-remaining")?.toIntOrNull()
            val body = response.body.string()
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401, 403 -> "Nexus API key was rejected"
                    404 -> if (displayPath.contains("download_link", ignoreCase = true)) {
                        "This Nexus file is no longer downloadable"
                    } else {
                        "Nexus API request failed (404)"
                    }
                    429 -> "Nexus API rate limit reached"
                    else -> "Nexus API request failed (${response.code})"
                }
                throw NexusApiException(message, response.code, hourly, daily)
            }
            return body
        }
    }

    private fun executeBytes(request: Request, maxBytes: Long = Long.MAX_VALUE): ByteArray {
        client.newCall(request).execute().use { response ->
            val hourly = response.header("x-rl-hourly-remaining")?.toIntOrNull()
            val daily = response.header("x-rl-daily-remaining")?.toIntOrNull()
            if (!response.isSuccessful) {
                response.body.string()
                val message = when (response.code) {
                    401, 403 -> "Nexus API key was rejected"
                    429 -> "Nexus API rate limit reached"
                    else -> "Nexus API request failed (${response.code})"
                }
                throw NexusApiException(message, response.code, hourly, daily)
            }
            return response.body.byteStream().use { it.readLimitedBytes(maxBytes) }
        }
    }

    private fun looksLikeJsonObject(bytes: ByteArray): Boolean =
        bytes.firstOrNull { !(it.toInt() and 0xff).toChar().isWhitespace() }
            ?.let { (it.toInt() and 0xff).toChar() == '{' }
            ?: false

    private fun HttpUrl.isAllowedCollectionScheme(): Boolean =
        scheme == "https" || (scheme == "http" && host.lowercase() in configuredHttpHosts)

    private fun HttpUrl.shouldAttachApiKey(): Boolean =
        isNexusOwnedHost(host) || host.lowercase() in configuredHosts

    private fun isNexusOwnedHost(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == "nexusmods.com" || normalized.endsWith(".nexusmods.com")
    }

    private fun Exception.toNexusApiException(): NexusApiException =
        this as? NexusApiException
            ?: NexusApiException(message ?: "Nexus collection request failed")

    private fun InputStream.readLimitedBytes(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) throw IOException("Nexus collection response is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseCollectionRevisionGraph(
        data: JSONObject,
        reference: NexusCollectionReference,
    ): NexusCollectionInfo {
        val collection = data.optJSONObject("collection")
        val revision = data.optJSONObject("collectionRevision") ?: JSONObject()
        val modFiles = revision.optJSONArray("modFiles") ?: JSONArray()
        val files = buildList {
            for (i in 0 until modFiles.length()) {
                val item = modFiles.optJSONObject(i) ?: continue
                val file = item.optJSONObject("file") ?: continue
                val mod = file.optJSONObject("mod") ?: JSONObject()
                val game = mod.optJSONObject("game") ?: JSONObject()
                val modId = mod.optLongOrNull("modId", "mod_id") ?: continue
                val fileId = file.optLongOrNull("fileId", "file_id")
                    ?: item.optLongOrNull("fileId", "file_id")
                    ?: continue
                add(
                    NexusCollectionFile(
                        gameDomain = game.optStringFromAny("domainName", "domain_name")
                            .ifBlank { reference.gameDomain }
                            .lowercase(),
                        modId = modId,
                        fileId = fileId,
                        modName = mod.optStringFromAny("name", "mod_name"),
                        fileName = file.optStringFromAny("name", "uri", "file_name"),
                        version = file.optStringFromAny("version"),
                        sizeBytes = collectionGraphFileSizeBytes(file),
                        position = i,
                        required = !item.optBooleanFlexible("optional", default = false),
                    ),
                )
            }
        }
        return NexusCollectionInfo(
            reference = reference,
            name = collection?.optStringFromAny("name", "title")
                ?.ifBlank { "Nexus collection ${reference.slug}" }
                ?: "Nexus collection ${reference.slug}",
            revision = revision.optIntOrNull("revisionNumber", "revision_number", "revision")
                ?: reference.revision,
            files = NexusCollectionPlanner.orderedFiles(files),
        )
    }

    private fun parseCollectionManifest(
        json: JSONObject,
        graphInfo: NexusCollectionInfo,
    ): NexusCollectionInfo {
        val manifest = json.firstObject("collectionManifest", "manifest")
            ?: json.firstObject("collection")?.firstObject("manifest", "collectionManifest")
            ?: json
        val filesArray = manifest.firstArray("mods", "mod_files", "files")
            ?: json.firstArray("mods", "mod_files", "files")
            ?: JSONArray()
        val files = buildList {
            for (i in 0 until filesArray.length()) {
                val item = filesArray.optJSONObject(i) ?: continue
                parseCollectionFile(item, graphInfo.reference.gameDomain, i)?.let(::add)
            }
        }
        return graphInfo.copy(
            files = NexusCollectionPlanner.orderedFiles(files),
            manifestInfo = NexusCollectionManifestParser.manifestInfo(json),
        )
    }

    private fun parseUploadedTimestamp(file: JSONObject): Long {
        val timestamp = file.optLong("uploaded_timestamp", 0L)
        if (timestamp > 0L) return timestamp
        val uploadedTime = file.optString("uploaded_time")
        if (uploadedTime.isBlank()) return 0L
        return try {
            Instant.parse(uploadedTime).epochSecond
        } catch (_: DateTimeParseException) {
            0L
        }
    }

    private fun parseCollectionRevision(
        json: JSONObject,
        reference: NexusCollectionReference,
    ): NexusCollectionInfo {
        val collectionObject = json.firstObject("collection", "collection_info", "info") ?: json
        val revisionObject = json.firstObject("revision", "latest_revision")
            ?: collectionObject.firstObject("revision", "latest_revision")
            ?: json
        val filesArray = revisionObject.firstArray("mods", "mod_files", "files")
            ?: collectionObject.firstArray("mods", "mod_files", "files")
            ?: json.firstArray("mods", "mod_files", "files")
            ?: JSONArray()

        val files = buildList {
            for (i in 0 until filesArray.length()) {
                val item = filesArray.optJSONObject(i) ?: continue
                parseCollectionFile(item, reference.gameDomain, i)?.let(::add)
            }
        }

        return NexusCollectionInfo(
            reference = reference,
            name = collectionObject.optStringFromAny(
                "name",
                "collection_name",
                "title",
            ).ifBlank { "Nexus collection ${reference.slug}" },
            revision = revisionObject.optIntOrNull("revision_number", "revision", "revision_id", "revisionNumber")
                ?: reference.revision,
            files = NexusCollectionPlanner.orderedFiles(files),
            manifestInfo = NexusCollectionManifestParser.manifestInfo(json),
        )
    }

    private fun parseCollectionFile(
        item: JSONObject,
        fallbackGameDomain: String,
        position: Int,
    ): NexusCollectionFile? {
        val modObject = item.firstObject("mod", "mod_info") ?: item
        val fileObject = item.firstObject("file", "mod_file", "file_info") ?: item
        val sourceObject = item.firstObject("source") ?: JSONObject()
        val gameDomain = item.optStringFromAny("domain_name", "game_domain", "gameDomain")
            .ifBlank { modObject.optStringFromAny("domain_name", "game_domain", "gameDomain") }
            .ifBlank { sourceObject.optStringFromAny("game", "gameDomain", "game_domain", "domainName") }
            .ifBlank { fallbackGameDomain }
            .lowercase()
        val modId = item.optLongOrNull("mod_id", "modId")
            ?: modObject.optLongOrNull("mod_id", "modId")
            ?: sourceObject.optLongOrNull("mod_id", "modId")
            ?: 0L
        val fileId = item.optLongOrNull("file_id", "fileId")
            ?: fileObject.optLongOrNull("file_id", "fileId")
            ?: sourceObject.optLongOrNull("file_id", "fileId")
            ?: 0L
        val modName = item.optStringFromAny("mod_name", "modName", "name")
            .ifBlank { modObject.optStringFromAny("name", "mod_name", "modName") }
        val fileName = item.optStringFromAny("file_name", "fileName")
            .ifBlank { fileObject.optStringFromAny("file_name", "fileName", "name") }
            .ifBlank { sourceObject.optStringFromAny("logicalFileName", "logicalFilename", "fileName", "file_name", "name") }
        val sizeBytes = collectionFileSizeBytes(item, fileObject, sourceObject)
        val manifestData = NexusCollectionManifestParser.classify(
            item = item,
            modId = modId,
            fileId = fileId,
            modName = modName,
            fileName = fileName,
        )
        if (modId <= 0L && fileId <= 0L && modName.isBlank() && fileName.isBlank() &&
            manifestData.notes.isEmpty() && manifestData.externalUrl.isBlank()
        ) {
            return null
        }

        return NexusCollectionFile(
            gameDomain = gameDomain,
            modId = modId,
            fileId = fileId,
            modName = modName,
            fileName = fileName,
            version = item.optStringFromAny("version", "file_version", "fileVersion")
                .ifBlank { fileObject.optStringFromAny("version", "file_version", "fileVersion") },
            sizeBytes = sizeBytes,
            position = item.optIntOrNull("position", "order", "sort_order") ?: position,
            required = item.optBooleanFlexible("required", default = !item.optBooleanFlexible("optional", default = false)),
            dependencyModIds = parseDependencyModIds(item),
            classification = manifestData.classification,
            notes = manifestData.notes,
            expectedDestination = manifestData.expectedDestination,
            externalUrl = manifestData.externalUrl,
        )
    }

    private fun collectionGraphFileSizeBytes(file: JSONObject): Long =
        file.optFileSizeBytes(
            "sizeInBytes",
            "size_bytes",
            "sizeBytes",
            "fileSizeBytes",
            "file_size_bytes",
            "bytes",
        ) ?: file.optFileSizeBytes("size").orZero()

    private fun collectionFileSizeBytes(vararg objects: JSONObject): Long =
        objects.firstNotNullOfOrNull {
            it.optFileSizeBytes(
                "sizeBytes",
                "sizeInBytes",
                "fileSizeBytes",
                "file_size_bytes",
                "bytes",
                "fileBytes",
                "file_bytes",
                "downloadSizeBytes",
                "download_size_bytes",
            )
        } ?: objects.firstNotNullOfOrNull {
            it.optFileSizeKilobytes(
                "sizeKB",
                "size_kb",
                "fileSizeKB",
                "file_size_kb",
                "downloadSizeKB",
                "download_size_kb",
            )
        } ?: objects.firstNotNullOfOrNull {
            it.optFileSizeBytes("size", "fileSize", "file_size", "downloadSize", "download_size")
        }.orZero()

    private fun parseDependencyModIds(item: JSONObject): List<Long> {
        val arrays = listOfNotNull(
            item.optJSONArray("dependencies"),
            item.optJSONArray("required_mods"),
            item.optJSONArray("requiredMods"),
        )
        return arrays.flatMap { array ->
            buildList {
                for (i in 0 until array.length()) {
                    when (val value = array.opt(i)) {
                        is Number -> add(value.toLong())
                        is String -> value.toLongOrNull()?.let(::add)
                        is JSONObject -> value.optLongOrNull("mod_id", "modId")?.let(::add)
                    }
                }
            }
        }.distinct()
    }

    private fun JSONObject.optBooleanFlexible(name: String, default: Boolean = false): Boolean {
        return when (val value = opt(name)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> default
        }
    }

    private fun JSONObject.firstObject(vararg names: String): JSONObject? =
        names.firstNotNullOfOrNull { name -> optJSONObject(name) }

    private fun JSONObject.firstArray(vararg names: String): JSONArray? =
        names.firstNotNullOfOrNull { name -> optJSONArray(name) }

    private fun JSONObject.optLongOrNull(vararg names: String): Long? =
        names.firstNotNullOfOrNull { name ->
            when (val value = opt(name)) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
        }
    }

    private fun JSONObject.optFileSizeBytes(vararg names: String): Long? =
        names.firstNotNullOfOrNull { name ->
            when (val value = opt(name)) {
                is Number -> value.toLong().takeIf { it > 0L }
                is String -> parseFileSizeBytes(value)
                else -> null
            }
        }

    private fun JSONObject.optFileSizeKilobytes(vararg names: String): Long? =
        names.firstNotNullOfOrNull { name ->
            when (val value = opt(name)) {
                is Number -> value.toLong().takeIf { it > 0L }?.let(::kilobytesToBytes)
                is String -> value.toLongOrNull()?.takeIf { it > 0L }?.let(::kilobytesToBytes)
                else -> null
            }
        }

    private fun parseFileSizeBytes(value: String): Long? {
        val normalized = value.trim().replace(",", "")
        normalized.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        val match = Regex("""^([0-9]+(?:\.[0-9]+)?)\s*([kmgt]?i?b?|bytes?)$""", RegexOption.IGNORE_CASE)
            .matchEntire(normalized)
            ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "b", "byte", "bytes" -> 1L
            "k", "kb", "kib" -> 1024L
            "m", "mb", "mib" -> 1024L * 1024L
            "g", "gb", "gib" -> 1024L * 1024L * 1024L
            "t", "tb", "tib" -> 1024L * 1024L * 1024L * 1024L
            else -> return null
        }
        val bytes = amount * multiplier
        if (bytes.isNaN() || bytes.isInfinite() || bytes <= 0.0 || bytes > Long.MAX_VALUE.toDouble()) return null
        return bytes.toLong()
    }

    private fun kilobytesToBytes(value: Long): Long =
        if (value > Long.MAX_VALUE / 1024L) Long.MAX_VALUE else value * 1024L

    private fun Long?.orZero(): Long = this ?: 0L

    private fun JSONObject.optIntOrNull(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name ->
            when (val value = opt(name)) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }

    private fun JSONObject.optStringFromAny(vararg names: String): String =
        names.firstNotNullOfOrNull { name ->
            when (val value = opt(name)) {
                is String -> value.takeIf { it.isNotBlank() }
                is Number -> value.toString()
                else -> null
            }
        }.orEmpty()
}

object NexusFileSelector {
    private val hiddenCategoryIds = setOf(4, 6, 7)
    private val hiddenCategoryNames = setOf("OLD_VERSION", "OLD", "ARCHIVED", "DELETED")

    fun currentFiles(files: List<NexusModFile>): List<NexusModFile> =
        files.filterNot { it.isOlderOrUnavailable() }.sortedForDisplay()

    fun olderFiles(files: List<NexusModFile>): List<NexusModFile> =
        files.filter { it.isOlderOrUnavailable() }.sortedForDisplay()

    fun NexusModFile.isOlderOrUnavailable(): Boolean {
        val normalizedName = categoryName.uppercase().replace(' ', '_')
        return categoryId in hiddenCategoryIds || normalizedName in hiddenCategoryNames
    }

    private fun List<NexusModFile>.sortedForDisplay(): List<NexusModFile> =
        sortedWith(
            compareByDescending<NexusModFile> { it.isPrimary }
                .thenByDescending { it.categoryName.equals("MAIN", ignoreCase = true) || it.categoryId == 1 }
                .thenByDescending { it.uploadedTimestamp }
                .thenByDescending { it.fileId },
        )
}

object NexusCollectionPlanner {
    fun orderedFiles(files: List<NexusCollectionFile>): List<NexusCollectionFile> =
        NexusCollectionFileOrdering.orderedFiles(files, preferPatches = false)
}
