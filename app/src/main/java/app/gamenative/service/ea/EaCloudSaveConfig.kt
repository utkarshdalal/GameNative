package app.gamenative.service.ea

import android.content.Context
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class EaCloudSaveTarget(
    val offerId: String,
    val cloudId: String,
    val includes: List<String>,
    val excludes: List<String>,
)

/** Finds a game's EA cloud save slot and maps its save file criteria onto the Wine prefix. */
object EaCloudSaveConfig {
    internal data class LegacyOffer(
        val offerId: String,
        val contentId: String,
        val primaryMasterTitleId: String,
        val multiplayerId: String,
        val cloudSaveConfigurationOverride: String,
    )

    private class Parsed(val token: String, val root: String, val segments: List<String>, val separator: Char)

    private class Cached(val target: EaCloudSaveTarget?, val fetchedAt: Long)

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val OWNED_QUERY = "query getPreloadedOwnedGames(\$next: String, \$limit: Int, \$type: [GameProductType!]!, " +
        "\$storefronts: [UserGameProductStorefront!], \$platforms: [GamePlatform!]!) { me { " +
        "ownedGameProducts(storefronts: \$storefronts, paging: {limit: \$limit, next: \$next}, productFound: true, " +
        "orderBy: {field: NAME, direction: ASC}, type: \$type, " +
        "downloadableOnly: false, platforms: \$platforms) { next totalCount items { originOfferId } } } }"

    private const val OFFERS_QUERY = "query getLegacyCatalogDefs(\$offerIds: [String!]!) { legacyOffers(offerIds: \$offerIds) { " +
        "offerId: id contentId primaryMasterTitleId multiplayerId cloudSaveConfigurationOverride } }"

    private const val PAGE_SIZE = 1000
    private const val MAX_PAGES = 20
    private const val OFFER_CHUNK = 200

    private val FOLDERS = mapOf(
        "documents" to "users/${ImageFs.USER}/Documents",
        "savedgames" to "users/${ImageFs.USER}/Saved Games",
        "localappdata" to "users/${ImageFs.USER}/AppData/Local",
        "appdata" to "users/${ImageFs.USER}/AppData/Roaming",
        "programdata" to "ProgramData",
    )

