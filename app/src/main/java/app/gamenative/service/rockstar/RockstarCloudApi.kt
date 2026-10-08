package app.gamenative.service.rockstar

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import timber.log.Timber

class RockstarCloudException(message: String, val code: String? = null, val httpStatus: Int = 0, cause: Throwable? = null) :
    Exception(message, cause)

data class RockstarCloudFile(
    val id: String,
    val path: String,
    val version: Long,
    val nextVersion: Long,
    val size: Long,
    val serverLastModifiedUtc: String?,
    val lastHardwareId: String?,
    val md5: String?,
)

data class RockstarCloudManifest(val rockstarId: String?, val bytesUsed: Long?, val files: List<RockstarCloudFile>)

data class RockstarCloudDownload(val size: Long, val md5: String, val version: Long?, val lastModified: String?)

data class RockstarCloudPosted(val version: Long?, val path: String?, val serverLastModifiedUtc: String?)

enum class RockstarResolveType(val wire: String) { NONE("None"), ACCEPT_REMOTE("AcceptRemote"), ACCEPT_LOCAL("AcceptLocal") }

/** Plain-form HTTPS client for the Rockstar launcher services used by cloud saves. */
object RockstarCloudApi {
    const val PRIMARY_HOST = "rgl-prod.ros.rockstargames.com"
    const val FALLBACK_HOST = "prod.ros.rockstargames.com"
    const val AUTH_PATH = "/launcher/11/launcherservices/auth.asmx/CreateTicketScAuthToken2"
    const val TITLE_TOKEN_PATH = "/launcher/11/launcherservices/app.asmx/GetTitleAccessToken2"
    const val CLOUD_PATH = "/launcher/11/launcherservices/cloudsave.asmx/"

    private const val TAG = "RockstarCloud"
    private const val PLATFORM_ID = 8
    private const val MAX_XML_BYTES = 1024 * 1024
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private val FORM_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    private val OCTET_TYPE = "application/octet-stream".toMediaType()

    private val XML_FEATURES = listOf(
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )

    data class LauncherSession(val ticket: String, val rockstarId: String?) {
        override fun toString(): String = "LauncherSession(rockstarId=$rockstarId)"
    }

    @Volatile
    internal var cloudHost: String = PRIMARY_HOST

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val transferClient = client.newBuilder().callTimeout(10, TimeUnit.MINUTES).build()

    suspend fun createLauncherTicket(scAuthToken: String): LauncherSession = io("Rockstar launcher ticket") {
        val form = "ticket=&scAuthToken=${urlenc(scAuthToken)}&platformName=pcros&rememberedMachineToken="
        val root = postXml(PRIMARY_HOST, AUTH_PATH, form)
        val ticket = field(root, "Ticket")?.takeIf { it.isNotEmpty() }
            ?: throw RockstarCloudException("Rockstar launcher ticket: no ticket in response")
        LauncherSession(ticket, field(root, "RockstarId")?.takeIf { it.isNotEmpty() })
    }

    suspend fun titleAccessToken(session: LauncherSession, rosTitleId: Int): String = io("Rockstar title access token") {
        val form = "ticket=${urlenc(session.ticket)}&titleId=$rosTitleId&platformId=$PLATFORM_ID"
        val root = postXml(PRIMARY_HOST, TITLE_TOKEN_PATH, form)
        field(root, "Result")?.takeIf { it.isNotEmpty() }
            ?: throw RockstarCloudException("Rockstar title access token: no Result in response")
    }

    suspend fun manifest(session: LauncherSession, titleAccessToken: String): RockstarCloudManifest = io("Rockstar cloud manifest") {
        val form = "ticket=${urlenc(session.ticket)}&titleAccessToken=${urlenc(titleAccessToken)}"
        val root = cloudXml("GetCloudSaveManifest") { formBody(form) }
        parseManifest(root)
    }

