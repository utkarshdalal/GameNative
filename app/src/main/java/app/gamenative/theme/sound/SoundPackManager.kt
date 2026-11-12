package app.gamenative.theme.sound

import android.content.Context
import android.net.Uri
import app.gamenative.theme.StoreTheme
import app.gamenative.theme.ThemeStoreClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
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
import timber.log.Timber

/** Installed Audio Loader packs under `filesDir/soundpacks`, the sound settings, and the players that follow them. */
object SoundPackManager {

    private const val STATE_FILE = "state.json"
    private const val TMP_DIR = ".tmp"
    private const val STORE_FILTER = "AUDIO"

    private val started = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var appContext: Context

    private val _packs = MutableStateFlow<List<InstalledSoundPack>>(emptyList())
    val packs: StateFlow<List<InstalledSoundPack>> = _packs.asStateFlow()

    private val _settings = MutableStateFlow(SoundSettings())
    val settings: StateFlow<SoundSettings> = _settings.asStateFlow()

    private class Pack(val manifest: SoundPackManifest, val soundCount: Int, val revision: Long)

    private val installed = HashMap<String, Pack>()
    private val origins = HashMap<String, SoundPackOrigin>()
    private var current = SoundSettings()

    private val root: File get() = File(appContext.filesDir, "soundpacks")
    private fun packDir(id: String) = File(root, id)

    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        scope.launch {
            try {
                // Without the folder nothing was ever installed or set; the defaults stand and nothing is created.
                if (root.isDirectory) {
                    mutex.withLock {
                        loadLocked()
                        publishLocked()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "SoundPackManager: load failed")
            } finally {
                ready.complete(Unit)
            }
        }
    }

