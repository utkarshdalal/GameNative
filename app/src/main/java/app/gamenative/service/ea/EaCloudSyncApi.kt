package app.gamenative.service.ea

import android.content.Context
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import timber.log.Timber

class EaCloudSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class EaCloudFile(val href: String, val size: Long, val md5: String?, val localName: String)

data class EaCloudManifest(val exists: Boolean, val files: List<EaCloudFile>, val lastModifiedMillis: Long?)

enum class EaCloudLockMode { READ, WRITE }

data class EaCloudLock(
    val mode: EaCloudLockMode,
    val userId: String,
    val cloudId: String,
    internal val lock: String,
    internal val manifestUrl: String,
) {
    override fun toString(): String = "EaCloudLock(mode=$mode, cloudId=$cloudId)"
}

data class EaCloudUpload(val resource: String, val md5: String?, val contentType: String?)

data class EaCloudAuthorizedUrl(val url: String, val headers: List<Pair<String, String>>) {
    override fun toString(): String = "EaCloudAuthorizedUrl(headers=${headers.map { it.first }})"
}

/** HTTP/XML protocol layer for EA (Origin/Juno) cloud saves: lock, manifest, authorize, transfer, release. */
object EaCloudSyncApi {
    const val BASE_URL = "https://cloudsync.juno.ea.com"
    const val MANIFEST_RESOURCE = "manifest.xml"
    const val MANIFEST_XMLNS = "http://origin.com/cloudsaves/manifest"

    private const val AUTH_HEADER = "X-Origin-AuthToken"
    private const val LOCK_HEADER = "X-Origin-Sync-Lock"

    private val XML_FEATURES = listOf(
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )

    @Volatile
    internal var baseUrl: String = BASE_URL

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val transferClient = client.newBuilder().callTimeout(10, TimeUnit.MINUTES).build()

    private class Verb(val verb: String, val resource: String, val md5: String?, val contentType: String?)