    suspend fun getFile(
        context: Context,
        session: LauncherSession,
        titleAccessToken: String,
        fileId: String,
        dest: File,
        resolveType: RockstarResolveType = RockstarResolveType.NONE,
    ): RockstarCloudDownload = io("Rockstar cloud download") {
        val form = "ticket=${urlenc(session.ticket)}&titleAccessToken=${urlenc(titleAccessToken)}&fileId=${urlenc(fileId)}" +
            "&resolveType=${resolveType.wire}&hardwareId=${urlenc(hardwareId(context))}"
        cloudCall("GetFile", transferClient) { formBody(form) }.use { resp ->
            val contentType = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            if (contentType == "text/xml" || contentType == "application/xml" || contentType == "text/html") {
                val text = readBounded(resp)
                Timber.tag(TAG).w("GetFile returned $contentType instead of file bytes: ${excerpt(text)}")
                throw runCatching { parseXml(text, "GetFile") }.map { statusError("GetFile", it) }
                    .getOrElse { RockstarCloudException("Rockstar GetFile returned $contentType instead of file bytes") }
            }
            val version = resp.header("SCS-File-Version")?.trim()?.toLongOrNull()
            val lastModified = resp.header("Last-Modified")
            val hash = resp.header("SCS-File-Hash")
            dest.parentFile?.mkdirs()
            val md5 = MessageDigest.getInstance("MD5")
            var size = 0L
            resp.body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > MAX_FILE_BYTES) throw RockstarCloudException("Rockstar cloud download: file exceeds size limit")
                        md5.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val digest = hex(md5.digest())
            if (hash != null) {
                val expected = normalizeMd5(hash)
                if (expected == null) {
                    Timber.tag(TAG).w("GetFile: SCS-File-Hash has an unrecognised shape (${hash.length} chars), not verified")
                } else if (expected != digest) {
                    dest.delete()
                    throw RockstarCloudException("Rockstar cloud download: content does not match SCS-File-Hash")
                }
            } else {
                Timber.tag(TAG).w("GetFile: no SCS-File-Hash header, download not verified")
            }
            Timber.tag(TAG).d("GetFile: $size bytes, version=$version, lastModified=$lastModified")
            RockstarCloudDownload(size, digest, version, lastModified)
        }
    }

    suspend fun postFile(
        context: Context,
        session: LauncherSession,
        titleAccessToken: String,
        file: File,
        fileName: String,
        expectedVersion: Long,
        lastModified: String,
        fileMetadata: String,
        resolveType: RockstarResolveType = RockstarResolveType.NONE,
    ): RockstarCloudPosted = io("Rockstar cloud upload") {
        val hw = hardwareId(context)
        val root = cloudXml("PostFile", transferClient) {
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("ticket", session.ticket)
                .addFormDataPart("hardwareId", hw)
                .addFormDataPart("resolveType", resolveType.wire)
                .addFormDataPart("titleAccessToken", titleAccessToken)
                .addFormDataPart("lastModified", lastModified)
                .addFormDataPart("expectedVersion", expectedVersion.toString())
                .addFormDataPart("fileMetadata", fileMetadata)
                .addFormDataPart("file1", fileName, file.asRequestBody(OCTET_TYPE))
                .build()
        }
        val results = descendants(root).filter { name(it).equals("Result", ignoreCase = true) && field(it, "FileId") != null }
        val result = results.firstOrNull { field(it, "Path")?.let { p -> sameName(p, fileName) } == true } ?: results.firstOrNull()
        if (result == null) {
            Timber.tag(TAG).w("PostFile: no Result element in response")
            throw RockstarCloudException("Rockstar PostFile: no Result in response")
        }
        RockstarCloudPosted(
            version = field(result, "Version")?.toLongOrNull(),
            path = field(result, "Path"),
            serverLastModifiedUtc = field(result, "ServerLastModifiedUtc"),
        )
    }

    fun hardwareId(context: Context): String {
        val file = File(context.filesDir, "rockstar_cloud/hardware_id")
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { Regex("[0-9a-f]{32}").matches(it) }?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = hex(bytes)
        file.parentFile?.mkdirs()
        file.writeText(id)
        return id
    }

    fun urlenc(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if ((ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                append(ch)
            } else {
                append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
            }
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun normalizeMd5(value: String): String? {
        val v = value.trim()
        if (Regex("[0-9A-Fa-f]{32}").matches(v)) return v.lowercase()
        if (Regex("[A-Za-z0-9+/]{22}(==)?").matches(v)) {
            val decoded = runCatching { Base64.decode(v, Base64.DEFAULT) }.getOrNull()
            if (decoded != null && decoded.size == 16) return hex(decoded)
        }
        return null
    }

    internal fun parseManifest(root: Element): RockstarCloudManifest {
        val entries = descendants(root).filter { el -> attrOrChild(el, "Path") != null && attrOrChild(el, "Id") != null }
        val files = entries.map { el ->
            RockstarCloudFile(
                id = field(el, "Id").orEmpty(),
                path = field(el, "Path").orEmpty(),
                version = field(el, "Version")?.toLongOrNull() ?: 0L,
                nextVersion = field(el, "NextVersion")?.toLongOrNull() ?: ((field(el, "Version")?.toLongOrNull() ?: 0L) + 1),
                size = field(el, "Size")?.toLongOrNull() ?: -1L,
                serverLastModifiedUtc = field(el, "ServerLastModifiedUtc"),
                lastHardwareId = field(el, "LastHardwareId"),
                md5 = field(el, "MD5Hash")?.let { raw ->
                    normalizeMd5(raw).also { if (it == null) Timber.tag(TAG).w("Manifest MD5Hash has an unrecognised shape (${raw.length} chars)") }
                },
            )
        }
        val rockstarId = (listOf(root) + descendants(root)).firstOrNull { el -> el !in entries && attrOrChild(el, "RockstarId") != null }
            ?.let { attrOrChild(it, "RockstarId") }
        val bytesUsed = (listOf(root) + descendants(root)).firstNotNullOfOrNull { attrOrChild(it, "BytesUsed")?.toLongOrNull() }
        Timber.tag(TAG).i("Manifest: ${files.size} file(s), rockstarId=${rockstarId != null}, bytesUsed=$bytesUsed")
        return RockstarCloudManifest(rockstarId, bytesUsed, files)
    }

    internal fun parseXmlForTest(xml: String): Element = parseXml(xml, "test")

    private fun formBody(form: String): RequestBody = form.toRequestBody(FORM_TYPE)

    private fun postXml(host: String, path: String, form: String): Element {
        val req = Request.Builder().url("https://$host$path").post(formBody(form)).build()
        client.newCall(req).execute().use { resp ->
            val text = readBounded(resp)
            Timber.tag(TAG).d("POST $path -> HTTP ${resp.code} on $host")
            if (resp.code != 200) {
                Timber.tag(TAG).w("POST $path on $host returned HTTP ${resp.code}: ${excerpt(text)}")
                throw RockstarCloudException("Rockstar $path HTTP ${resp.code}${errorSuffix(text)}", errorCode(text), resp.code)
            }
            val root = parseXml(text, path)
            checkStatus(path, root, text)
            return root
        }
    }

    private fun cloudXml(method: String, http: OkHttpClient = client, body: () -> RequestBody): Element {
        cloudCall(method, http, body).use { resp ->
            val text = readBounded(resp)
            val root = parseXml(text, method)
            checkStatus(method, root, text)
            return root
        }
    }

    private fun cloudCall(method: String, http: OkHttpClient, body: () -> RequestBody): Response {
        val path = CLOUD_PATH + method
        val first = cloudHost
        var resp = execute(http, first, path, body())
        if (resp.code == 404 && first == PRIMARY_HOST) {
            Timber.tag(TAG).w("POST $path on $first returned 404, trying $FALLBACK_HOST")
            resp.close()
            resp = execute(http, FALLBACK_HOST, path, body())
            if (resp.code != 404) {
                Timber.tag(TAG).w("Cloud save service answered on $FALLBACK_HOST (HTTP ${resp.code}), using it from now on")
                cloudHost = FALLBACK_HOST
            }
        }
        if (resp.code != 200) {
            val text = resp.use { readBounded(it) }
            Timber.tag(TAG).w("POST $path on $cloudHost returned HTTP ${resp.code}: ${excerpt(text)}")
            throw RockstarCloudException("Rockstar $method HTTP ${resp.code}${errorSuffix(text)}", errorCode(text), resp.code)
        }
        return resp
    }

    private fun execute(http: OkHttpClient, host: String, path: String, body: RequestBody): Response {
        val resp = http.newCall(Request.Builder().url("https://$host$path").post(body).build()).execute()
        Timber.tag(TAG).d("POST $path -> HTTP ${resp.code} on $host")
        return resp
    }

    private fun checkStatus(what: String, root: Element, text: String) {
        val status = field(root, "Status") ?: return
        if (status.trim() == "1") return
        Timber.tag(TAG).w("$what returned Status=$status: ${excerpt(text)}")
        throw statusError(what, root)
    }

    private fun statusError(what: String, root: Element): RockstarCloudException {
        val code = errorCode(root)
        return RockstarCloudException("Rockstar $what failed${code?.let { " ($it)" }.orEmpty()}", code)
    }

    private fun errorCode(root: Element): String? {
        val error = (listOf(root) + descendants(root)).firstOrNull { name(it).equals("Error", ignoreCase = true) } ?: return null
        val code = error.getAttribute("Code").takeIf { it.isNotEmpty() } ?: field(error, "Code")
        val ex = error.getAttribute("CodeEx").takeIf { it.isNotEmpty() } ?: field(error, "CodeEx")
        return listOfNotNull(code, ex).joinToString("/").takeIf { it.isNotEmpty() }
    }

    private fun errorCode(text: String): String? = runCatching { errorCode(parseXml(text, "error")) }.getOrNull()

    private fun errorSuffix(text: String): String = errorCode(text)?.let { " ($it)" }.orEmpty()

    private fun readBounded(resp: Response): String {
        val bytes = resp.body.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_XML_BYTES) throw RockstarCloudException("Rockstar response exceeds size limit")
            }
            out.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun excerpt(text: String): String = text.take(300).replace(Regex("\\s+"), " ")

    private fun sameName(a: String, b: String): Boolean =
        a.substringAfterLast('/').substringAfterLast('\\').equals(b.substringAfterLast('/').substringAfterLast('\\'), ignoreCase = true)

    private suspend fun <T> io(what: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: RockstarCloudException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RockstarCloudException("$what failed: ${e.javaClass.simpleName}", cause = e)
        }
    }

    private fun parseXml(xml: String, what: String): Element {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
            throw RockstarCloudException("Rockstar $what: DTD declarations are not allowed")
        }
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isExpandEntityReferences = false
            for ((feature, value) in XML_FEATURES) runCatching { factory.setFeature(feature, value) }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw RockstarCloudException("Rockstar $what: external entities are not allowed") }
            builder.setErrorHandler(null)
            return builder.parse(InputSource(StringReader(xml.trimStart('\uFEFF')))).documentElement
                ?: throw RockstarCloudException("Rockstar $what: empty XML document")
        } catch (e: RockstarCloudException) {
            throw e
        } catch (e: Exception) {
            throw RockstarCloudException("Rockstar $what: not valid XML (${e.javaClass.simpleName})", cause = e)
        }
    }

    private fun name(el: Element): String = (el.localName ?: el.nodeName).substringAfter(':')

    private fun children(el: Element): List<Element> {
        val nodes = el.childNodes
        return (0 until nodes.length).mapNotNull { i -> nodes.item(i).takeIf { it.nodeType == Node.ELEMENT_NODE } as? Element }
    }

    private fun descendants(el: Element): List<Element> = children(el).flatMap { listOf(it) + descendants(it) }

    private fun attrOrChild(el: Element, key: String): String? {
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if ((a.localName ?: a.nodeName).substringAfter(':').equals(key, ignoreCase = true)) return a.nodeValue?.trim()
        }
        return children(el).firstOrNull { name(it).equals(key, ignoreCase = true) && children(it).isEmpty() }?.textContent?.trim()
    }

    private fun field(el: Element, key: String): String? =
        attrOrChild(el, key) ?: descendants(el).firstOrNull { name(it).equals(key, ignoreCase = true) && children(it).isEmpty() }?.textContent?.trim()
}