    private suspend fun <T> op(name: String, block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        if (!started.get()) return@withContext Result.failure(IllegalStateException("SoundPackManager.init was not called"))
        try {
            ready.await()
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "SoundPackManager: $name failed")
            Result.failure(e)
        }
    }

    private suspend fun editSettings(name: String, block: (SoundSettings) -> SoundSettings) {
        op(name) {
            mutex.withLock {
                val next = block(current)
                if (next != current) {
                    current = next
                    saveLocked()
                    publishLocked()
                }
            }
        }
    }

    suspend fun importZip(uri: Uri): Result<InstalledSoundPack> = op("importZip") {
        val zip = File(appContext.cacheDir, "soundpack-import-${System.nanoTime()}.zip")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { copyCapped(it, zip, ThemeStoreClient.MAX_ZIP_BYTES) }
                ?: throw IOException("Cannot read the selected file")
            installZip(zip, SoundPackOrigin())
        } finally {
            zip.delete()
        }
    }

    suspend fun delete(id: String) {
        op("delete") {
            mutex.withLock {
                if (installed.remove(id) == null && !packDir(id).exists()) return@withLock
                origins.remove(id)
                current = current.copy(
                    activePackId = current.activePackId?.takeIf { it != id },
                    activeMusicPackId = current.activeMusicPackId?.takeIf { it != id },
                )
                saveLocked()
                publishLocked()
                packDir(id).deleteRecursively()
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) = editSettings("setEnabled") { it.copy(enabled = enabled) }

    suspend fun setVolume(volume: Float) = editSettings("setVolume") { it.copy(volume = SoundState.clampVolume(volume)) }

    suspend fun setActivePack(id: String?) = editSettings("setActivePack") { s ->
        if (id == null || installed[id]?.manifest?.music == false) s.copy(activePackId = id) else s
    }

    suspend fun setMusicEnabled(enabled: Boolean) = editSettings("setMusicEnabled") { it.copy(musicEnabled = enabled) }

    suspend fun setMusicVolume(volume: Float) = editSettings("setMusicVolume") { it.copy(musicVolume = SoundState.clampVolume(volume)) }

    suspend fun setActiveMusicPack(id: String?) = editSettings("setActiveMusicPack") { s ->
        if (id == null || installed[id]?.manifest?.music == true) s.copy(activeMusicPackId = id) else s
    }

    suspend fun storeList(query: String, page: Int): Result<List<StoreTheme>> = op("storeList") {
        ThemeStoreClient.list(query, page, STORE_FILTER)
    }

    suspend fun installFromStore(pack: StoreTheme): Result<InstalledSoundPack> = op("installFromStore") {
        val blob = pack.downloadBlobId ?: throw IOException("Pack ${pack.name} has no download")
        val zip = File(appContext.cacheDir, "soundpack-store-${System.nanoTime()}.zip")
        try {
            ThemeStoreClient.downloadBlob(blob, zip)
            installZip(zip, SoundPackOrigin(storeId = pack.id, storeVersion = pack.version))
        } finally {
            zip.delete()
        }
    }

    /** Menu music runs only while the app's own UI is in the foreground. */
    fun setUiForeground(foreground: Boolean) = MenuMusic.setForeground(foreground)

    /** While a game is running nothing plays: no UI sounds, no music. */
    fun setGameRunning(running: Boolean) {
        UiSounds.setSuspended(running)
        MenuMusic.setGameRunning(running)
    }

    fun preview(sound: UiSound) = UiSounds.preview(sound)

    private suspend fun installZip(zip: File, origin: SoundPackOrigin): InstalledSoundPack {
        val work = File(File(root, TMP_DIR), System.nanoTime().toString()).apply { mkdirs() }
        try {
            val src = SoundPackFiles.unpack(zip, work)
            val manifest = SoundPackFiles.validate(src)
            return mutex.withLock {
                val id = SoundPackFiles.packId(manifest.name)
                val dest = packDir(id)
                if (dest.exists()) dest.deleteRecursively()
                dest.parentFile?.mkdirs()
                if (!src.renameTo(dest)) src.copyRecursively(dest, overwrite = true)
                val pack = Pack(manifest, SteamSounds.soundCount(dest, manifest), System.nanoTime())
                installed[id] = pack
                if (origin.storeId != null) origins[id] = origin else origins.remove(id)
                if (manifest.music && current.activeMusicPackId == null) current = current.copy(activeMusicPackId = id)
                if (!manifest.music && current.activePackId == null) current = current.copy(activePackId = id)
                saveLocked()
                publishLocked()
                toInstalled(id, pack)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun toInstalled(id: String, p: Pack) = InstalledSoundPack(
        id = id,
        name = p.manifest.name,
        author = p.manifest.author,
        version = p.manifest.version,
        description = p.manifest.description,
        isMusic = p.manifest.music,
        soundCount = p.soundCount,
    )

    private fun publishLocked() {
        _packs.value = installed.entries
            .map { (id, p) -> toInstalled(id, p) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        _settings.value = current
        val sound = current.activePackId?.let { id -> installed[id]?.let { id to it } }
        UiSounds.configure(
            key = sound?.let { (id, p) -> "$id@${p.revision}" },
            dir = sound?.let { packDir(it.first) },
            manifest = sound?.second?.manifest,
            enabled = current.enabled,
            volume = current.volume,
        )
        val music = current.activeMusicPackId?.let { id -> installed[id]?.let { id to it } }
        MenuMusic.configure(
            context = appContext,
            key = music?.let { (id, p) -> "$id@${p.revision}" },
            dir = music?.let { packDir(it.first) },
            manifest = music?.second?.manifest,
            enabled = current.musicEnabled,
            volume = current.musicVolume,
        )
    }

    private fun loadLocked() {
        root.mkdirs()
        File(root, TMP_DIR).deleteRecursively()
        val state = try {
            File(root, STATE_FILE).takeIf { it.isFile }?.readText()
        } catch (e: IOException) {
            Timber.w(e, "SoundPackManager: state unreadable")
            null
        }.let(SoundState::parse)
        installed.clear()
        origins.clear()
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()
        for (dir in dirs) {
            try {
                val manifest = SoundPackManifest.load(dir)
                installed[dir.name] = Pack(manifest, SteamSounds.soundCount(dir, manifest), dir.lastModified())
            } catch (e: Exception) {
                Timber.w(e, "SoundPackManager: skipping pack folder ${dir.name}")
            }
        }
        state.origins.forEach { (id, o) -> if (id in installed) origins[id] = o }
        val s = state.settings
        current = s.copy(
            activePackId = s.activePackId?.takeIf { installed[it]?.manifest?.music == false },
            activeMusicPackId = s.activeMusicPackId?.takeIf { installed[it]?.manifest?.music == true },
        )
    }

    private fun saveLocked() {
        val text = SoundState(current, origins.toMap()).toJson()
        root.mkdirs()
        val f = File(root, STATE_FILE)
        val tmp = File(root, "$STATE_FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

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
}