    suspend fun acquire(context: Context, cloudId: String, mode: EaCloudLockMode): EaCloudLock = io("EA cloud lock acquire") {
        val token = EaAuthManager.accessToken(context)
        val userId = EaAuthManager.credentials(context)?.userId?.takeIf { it.isNotBlank() }
            ?: throw EaCloudSyncException("EA cloud lock acquire: not signed in")
        val path = if (mode == EaCloudLockMode.WRITE) "lock/write" else "lock/read"
        val req = Request.Builder().url(endpoint(path, userId, cloudId))
            .header(AUTH_HEADER, token)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw EaCloudSyncException("EA cloud lock $path HTTP ${resp.code}${errorCode(text)}")
            val lock = resp.header(LOCK_HEADER)?.takeIf { it.isNotBlank() }
                ?: throw EaCloudSyncException("EA cloud lock $path HTTP ${resp.code}: no lock header in response")
            val manifestUrl = try {
                children(parseXml(text, "lock response")).firstOrNull { name(it) == "manifest" }?.textContent?.trim()
                    ?.takeIf { it.isNotEmpty() && it.toHttpUrlOrNullSafe() != null }
                    ?: throw EaCloudSyncException("EA cloud lock $path: no manifest URL in response")
            } catch (e: EaCloudSyncException) {
                release(context, EaCloudLock(mode, userId, cloudId, lock, ""))
                throw e
            }
            Timber.i("EA cloud sync: acquired $path for $cloudId")
            EaCloudLock(mode, userId, cloudId, lock, manifestUrl)
        }
    }

    suspend fun fetchManifest(lock: EaCloudLock): EaCloudManifest = io("EA cloud manifest fetch") {
        val req = Request.Builder().url(lock.manifestUrl).get().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            val lastModified = resp.headers.getDate("Last-Modified")?.time
            val missing = resp.code == 404 ||
                (!resp.isSuccessful && text.contains("<Code>NoSuchKey</Code>")) ||
                (resp.isSuccessful && text.isBlank())
            when {
                missing -> {
                    Timber.i("EA cloud sync: no manifest for ${lock.cloudId} (HTTP ${resp.code})")
                    EaCloudManifest(false, emptyList(), null)
                }
                !resp.isSuccessful -> throw EaCloudSyncException("EA cloud manifest HTTP ${resp.code}${errorCode(text)}")
                else -> EaCloudManifest(true, parseManifest(text), lastModified).also {
                    Timber.i("EA cloud sync: manifest for ${lock.cloudId} has ${it.files.size} file(s)")
                }
            }
        }
    }

    suspend fun authorizeDownloads(context: Context, lock: EaCloudLock, hrefs: List<String>): Map<String, EaCloudAuthorizedUrl> =
        authorize(context, lock, hrefs.distinct().map { Verb("GET", it, null, null) })

    suspend fun authorizeUploads(context: Context, lock: EaCloudLock, uploads: List<EaCloudUpload>): Map<String, EaCloudAuthorizedUrl> =
        authorize(context, lock, uploads.distinctBy { it.resource }.map { Verb("PUT", it.resource, it.md5, it.contentType) })

    suspend fun download(target: EaCloudAuthorizedUrl, tempFile: File): Pair<Long, ByteArray> = io("EA cloud download") {
        val builder = Request.Builder().url(target.url).get()
        target.headers.forEach { (k, v) -> builder.header(k, v) }
        try {
            transferClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw EaCloudSyncException("EA cloud download HTTP ${resp.code}${errorCode(resp.body.string())}")
                }
                val body = resp.body
                val digest = MessageDigest.getInstance("MD5")
                var total = 0L
                tempFile.parentFile?.mkdirs()
                tempFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            total += n
                        }
                    }
                }
                val expected = body.contentLength()
                if (expected >= 0 && resp.header("Content-Encoding") == null && expected != total) {
                    throw EaCloudSyncException("EA cloud download truncated: $total of $expected bytes")
                }
                total to digest.digest()
            }
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }
    }

    suspend fun uploadFile(target: EaCloudAuthorizedUrl, file: File) {
        io("EA cloud upload") {
            if (!file.isFile) throw EaCloudSyncException("EA cloud upload: source file missing")
            put(target, file.asRequestBody(null))
        }
    }

    suspend fun uploadBytes(target: EaCloudAuthorizedUrl, bytes: ByteArray) {
        io("EA cloud upload") { put(target, bytes.toRequestBody(null)) }
    }

    suspend fun release(context: Context, lock: EaCloudLock): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val token = EaAuthManager.accessToken(context)
            val req = Request.Builder().url(endpoint("lock/delete", lock.userId))
                .header(AUTH_HEADER, token)
                .header(LOCK_HEADER, lock.lock)
                .delete()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Timber.i("EA cloud sync: released ${lock.mode} lock for ${lock.cloudId}")
                } else {
                    Timber.w("EA cloud sync: lock release for ${lock.cloudId} HTTP ${resp.code}")
                }
                resp.isSuccessful
            }
        } catch (e: Throwable) {
            Timber.w("EA cloud sync: lock release for ${lock.cloudId} failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun manifestXml(files: List<EaCloudFile>): String = buildString {
        append("<manifest xmlns=\"").append(MANIFEST_XMLNS).append("\">")
        for (f in files) {
            append("<file href=\"").append(escape(f.href)).append("\" size=\"").append(f.size).append('"')
            if (f.md5 != null) append(" md5=\"").append(escape(f.md5)).append('"')
            append("><localName>").append(escape(f.localName)).append("</localName></file>")
        }
        append("</manifest>")
    }

    internal fun parseManifest(xml: String): List<EaCloudFile> {
        val root = parseXml(xml, "manifest")
        if (name(root) != "manifest") throw EaCloudSyncException("EA cloud manifest: unexpected root element <${name(root)}>")
        return children(root).filter { name(it) == "file" }.map { file ->
            val href = file.getAttribute("href").takeIf { it.isNotEmpty() }
                ?: throw EaCloudSyncException("EA cloud manifest: file entry without href")
            val size = file.getAttribute("size").trim().toLongOrNull()?.takeIf { it >= 0 }
                ?: throw EaCloudSyncException("EA cloud manifest: file entry without valid size")
            val localName = children(file).firstOrNull { name(it) == "localName" }?.textContent?.takeIf { it.isNotEmpty() }
                ?: throw EaCloudSyncException("EA cloud manifest: file entry without localName")
            EaCloudFile(href, size, file.getAttribute("md5").takeIf { it.isNotEmpty() }, localName)
        }
    }

    private suspend fun authorize(context: Context, lock: EaCloudLock, verbs: List<Verb>): Map<String, EaCloudAuthorizedUrl> {
        if (verbs.isEmpty()) return emptyMap()
        return io("EA cloud authorize") {
            val token = EaAuthManager.accessToken(context)
            val xml = buildString {
                append("<requests>")
                verbs.forEachIndexed { i, v ->
                    append("<request id=\"").append(i).append("\"><verb>").append(v.verb).append("</verb>")
                    append("<resource>").append(escape(v.resource)).append("</resource>")
                    if (v.md5 != null) append("<md5>").append(escape(v.md5)).append("</md5>")
                    if (v.contentType != null) append("<content-type>").append(escape(v.contentType)).append("</content-type>")
                    append("</request>")
                }
                append("</requests>")
            }
            val req = Request.Builder().url(endpoint("lock/authorize", lock.userId))
                .header(AUTH_HEADER, token)
                .header(LOCK_HEADER, lock.lock)
                .put(xml.toByteArray().toRequestBody("application/xml".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw EaCloudSyncException("EA cloud authorize HTTP ${resp.code}${errorCode(text)}")
                val byId = HashMap<String, EaCloudAuthorizedUrl>()
                val root = parseXml(text, "authorize response")
                for (el in listOf(root) + descendants(root)) {
                    if (name(el) != "request") continue
                    val url = children(el).firstOrNull { name(it) == "url" }?.textContent?.trim().orEmpty()
                    if (url.toHttpUrlOrNullSafe() == null) continue
                    val headers = descendants(el).filter { name(it) == "header" && it.getAttribute("key").isNotEmpty() }
                        .map { it.getAttribute("key") to it.getAttribute("value") }
                    byId[el.getAttribute("id").trim()] = EaCloudAuthorizedUrl(url, headers)
                }
                val out = LinkedHashMap<String, EaCloudAuthorizedUrl>()
                verbs.forEachIndexed { i, v ->
                    out[v.resource] = byId[i.toString()]
                        ?: throw EaCloudSyncException("EA cloud authorize: no URL for request $i (${v.verb}) of ${verbs.size}")
                }
                Timber.i("EA cloud sync: authorized ${verbs.size} ${verbs.first().verb} request(s) for ${lock.cloudId}")
                out
            }
        }
    }

    private fun put(target: EaCloudAuthorizedUrl, body: RequestBody) {
        val builder = Request.Builder().url(target.url).put(body)
        target.headers.forEach { (k, v) -> if (!k.equals("Content-Length", ignoreCase = true)) builder.header(k, v) }
        transferClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw EaCloudSyncException("EA cloud upload HTTP ${resp.code}${errorCode(resp.body.string())}")
            }
        }
    }

    private suspend fun <T> io(what: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: EaCloudSyncException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EaCloudSyncException("$what failed: ${e.javaClass.simpleName}", e)
        }
    }

    private fun endpoint(path: String, vararg segments: String): HttpUrl {
        val b = baseUrl.toHttpUrl().newBuilder().addPathSegments(path)
        segments.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun String.toHttpUrlOrNullSafe(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    private fun errorCode(body: String): String =
        Regex("<Code>([A-Za-z0-9_.-]{1,64})</Code>").find(body)?.let { " (${it.groupValues[1]})" }.orEmpty()

    private fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private fun parseXml(xml: String, what: String): Element {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
            throw EaCloudSyncException("EA cloud $what: DTD declarations are not allowed")
        }
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isExpandEntityReferences = false
            for ((feature, value) in XML_FEATURES) runCatching { factory.setFeature(feature, value) }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw EaCloudSyncException("EA cloud $what: external entities are not allowed") }
            builder.setErrorHandler(null)
            return builder.parse(InputSource(StringReader(xml))).documentElement
                ?: throw EaCloudSyncException("EA cloud $what: empty XML document")
        } catch (e: EaCloudSyncException) {
            throw e
        } catch (e: Exception) {
            throw EaCloudSyncException("EA cloud $what: not valid XML (${e.javaClass.simpleName})", e)
        }
    }

    private fun name(el: Element): String = (el.localName ?: el.nodeName).substringAfter(':')

    private fun children(el: Element): List<Element> {
        val nodes = el.childNodes
        return (0 until nodes.length).mapNotNull { i -> nodes.item(i).takeIf { it.nodeType == Node.ELEMENT_NODE } as? Element }
    }

    private fun descendants(el: Element): List<Element> = children(el).flatMap { listOf(it) + descendants(it) }
}
