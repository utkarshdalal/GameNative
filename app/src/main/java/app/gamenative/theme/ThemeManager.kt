package app.gamenative.theme

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import app.gamenative.PrefManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber

/**
 * Installed CSS Loader themes: storage under `filesDir/themes`, user options, and the merged [activeSpec]
 * of every enabled theme (later themes in [themes] win).
 */
object ThemeManager {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    private const val STATE_FILE = "state.json"
    private const val STATE_DIR = ".state"
    private const val CLASS_MAP_FILE = ".classmap2.json"
    private const val OLD_CLASS_MAP_FILE = ".classmap.json"
    private const val TMP_DIR = ".tmp"

    private val started = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var appContext: Context

    private val _themes = MutableStateFlow<List<InstalledTheme>>(emptyList())
    val themes: StateFlow<List<InstalledTheme>> = _themes.asStateFlow()

    private val _activeSpec = MutableStateFlow(ThemeSpec.EMPTY)
    val activeSpec: StateFlow<ThemeSpec> = _activeSpec.asStateFlow()

    private val _shareDisplayName = MutableStateFlow("")
    /** Public name last used to share a theme; empty until one is saved. */
    val shareDisplayName: StateFlow<String> = _shareDisplayName.asStateFlow()

    private class Entry(
        val id: String,
        var enabled: Boolean,
        val choices: MutableMap<String, String> = LinkedHashMap(),
        val colors: MutableMap<String, String> = LinkedHashMap(),
        /** Component key -> file name in the theme's state folder. */
        val images: MutableMap<String, String> = LinkedHashMap(),
        /** Store id and version when the theme was installed from the store. */
        var storeId: String? = null,
        var storeVersion: String? = null,
        var updateAvailable: String? = null,
        /** Community theme this folder was installed from ([communityInstalled]) or shared as. */
        var communityId: String? = null,
        var communityVersion: Int? = null,
        var communityInstalled: Boolean = false,
        /** The community theme belongs to the signed-in account, as last reported by the server or an upload. */
        var communityMine: Boolean = false,
    )

    private class ShareInfo(val spec: ThemeSpec, val blocker: ShareBlocker?)

    private val entries = ArrayList<Entry>()
    @Volatile private var shareInfo: Map<String, ShareInfo> = emptyMap()
    /** Latest list record of each community theme seen this session. */
    private val communitySeen = ConcurrentHashMap<String, CommunityTheme>()
    private val community: CommunityThemeClient get() = CommunityThemeClient.default
    @Volatile private var communityTermsCache: CommunityTerms? = null
    private val manifests = HashMap<String, DeckyThemeManifest>()
    private var classMap = SteamClassMap.EMPTY

    private val root: File get() = File(appContext.filesDir, "themes")
    private fun themeDir(id: String) = File(root, id)
    private fun stateDir(id: String) = File(File(root, STATE_DIR), id)

    /** True once the theme folder is loaded; it stays unloaded, and nothing is written, until the feature is first used. */
    @Volatile private var prepared = false

    /** Loads installed themes when the theme folder exists; without it nothing is read, created or fetched. */
    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        scope.launch {
            var needsClassMap = false
            try {
                if (root.isDirectory) {
                    mutex.withLock {
                        prepareLocked()
                        needsClassMap = entries.any { it.enabled && it.id != BuiltInTheme.ID }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "ThemeManager: load failed")
            } finally {
                ready.complete(Unit)
            }
            if (needsClassMap) refreshClassMapIfStale()
        }
    }

    private fun prepareLocked() {
        if (prepared) return
        val saved = loadLocked()
        try {
            ensureBuiltInLocked(saved)
        } catch (e: Exception) {
            Timber.e(e, "ThemeManager: built-in theme unavailable")
        }
        recomputeLocked()
        prepared = true
    }