    private val TOKEN = Regex("^%([^%/\\\\]+)%(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val ELEMENT = Regex("<(include|exclude)\\b([^>]*)>(.*?)</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ORDER = Regex("order\\s*=\\s*[\"'](-?\\d+)[\"']", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|\\w+);")

    private val loggedUnsupported = ConcurrentHashMap.newKeySet<String>()
    private val loggedOutsidePrefix = ConcurrentHashMap.newKeySet<String>()

    @Volatile internal var endpoint: String = EaConstants.SERVICE_AGGREGATION_ENDPOINT
    @Volatile internal var cacheMaxAgeMs: Long = TimeUnit.HOURS.toMillis(24)

    suspend fun resolve(context: Context, contentIds: List<String>): EaCloudSaveTarget? = withContext(Dispatchers.IO) {
        val key = contentIds.firstOrNull() ?: return@withContext null
        val cached = readCache(context, key)
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt in 0 until cacheMaxAgeMs) return@withContext cached.target
        try {
            val offer = fetchOffer(context, contentIds)
            if (offer == null) {
                Timber.w("EA cloud saves: no owned offer matches content ${contentIds.take(4)}")
                if (cached != null) removeCache(context, key)
                return@withContext null
            }
            val target = targetOf(offer)
            writeCache(context, key, target)
            Timber.i(
                "EA cloud saves: offer ${offer.offerId} -> ${target?.cloudId ?: "no cloud saves"} " +
                    "(${target?.includes?.size ?: 0} include(s))",
            )
            target
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached == null) throw e
            Timber.w("EA cloud saves: catalog lookup failed, using cached target (${e.message})")
            cached.target
        }
    }

    fun localFiles(target: EaCloudSaveTarget, prefixDriveC: File): Map<String, File> {
        val excludes = target.excludes.mapNotNull(::parse)
        val out = LinkedHashMap<String, File>()
        val seen = HashSet<String>()
        for (raw in target.includes) {
            val pattern = parse(raw) ?: continue
            if (pattern.segments.isEmpty() || unsafe(pattern.segments)) continue
            val root = File(prefixDriveC, pattern.root)
            val rootPath = folderPath(prefixDriveC, pattern.root) ?: continue
            val visited = HashSet<String>()
            collect(root, pattern.segments, 0, emptyList(), rootPath, visited) { names, file ->
                if (excludes.any { matches(it, Parsed(pattern.token, pattern.root, names, pattern.separator)) }) return@collect
                val name = pattern.token + pattern.separator + names.joinToString(pattern.separator.toString())
                if (seen.add(normalizeName(name))) out[name] = file
            }
        }
        return out
    }

    fun toLocalName(target: EaCloudSaveTarget, file: File, prefixDriveC: File): String? {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return null
        val excludes = target.excludes.mapNotNull(::parse)
        for (raw in target.includes) {
            val pattern = parse(raw) ?: continue
            val rootPath = folderPath(prefixDriveC, pattern.root) ?: continue
            if (!path.startsWith(rootPath + File.separator)) continue
            val names = path.substring(rootPath.length + 1).split(File.separatorChar).filter { it.isNotEmpty() }
            val name = Parsed(pattern.token, pattern.root, names, pattern.separator)
            if (!matches(pattern, name) || excludes.any { matches(it, name) }) continue
            return pattern.token + pattern.separator + names.joinToString(pattern.separator.toString())
        }
        return null
    }

    fun toFile(localName: String, prefixDriveC: File): File? {
        val name = parse(localName) ?: return null
        if (name.segments.isEmpty() || unsafe(name.segments)) return null
        val rootPath = folderPath(prefixDriveC, name.root) ?: return null
        var file = File(prefixDriveC, name.root)
        for (segment in name.segments) {
            val children = file.takeIf { it.isDirectory }?.list()
            val existing = children?.firstOrNull { it == segment } ?: children?.firstOrNull { it.equals(segment, ignoreCase = true) }
            file = File(file, existing ?: segment)
        }
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return null
        return file.takeIf { path.startsWith(rootPath + File.separator) }
    }

    fun isAllowed(target: EaCloudSaveTarget, localName: String): Boolean {
        val name = parse(localName) ?: return false
        if (name.segments.isEmpty() || unsafe(name.segments)) return false
        if (target.includes.none { raw -> parse(raw)?.let { matches(it, name) } == true }) return false
        return target.excludes.none { raw -> parse(raw)?.let { matches(it, name) } == true }
    }

    fun normalizeName(localName: String): String =
        localName.trim().replace('\\', '/').replace(Regex("/{2,}"), "/").lowercase()

    internal fun parseCriteria(xml: String): Pair<List<String>, List<String>> {
        val text = if ('<' !in xml && "&lt;" in xml) unescape(xml) else xml
        val entries = ELEMENT.findAll(text).mapIndexedNotNull { index, m ->
            val value = unescape(m.groupValues[3].trim().removePrefix("<![CDATA[").removeSuffix("]]>")).trim()
            if (value.isEmpty()) return@mapIndexedNotNull null
            val order = ORDER.find(m.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() ?: index
            Triple(m.groupValues[1].lowercase(), order, value)
        }.toList().sortedBy { it.second }
        return entries.filter { it.first == "include" }.map { it.third } to entries.filter { it.first == "exclude" }.map { it.third }
    }

    internal fun parseOffers(data: JSONObject): List<LegacyOffer> {
        val arr = data.optJSONArray("legacyOffers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            LegacyOffer(
                offerId = o.text("offerId"),
                contentId = o.text("contentId"),
                primaryMasterTitleId = o.text("primaryMasterTitleId"),
                multiplayerId = o.text("multiplayerId"),
                cloudSaveConfigurationOverride = o.text("cloudSaveConfigurationOverride"),
            )
        }
    }

    internal fun selectOffer(offers: List<LegacyOffer>, contentIds: List<String>): LegacyOffer? =
        offers.filter { it.contentId.isNotEmpty() && indexOf(contentIds, it.contentId) >= 0 }
            .minByOrNull { indexOf(contentIds, it.contentId) }

    internal fun targetOf(offer: LegacyOffer): EaCloudSaveTarget? {
        if (offer.primaryMasterTitleId.isBlank() || offer.multiplayerId.isBlank()) return null
        if (offer.cloudSaveConfigurationOverride.isBlank()) return null
        val (includes, excludes) = parseCriteria(offer.cloudSaveConfigurationOverride)
        if (includes.isEmpty()) return null
        return EaCloudSaveTarget(offer.offerId, "${offer.primaryMasterTitleId}_${offer.multiplayerId}", includes, excludes)
    }

    private fun indexOf(contentIds: List<String>, contentId: String): Int =
        contentIds.indexOfFirst { it.equals(contentId, ignoreCase = true) }

    private suspend fun fetchOffer(context: Context, contentIds: List<String>): LegacyOffer? {
        val token = EaAuthManager.accessToken(context)
        var best: LegacyOffer? = null
        var next = "0"
        repeat(MAX_PAGES) {
            val variables = JSONObject()
                .put("next", next)
                .put("limit", PAGE_SIZE)
                .put("type", JSONArray(listOf("DIGITAL_FULL_GAME")))
                .put("storefronts", JSONArray(listOf("EA", "STEAM", "EPIC")))
                .put("platforms", JSONArray(listOf("PC")))
            val owned = post(token, "getPreloadedOwnedGames", OWNED_QUERY, variables)
                .optJSONObject("me")?.optJSONObject("ownedGameProducts")
                ?: error("EA getPreloadedOwnedGames: no ownedGameProducts")
            val items = owned.optJSONArray("items")
            val offerIds = (0 until (items?.length() ?: 0))
                .mapNotNull { i -> items?.optJSONObject(i)?.text("originOfferId")?.takeIf { it.isNotEmpty() } }
                .distinct()
            for (chunk in offerIds.chunked(OFFER_CHUNK)) {
                val offers = parseOffers(post(token, "getLegacyCatalogDefs", OFFERS_QUERY, JSONObject().put("offerIds", JSONArray(chunk))))
                val candidate = selectOffer(listOfNotNull(best) + offers, contentIds)
                if (candidate != null) best = candidate
                val found = best
                if (found != null && indexOf(contentIds, found.contentId) == 0) return found
            }
            val following = owned.text("next")
            if (offerIds.isEmpty() || following.isEmpty() || following == next) return best
            next = following
        }
        return best
    }

    private fun post(token: String, operation: String, query: String, variables: JSONObject): JSONObject {
        val body = JSONObject()
            .put("operationName", operation)
            .put("query", query)
            .put("variables", variables)
            .put(
                "extensions",
                JSONObject().put(
                    "persistedQuery",
                    JSONObject().put("version", 1).put("sha256Hash", EaCrypto.hex(EaCrypto.sha256(query.toByteArray()))),
                ),
            )
        val req = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "EADesktop/13.778.0")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("EA $operation HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text).optJSONObject("data") ?: error("EA $operation: no data (${text.take(300)})")
        }
    }

    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)

    private fun cacheFile(context: Context) = File(File(context.filesDir, "ea_cloud"), "targets.json")

    private fun readCache(context: Context, key: String): Cached? = runCatching {
        val entry = JSONObject(cacheFile(context).readText()).optJSONObject(key) ?: return null
        val cloudId = entry.text("cloudId")
        val target = if (cloudId.isEmpty()) {
            null
        } else {
            EaCloudSaveTarget(entry.text("offerId"), cloudId, entry.strings("includes"), entry.strings("excludes"))
        }
        Cached(target, entry.optLong("fetchedAt", 0))
    }.getOrNull()

    private fun writeCache(context: Context, key: String, target: EaCloudSaveTarget?) = updateCache(context) { all ->
        all.put(
            key,
            JSONObject()
                .put("fetchedAt", System.currentTimeMillis())
                .put("offerId", target?.offerId.orEmpty())
                .put("cloudId", target?.cloudId.orEmpty())
                .put("includes", JSONArray(target?.includes.orEmpty()))
                .put("excludes", JSONArray(target?.excludes.orEmpty())),
        )
    }

    private fun removeCache(context: Context, key: String) = updateCache(context) { it.remove(key) }

    @Synchronized
    private fun updateCache(context: Context, change: (JSONObject) -> Unit) {
        runCatching {
            val file = cacheFile(context)
            file.parentFile?.mkdirs()
            val all = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
            change(all)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(all.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(all.toString())
                tmp.delete()
            }
        }.onFailure { Timber.w("EA cloud saves: could not write target cache (${it.message})") }
    }

    private fun JSONObject.strings(name: String): List<String> {
        val arr = optJSONArray(name) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    private fun parse(raw: String): Parsed? {
        val text = raw.trim()
        val m = TOKEN.matchEntire(text)
        val root = m?.let { FOLDERS[it.groupValues[1].lowercase()] }
        if (m == null || root == null) {
            val label = m?.let { "%${it.groupValues[1]}%" } ?: "no folder token"
            if (loggedUnsupported.add(label.lowercase())) Timber.w("EA cloud saves: unsupported save path ($label)")
            return null
        }
        val segments = m.groupValues[2].split('/', '\\').filter { it.isNotEmpty() }
            .let { if (text.endsWith('/') || text.endsWith('\\')) it + "*" else it }
        return Parsed("%${m.groupValues[1]}%", root, segments, if ('\\' in text) '\\' else '/')
    }

    private fun folderPath(prefixDriveC: File, root: String): String? {
        val drivePath = runCatching { prefixDriveC.canonicalPath }.getOrNull() ?: return null
        val path = runCatching { File(prefixDriveC, root).canonicalPath }.getOrNull() ?: return null
        if (path == drivePath || path.startsWith(drivePath + File.separator)) return path
        if (loggedOutsidePrefix.add(path)) Timber.w("EA cloud saves: $root resolves outside the Wine prefix ($path), skipping it")
        return null
    }

    private fun unsafe(segments: List<String>): Boolean = segments.any { it == ".." || it == "." || ':' in it || '\u0000' in it }

    private fun recursive(segment: String): Boolean = segment == "*" || segment == "**"

    private fun glob(segment: String): Regex = Regex(
        buildString {
            for (c in segment) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        },
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private fun matches(pattern: Parsed, name: Parsed): Boolean {
        if (pattern.root != name.root || pattern.segments.isEmpty()) return false
        val p = pattern.segments
        val n = name.segments
        val tree = recursive(p.last())
        val fixed = if (tree) p.size - 1 else p.size
        if (if (tree) n.size < p.size else n.size != p.size) return false
        return (0 until fixed).all { glob(p[it]).matches(n[it]) }
    }

    private fun inside(file: File, rootPath: String): Boolean =
        runCatching { file.canonicalPath.startsWith(rootPath + File.separator) }.getOrDefault(false)

    private fun enter(dir: File, rootPath: String, visited: MutableSet<String>): Boolean {
        val path = runCatching { dir.canonicalPath }.getOrNull() ?: return false
        return path.startsWith(rootPath + File.separator) && visited.add(path)
    }

    private fun collect(
        dir: File,
        segments: List<String>,
        index: Int,
        names: List<String>,
        rootPath: String,
        visited: MutableSet<String>,
        emit: (List<String>, File) -> Unit,
    ) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        val segment = segments[index]
        val last = index == segments.lastIndex
        if (last && recursive(segment)) {
            for (child in children) walk(child, names + child.name, rootPath, visited, emit)
            return
        }
        val regex = glob(segment)
        for (child in children) {
            if (!regex.matches(child.name)) continue
            if (last) {
                if (child.isFile && inside(child, rootPath)) emit(names + child.name, child)
            } else if (child.isDirectory && enter(child, rootPath, visited)) {
                collect(child, segments, index + 1, names + child.name, rootPath, visited, emit)
            }
        }
    }

    private fun walk(file: File, names: List<String>, rootPath: String, visited: MutableSet<String>, emit: (List<String>, File) -> Unit) {
        if (file.isDirectory) {
            if (!enter(file, rootPath, visited)) return
            for (child in file.listFiles()?.sortedBy { it.name }.orEmpty()) walk(child, names + child.name, rootPath, visited, emit)
        } else if (file.isFile && inside(file, rootPath)) {
            emit(names, file)
        }
    }

    private fun unescape(text: String): String = ENTITY.replace(text) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> codePoint(name.substring(2).toIntOrNull(16)) ?: m.value
            name.startsWith("#") -> codePoint(name.substring(1).toIntOrNull()) ?: m.value
            name == "lt" -> "<"
            name == "gt" -> ">"
            name == "amp" -> "&"
            name == "quot" -> "\""
            name == "apos" -> "'"
            else -> m.value
        }
    }

    private fun codePoint(value: Int?): String? = value?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
}