    private suspend fun <T> op(name: String, block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        if (!started.get()) return@withContext Result.failure(IllegalStateException("ThemeManager.init was not called"))
        try {
            ready.await()
            if (!prepared) mutex.withLock { prepareLocked() }
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "ThemeManager: $name failed")
            Result.failure(e)
        }
    }

    private suspend fun edit(name: String, id: String, block: (Entry) -> Unit) {
        op(name) {
            mutex.withLock {
                val e = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("No theme $id")
                block(e)
                saveLocked()
                recomputeLocked()
            }
        }
    }

    suspend fun importZip(uri: Uri): Result<InstalledTheme> = op("importZip") {
        val zip = File(appContext.cacheDir, "theme-import-${System.nanoTime()}.zip")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { copyCapped(it, zip, ThemeStoreClient.MAX_ZIP_BYTES) }
                ?: throw IOException("Cannot read the selected file")
            installZip(zip, displayName(uri)?.substringBeforeLast('.'), enable = true, store = null)
        } finally {
            zip.delete()
        }
    }

    suspend fun importFolder(treeUri: Uri): Result<InstalledTheme> = op("importFolder") {
        val work = newWorkDir()
        try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val name = queryName(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
            copyTree(treeUri, rootId, work, IntArray(1), LongArray(1), 0)
            val themeRoot = ThemeZip.findThemeRoot(work) ?: throw IOException("Folder has no theme.json or theme.css")
            installFolder(themeRoot, name, enable = true, store = null)
        } finally {
            work.deleteRecursively()
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = edit("setEnabled", id) { it.enabled = enabled }

    suspend fun delete(id: String) {
        if (id == BuiltInTheme.ID) return
        op("delete") {
            mutex.withLock {
                entries.removeAll { it.id == id }
                manifests.remove(id)
                themeDir(id).deleteRecursively()
                stateDir(id).deleteRecursively()
                ThemeLoader.clearCache()
                saveLocked()
                recomputeLocked()
            }
        }
    }

    suspend fun move(id: String, up: Boolean) {
        op("move") {
            mutex.withLock {
                val i = entries.indexOfFirst { it.id == id }
                val j = if (up) i - 1 else i + 1
                if (i >= 0 && j in entries.indices) {
                    val t = entries[i]
                    entries[i] = entries[j]
                    entries[j] = t
                    saveLocked()
                    recomputeLocked()
                }
            }
        }
    }

    suspend fun setChoice(id: String, key: String, value: String) = edit("setChoice", id) { e ->
        val patch = manifests[id]?.patches?.firstOrNull { it.name == key } ?: throw IllegalArgumentException("No option $key")
        if (value !in patch.optionNames) throw IllegalArgumentException("No value $value for $key")
        e.choices[key] = value
    }

    suspend fun setColor(id: String, key: String, argb: Int) = edit("setColor", id) { e ->
        val c = component(id, key, DeckyThemeManifest.COLOR_PICKER)
        e.colors[c.key] = Rgba.fromArgb(argb).toHex()
    }

    suspend fun setImage(id: String, key: String, uri: Uri?) {
        op("setImage") {
            val c = mutex.withLock { component(id, key, DeckyThemeManifest.IMAGE_PICKER) }
            val dir = stateDir(id)
            var copied: String? = null
            if (uri != null) {
                dir.mkdirs()
                val ext = when (appContext.contentResolver.getType(uri)) {
                    "image/png" -> "png"
                    "image/jpeg" -> "jpg"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "img"
                }
                val name = "img-${c.key.hashCode().toUInt().toString(16)}-${System.currentTimeMillis()}.$ext"
                val f = File(dir, name)
                appContext.contentResolver.openInputStream(uri)?.use { copyCapped(it, f, MAX_IMAGE_BYTES) }
                    ?: throw IOException("Cannot read the selected image")
                copied = name
            }
            mutex.withLock {
                val e = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("No theme $id")
                e.images.remove(c.key)?.let { File(dir, it).delete() }
                copied?.let { e.images[c.key] = it }
                saveLocked()
                recomputeLocked()
            }
        }
    }

    suspend fun resetOptions(id: String) = edit("resetOptions", id) { e ->
        e.choices.clear()
        e.colors.clear()
        e.images.clear()
        stateDir(id).deleteRecursively()
    }

    suspend fun exportStarterTheme(destZip: Uri): Result<Unit> = op("exportStarterTheme") {
        val out = appContext.contentResolver.openOutputStream(destZip) ?: throw IOException("Cannot write the file")
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry("${StarterTheme.FOLDER}/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("theme.json", StarterTheme.themeJson.toByteArray())
            put("theme.css", StarterTheme.themeCss.toByteArray())
            put(StarterTheme.IMAGE, starterImage())
        }
    }

    suspend fun storeList(query: String, page: Int): Result<List<StoreTheme>> = op("storeList") {
        ThemeStoreClient.list(query, page)
    }

    suspend fun installFromStore(theme: StoreTheme): Result<InstalledTheme> = op("installFromStore") {
        val details = try {
            ThemeStoreClient.details(theme.id)
        } catch (e: IOException) {
            Timber.w(e, "ThemeManager: store record of ${theme.id} unavailable")
            null
        }
        val record = details?.first ?: theme
        val parent = downloadAndInstall(record, enable = true)
        for (dep in details?.second.orEmpty()) {
            val installed = mutex.withLock { findByName(dep.name) != null }
            if (installed) continue
            try {
                val depRecord = ThemeStoreClient.details(dep.id)?.first ?: dep
                downloadAndInstall(depRecord, enable = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ThemeManager: dependency ${dep.name} failed to install")
            }
        }
        _themes.value.firstOrNull { it.id == parent.id } ?: parent
    }

    /**
     * Asks the store for the current version of every theme installed from it (one request each), and the community
     * list for every theme installed from it (one search by name each), and sets [InstalledTheme.updateAvailable].
     * Failures leave a theme's state as it was; nothing is thrown.
     */
    suspend fun checkForUpdates() {
        op("checkForUpdates") {
            if (mutex.withLock { entries.any { it.id != BuiltInTheme.ID } }) scope.launch { refreshClassMapIfStale() }
            val targets = mutex.withLock { entries.mapNotNull { e -> e.storeId?.let { Triple(e.id, it, e.storeVersion) } } }
            val communityTargets = mutex.withLock {
                entries.filter { it.communityInstalled && it.communityId != null }.map { e ->
                    Triple(e.id, e.communityId!!, manifests[e.id]?.label ?: e.id)
                }
            }
            if (targets.isEmpty() && communityTargets.isEmpty()) return@op
            val found = HashMap<String, String?>()
            for ((id, storeId, installed) in targets) {
                val latest = try {
                    ThemeStoreClient.details(storeId)?.first?.version
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "ThemeManager: update check for $id failed")
                    continue
                }
                found[id] = latest?.takeIf { StoreVersion.isNewer(it, installed) }
            }
            val communityLatest = HashMap<String, Int>()
            for ((id, communityId, name) in communityTargets) {
                val listed = try {
                    community.list(name, 1, CommunitySort.POPULAR, mineOnly = false).themes.onEach { communitySeen[it.id] = it }
                        .firstOrNull { it.id == communityId }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "ThemeManager: community update check for $id failed")
                    null
                }
                (listed ?: communitySeen[communityId])?.let { communityLatest[id] = it.version }
            }
            mutex.withLock {
                for ((id, v) in found) entries.firstOrNull { it.id == id }?.updateAvailable = v
                for ((id, v) in communityLatest) {
                    val e = entries.firstOrNull { it.id == id } ?: continue
                    e.updateAvailable = communityUpdate(v, e.communityVersion)
                }
                recomputeLocked()
            }
        }
    }

    private fun communityUpdate(listed: Int, installed: Int?): String? = CommunityThemeClient.updateVersion(listed, installed)

    /** Downloads the current version of [id] from the store or the community list into its folder; options and the enabled state are kept. */
    suspend fun updateFromStore(id: String): Result<InstalledTheme> = op("updateFromStore") {
        val communityRecord = mutex.withLock {
            val e = entries.firstOrNull { it.id == id }
            val cid = e?.communityId
            if (e == null || cid == null || !e.communityInstalled || e.storeId != null) {
                null
            } else {
                val latest = e.updateAvailable?.toIntOrNull() ?: e.communityVersion ?: 1
                val seen = communitySeen[cid]
                CommunityTheme(
                    id = cid,
                    name = manifests[id]?.label ?: id,
                    version = maxOf(latest, seen?.version ?: 0),
                    mine = seen?.mine ?: e.communityMine,
                )
            }
        }
        if (communityRecord != null) {
            downloadCommunity(communityRecord, forceId = id)
            return@op _themes.value.first { it.id == id }
        }
        val storeId = mutex.withLock { entries.firstOrNull { it.id == id }?.storeId }
            ?: throw IllegalArgumentException("Theme $id was not installed from the store")
        val record = ThemeStoreClient.details(storeId)?.first ?: throw IOException("Theme $id is no longer in the store")
        downloadAndInstall(record, enable = false, forceId = id)
        _themes.value.first { it.id == id }
    }

    val isSignedIn: Boolean get() = PrefManager.gameNativeSignedIn.value

    private fun communityFailure(e: Throwable?, fallback: String): CommunityException = when (e) {
        is CommunityException -> e
        else -> CommunityException(fallback, e?.message)
    }

    private suspend fun <T> communityOp(name: String, fallback: String, needsAccount: Boolean, block: suspend () -> T): Result<T> {
        if (needsAccount && !isSignedIn) return Result.failure(CommunityException("unauthorized"))
        val r = op(name, block)
        return r.exceptionOrNull()?.let { Result.failure(communityFailure(it, fallback)) } ?: r
    }

    /** The community listing of a theme this account shared, from the last list seen or the account's uploads. */
    suspend fun sharedListing(communityId: String): CommunityTheme? =
        communitySeen[communityId]
            ?: communityList("", 1, CommunitySort.NEW, mineOnly = true).getOrNull()?.themes?.firstOrNull { it.id == communityId }

    suspend fun communityList(query: String, page: Int, sort: CommunitySort, mineOnly: Boolean = false): Result<CommunityPage> =
        communityOp("communityList", "server", needsAccount = mineOnly) {
            val result = community.list(query, page, sort, mineOnly)
            for (t in result.themes) communitySeen[t.id] = t
            mutex.withLock {
                var changed = false
                for (t in result.themes) for (e in entries) {
                    if (e.communityId != t.id) continue
                    if (e.communityMine != t.mine) {
                        e.communityMine = t.mine
                        changed = true
                    }
                    if (e.communityInstalled) {
                        val v = communityUpdate(t.version, e.communityVersion)
                        if (v != e.updateAvailable) {
                            e.updateAvailable = v
                            changed = true
                        }
                    }
                }
                if (changed) {
                    saveLocked()
                    recomputeLocked()
                }
            }
            result
        }

    suspend fun installFromCommunity(theme: CommunityTheme): Result<InstalledTheme> =
        communityOp("installFromCommunity", "invalid_theme", needsAccount = false) {
            val existing = mutex.withLock { entries.firstOrNull { it.communityInstalled && it.communityId == theme.id }?.id }
            val installed = downloadCommunity(theme, existing)
            _themes.value.firstOrNull { it.id == installed.id } ?: installed
        }

    private suspend fun downloadCommunity(theme: CommunityTheme, forceId: String?): InstalledTheme {
        val zip = File(appContext.cacheDir, "theme-community-${System.nanoTime()}.zip")
        try {
            community.download(theme.id, zip)
            return installZip(zip, theme.name, enable = true, store = null, forceId = forceId, community = theme)
        } finally {
            zip.delete()
        }
    }

    /** Upload terms of the community list, fetched once per session and again after an upload reports them outdated. */
    suspend fun communityTerms(): Result<CommunityTerms> =
        communityOp("communityTerms", "server", needsAccount = false) {
            communityTermsCache ?: community.terms().also { communityTermsCache = it }
        }

    suspend fun setShareDisplayName(name: String) {
        op("setShareDisplayName") {
            mutex.withLock { saveShareDisplayNameLocked(name) }
        }
    }

    private fun saveShareDisplayNameLocked(name: String) {
        val n = ShareDisplayName.normalize(name)
        if (n == _shareDisplayName.value) return
        _shareDisplayName.value = n
        saveLocked()
    }

    fun shareBlocker(id: String): ShareBlocker? = shareInfo[id]?.blocker ?: if (shareInfo.containsKey(id)) null else ShareBlocker.EMPTY

    suspend fun shareTheme(
        id: String,
        name: String,
        description: String,
        authorName: String,
        termsVersion: String,
    ): Result<CommunityTheme> =
        communityOp("shareTheme", "invalid_theme", needsAccount = true) {
            val title = name.trim()
            if (title.isEmpty()) throw CommunityException("invalid_theme", "The theme needs a name")
            val (info, version, linked) = mutex.withLock {
                val e = entries.firstOrNull { it.id == id } ?: throw CommunityException("not_found", "No theme $id")
                val linkedId = e.communityId?.takeIf { cid ->
                    val seen = communitySeen[cid]
                    if (seen != null) seen.mine else e.communityMine
                }
                Triple(shareInfo[id] ?: throw CommunityException("invalid_theme"), manifests[id]?.version ?: "v1.0", linkedId)
            }
            when (info.blocker) {
                ShareBlocker.NOT_NATIVE, ShareBlocker.EMPTY -> throw CommunityException("invalid_theme")
                ShareBlocker.TOO_LARGE -> throw CommunityException("too_large")
                null -> {}
            }
            val spec = info.spec
            val zip = File(appContext.cacheDir, "theme-share-${System.nanoTime()}.zip")
            val uploaded = try {
                CommunityThemePackage.write(spec, title, authorName.trim(), version, description.trim(), zip)
                if (zip.length() > CommunityThemePackage.MAX_ZIP_BYTES) throw CommunityException("too_large")
                val bytes = zip.readBytes()
                fun meta(replaces: String?) =
                    CommunityThemeClient.uploadMeta(title, description.trim(), authorName.trim(), spec, replaces, termsVersion)
                val result = try {
                    try {
                        community.upload(meta(linked), bytes)
                    } catch (e: CommunityException) {
                        if (linked == null || e.code != "not_found") throw e
                        community.upload(meta(null), bytes)
                    }
                } catch (e: CommunityException) {
                    if (e.code == "terms_outdated" || e.code == "terms_required") communityTermsCache = null
                    throw e
                }
                CommunityTheme(
                    id = result.id,
                    name = title,
                    description = description.trim().ifEmpty { null },
                    authorName = authorName.trim().ifEmpty { null },
                    version = result.version,
                    downloads = communitySeen[result.id]?.downloads ?: 0,
                    sizeBytes = bytes.size.toLong(),
                    preview = spec.copy(fontFile = null, backgroundImage = null).takeIf { !it.isEmpty },
                    mine = true,
                )
            } finally {
                zip.delete()
            }
            communitySeen[uploaded.id] = uploaded
            mutex.withLock {
                val e = entries.firstOrNull { it.id == id }
                if (e != null) {
                    e.communityId = uploaded.id
                    e.communityVersion = uploaded.version
                    e.communityMine = true
                    e.communityInstalled = false
                    e.updateAvailable = null
                }
                _shareDisplayName.value = ShareDisplayName.normalize(authorName)
                saveLocked()
                if (e != null) recomputeLocked()
            }
            uploaded
        }

    suspend fun deleteCommunityTheme(communityId: String): Result<Unit> =
        communityOp("deleteCommunityTheme", "server", needsAccount = true) {
            community.delete(communityId)
            communitySeen.remove(communityId)
            mutex.withLock {
                var changed = false
                for (e in entries) {
                    if (e.communityId != communityId) continue
                    e.communityId = null
                    e.communityVersion = null
                    e.communityInstalled = false
                    e.communityMine = false
                    e.updateAvailable = if (e.storeId != null) e.updateAvailable else null
                    changed = true
                }
                if (changed) {
                    saveLocked()
                    recomputeLocked()
                }
            }
        }

    suspend fun reportCommunityTheme(communityId: String, reason: String, details: String?): Result<Unit> =
        communityOp("reportCommunityTheme", "server", needsAccount = true) {
            community.report(communityId, reason, details)
        }

    private suspend fun downloadAndInstall(t: StoreTheme, enable: Boolean, forceId: String? = null): InstalledTheme {
        val blob = t.downloadBlobId ?: throw IOException("Theme ${t.name} has no download")
        val zip = File(appContext.cacheDir, "theme-store-${System.nanoTime()}.zip")
        try {
            ThemeStoreClient.downloadBlob(blob, zip)
            return installZip(zip, t.name, enable, t, forceId)
        } finally {
            zip.delete()
        }
    }

    private suspend fun installZip(
        zip: File,
        fallbackName: String?,
        enable: Boolean,
        store: StoreTheme?,
        forceId: String? = null,
        community: CommunityTheme? = null,
    ): InstalledTheme {
        val work = newWorkDir()
        try {
            ThemeZip.extract(zip, work)
            val themeRoot = ThemeZip.findThemeRoot(work) ?: throw IOException("No theme.json or theme.css in the zip")
            return installFolder(themeRoot, fallbackName, enable, store, forceId, community)
        } finally {
            work.deleteRecursively()
        }
    }

    /** A new community theme takes a free folder name instead of replacing a local theme with the same name. */
    private suspend fun installFolder(
        src: File,
        fallbackName: String?,
        enable: Boolean,
        store: StoreTheme?,
        forceId: String? = null,
        community: CommunityTheme? = null,
    ): InstalledTheme {
        val parsed = ThemeLoader.loadManifest(src)
        val manifest = if (parsed.cssOnly) DeckyThemeManifest.cssOnly(fallbackName?.takeIf { it.isNotBlank() } ?: "Theme") else parsed
        val result = mutex.withLock {
            val id = forceId ?: if (community != null) freeId(themeId(manifest.name)) else themeId(manifest.name)
            val dest = themeDir(id)
            if (dest.exists()) dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            if (!src.renameTo(dest)) {
                src.copyRecursively(dest, overwrite = true)
            }
            ThemeLoader.clearCache()
            manifests[id] = if (manifest.cssOnly) manifest else ThemeLoader.loadManifest(dest)
            val entry = entries.firstOrNull { it.id == id } ?: Entry(id, enabled = enable).also { entries.add(it) }
            entry.storeId = store?.id
            entry.storeVersion = store?.version
            entry.updateAvailable = null
            if (community != null) {
                entry.communityId = community.id
                entry.communityVersion = community.version
                entry.communityInstalled = true
                entry.communityMine = community.mine
            } else {
                entry.communityInstalled = false
            }
            saveLocked()
            recomputeLocked()
            _themes.value.first { it.id == id }
        }
        scope.launch { refreshClassMapIfStale() }
        return result
    }

    private fun freeId(base: String): String {
        fun taken(id: String) = entries.any { it.id.equals(id, ignoreCase = true) } || themeDir(id).exists()
        if (!taken(base)) return base
        var n = 2
        while (taken("$base ($n)")) n++
        return "$base ($n)"
    }

    private fun themeId(name: String): String {
        var id = name.trim().map { if (it.isLetterOrDigit() || it in " ._-()") it else '_' }.joinToString("").trim().take(80)
        if (id.isEmpty()) id = "Theme"
        if (id.startsWith(".") || id.equals(STATE_FILE, ignoreCase = true) || id.equals(BuiltInTheme.ID, ignoreCase = true)) id = "theme_$id"
        return id
    }

    /** Writes the built-in theme's folder when the app's definition differs from it; the user's option values stay in state. */
    private fun ensureBuiltInLocked(savedIds: Set<String>) {
        val dir = themeDir(BuiltInTheme.ID)
        val json = File(dir, "theme.json")
        val current = try {
            if (json.isFile) json.readText() else null
        } catch (e: IOException) {
            null
        }
        if (current != BuiltInTheme.themeJson) {
            dir.mkdirs()
            val tmp = File(dir, "theme.json.tmp")
            tmp.writeText(BuiltInTheme.themeJson)
            if (!tmp.renameTo(json)) {
                json.writeText(BuiltInTheme.themeJson)
                tmp.delete()
            }
            ThemeLoader.clearCache()
        }
        manifests[BuiltInTheme.ID] = ThemeLoader.loadManifest(dir)
        if (BuiltInTheme.ID !in savedIds) {
            val existing = entries.firstOrNull { it.id == BuiltInTheme.ID }
            entries.removeAll { it.id == BuiltInTheme.ID }
            entries.add(0, existing ?: Entry(BuiltInTheme.ID, enabled = false))
            saveLocked()
        }
    }

    private fun newWorkDir(): File {
        val dir = File(File(root, TMP_DIR), System.nanoTime().toString())
        dir.mkdirs()
        return dir
    }

    private fun component(id: String, key: String, type: String): DeckyThemeManifest.Component =
        manifests[id]?.patches?.flatMap { it.components }?.firstOrNull { it.key == key && it.type == type }
            ?: throw IllegalArgumentException("No $type $key in $id")

    private fun copyCapped(input: InputStream, dest: File, max: Long) {
        var total = 0L
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw IOException("File is larger than ${max / (1024 * 1024)} MB")
                out.write(buf, 0, n)
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    } catch (e: Exception) {
        null
    }

    private fun queryName(docUri: Uri): String? = try {
        appContext.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun copyTree(tree: Uri, docId: String, dest: File, count: IntArray, bytes: LongArray, depth: Int) {
        if (depth > 16) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val kids = ArrayList<Triple<String, String, String?>>()
        appContext.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                kids.add(Triple(id, name, c.getString(2)))
            }
        }
        for ((id, name, mime) in kids) {
            val safe = ThemeZip.safeEntryPath(name)?.takeIf { !it.contains('/') } ?: continue
            if (++count[0] > ThemeZip.MAX_ENTRIES) throw IOException("Folder has too many files")
            val target = File(dest, safe)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                target.mkdirs()
                copyTree(tree, id, target, count, bytes, depth + 1)
                continue
            }
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            appContext.contentResolver.openInputStream(docUri)?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes[0] += n
                        if (bytes[0] > ThemeZip.MAX_TOTAL_BYTES) throw IOException("Folder is too large")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun starterImage(): ByteArray {
        val w = 96
        val h = 160
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val t = (x + y).toFloat() / (w + h)
            val r = (0x1f + (0x4f - 0x1f) * t).toInt()
            val g = (0x2a + (0xc3 - 0x2a) * t * 0.6f).toInt()
            val b = (0x44 + (0xf7 - 0x44) * t).toInt()
            px[y * w + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    private fun findByName(name: String): Entry? =
        entries.firstOrNull { e ->
            val m = manifests[e.id] ?: return@firstOrNull false
            m.name.equals(name, ignoreCase = true) || m.displayName?.equals(name, ignoreCase = true) == true || e.id == name
        }

    private fun valuesOf(e: Entry): ThemeValues = ThemeValues(
        choices = HashMap(e.choices),
        colors = HashMap(e.colors),
        images = e.images.mapNotNull { (k, f) -> File(stateDir(e.id), f).takeIf { it.isFile }?.let { k to it.absolutePath } }.toMap(),
    )

    private fun unitOf(e: Entry): ThemeUnit? = manifests[e.id]?.let { ThemeUnit(themeDir(e.id), it, valuesOf(e)) }

    private fun recomputeLocked() {
        val assets = ThemeAssets(root) { folder ->
            (entries.firstOrNull { it.id == folder } ?: findByName(folder))?.let { themeDir(it.id) }
        }
        val out = ArrayList<InstalledTheme>()
        val share = HashMap<String, ShareInfo>()
        var active = ThemeSpec.EMPTY
        for (e in entries) {
            val m = manifests[e.id] ?: continue
            val unit = unitOf(e) ?: continue
            val (ordered, missing) = ThemeLoader.withDependencies(unit) { name -> findByName(name)?.let(::unitOf) }
            var native = false
            val res = try {
                val sheets = ordered.flatMap { ThemeLoader.sheets(it, assets) }
                native = sheets.all { CommunityThemePackage.isNative(it.sheet) }
                ThemeTranslator.translate(sheets, classMap)
            } catch (ex: Exception) {
                Timber.w(ex, "ThemeManager: translating ${e.id} failed")
                TranslationResult(ThemeSpec.EMPTY, 0, 0)
            }
            share[e.id] = ShareInfo(res.spec, CommunityThemePackage.blocker(native, res.spec))
            if (e.enabled) active = active.overlay(res.spec)
            out.add(
                InstalledTheme(
                    id = e.id,
                    name = m.label,
                    author = m.author?.takeIf { it.isNotBlank() },
                    version = m.version,
                    description = m.description,
                    enabled = e.enabled,
                    options = optionsOf(m, unit.values, unit.folder, assets),
                    appliedFields = res.spec.setFields(),
                    totalRules = res.totalRules,
                    appliedRules = res.appliedRules,
                    missingDependencies = missing,
                    builtIn = e.id == BuiltInTheme.ID,
                    updateAvailable = e.updateAvailable,
                    communityId = e.communityId,
                    communityInstalled = e.communityInstalled,
                ),
            )
        }
        shareInfo = share
        _themes.value = out
        _activeSpec.value = active
    }

    private fun optionsOf(m: DeckyThemeManifest, values: ThemeValues, dir: File, assets: ThemeAssets): List<ThemeOption> {
        val out = ArrayList<ThemeOption>()
        for (p in m.patches) {
            val current = p.valueOf(values)
            if (p.type != "none") {
                val kind = when (p.type) {
                    "checkbox" -> ThemeOption.Choice.Kind.CHECKBOX
                    "slider" -> ThemeOption.Choice.Kind.SLIDER
                    else -> ThemeOption.Choice.Kind.DROPDOWN
                }
                out.add(ThemeOption.Choice(p.name, p.name, kind, p.optionNames, current))
            }
            for (c in p.components) {
                val visible = current == c.on
                if (c.type == DeckyThemeManifest.COLOR_PICKER) {
                    val argb = CssValues.parseColor(m.componentValue(c, values))?.toArgb() ?: 0xFFFFFFFF.toInt()
                    out.add(ThemeOption.Color(c.key, c.name, argb, visible))
                } else {
                    val path = values.images[c.key] ?: assets.resolve("/themes_custom/${c.default}", dir, "")
                    out.add(ThemeOption.Image(c.key, c.name, path, visible))
                }
            }
        }
        return out
    }

    /** Loads state and theme folders; returns the theme ids state.json listed. */
    private fun loadLocked(): Set<String> {
        root.mkdirs()
        File(root, TMP_DIR).deleteRecursively()
        File(root, OLD_CLASS_MAP_FILE).delete()
        classMap = try {
            File(root, CLASS_MAP_FILE).takeIf { it.isFile }?.let { SteamClassMap.fromJson(it.readText()) } ?: SteamClassMap.EMPTY
        } catch (e: Exception) {
            Timber.w(e, "ThemeManager: class map cache unreadable")
            SteamClassMap.EMPTY
        }
        val saved = LinkedHashMap<String, Entry>()
        try {
            val f = File(root, STATE_FILE)
            if (f.isFile) {
                val obj = Json.parseToJsonElement(f.readText()) as? JsonObject
                _shareDisplayName.value = ShareDisplayName.read(obj)
                val themesObj = obj?.get("themes") as? JsonObject
                val order = (obj?.get("order") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                for (id in order) {
                    val t = themesObj?.get(id) as? JsonObject ?: JsonObject(emptyMap())
                    saved[id] = Entry(
                        id = id,
                        enabled = (t["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        choices = strMap(t["choices"]),
                        colors = strMap(t["colors"]),
                        images = strMap(t["images"]),
                        storeId = (t["storeId"] as? JsonPrimitive)?.contentOrNull,
                        storeVersion = (t["storeVersion"] as? JsonPrimitive)?.contentOrNull,
                        communityId = (t["communityId"] as? JsonPrimitive)?.contentOrNull,
                        communityVersion = (t["communityVersion"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                        communityInstalled = (t["communityInstalled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        communityMine = (t["communityMine"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "ThemeManager: state unreadable")
        }
        entries.clear()
        manifests.clear()
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
        val byId = dirs.associateBy { it.name }
        fun tryLoad(id: String): Boolean {
            val dir = byId[id] ?: return false
            return try {
                manifests[id] = ThemeLoader.loadManifest(dir)
                true
            } catch (e: Exception) {
                Timber.w(e, "ThemeManager: skipping theme folder $id")
                false
            }
        }
        for ((id, e) in saved) if (tryLoad(id)) entries.add(e)
        for (d in dirs) if (!saved.containsKey(d.name) && tryLoad(d.name)) entries.add(Entry(d.name, enabled = false))
        return saved.keys
    }

    private fun strMap(e: JsonElement?): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        (e as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { out[k] = it } }
        return out
    }

    private fun saveLocked() {
        fun obj(m: Map<String, String>) = JsonObject(m.mapValues { JsonPrimitive(it.value) })
        val state = linkedMapOf<String, JsonElement>(
            "order" to JsonArray(entries.map { JsonPrimitive(it.id) }),
            "themes" to JsonObject(
                entries.associate {
                    it.id to JsonObject(
                        buildMap {
                            put("enabled", JsonPrimitive(it.enabled))
                            put("choices", obj(it.choices))
                            put("colors", obj(it.colors))
                            put("images", obj(it.images))
                            it.storeId?.let { v -> put("storeId", JsonPrimitive(v)) }
                            it.storeVersion?.let { v -> put("storeVersion", JsonPrimitive(v)) }
                            it.communityId?.let { v -> put("communityId", JsonPrimitive(v)) }
                            it.communityVersion?.let { v -> put("communityVersion", JsonPrimitive(v)) }
                            if (it.communityInstalled) put("communityInstalled", JsonPrimitive(true))
                            if (it.communityMine) put("communityMine", JsonPrimitive(true))
                        },
                    )
                },
            ),
        )
        ShareDisplayName.write(state, _shareDisplayName.value)
        val json = JsonObject(state)
        root.mkdirs()
        val tmp = File(root, "$STATE_FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(File(root, STATE_FILE))) {
            File(root, STATE_FILE).writeText(json.toString())
            tmp.delete()
        }
    }

    private suspend fun refreshClassMapIfStale() {
        val f = File(root, CLASS_MAP_FILE)
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < DAY_MS) return
        try {
            val map = ThemeStoreClient.fetchClassMap()
            if (map.names.isEmpty()) return
            val tmp = File(root, "$CLASS_MAP_FILE.tmp")
            tmp.writeText(map.toJson())
            if (!tmp.renameTo(f)) {
                f.writeText(map.toJson())
                tmp.delete()
            }
            mutex.withLock {
                classMap = map
                recomputeLocked()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ThemeManager: class map refresh failed")
        }
    }
}
