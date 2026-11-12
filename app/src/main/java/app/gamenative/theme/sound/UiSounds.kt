package app.gamenative.theme.sound

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/** UI sounds of the active sound pack, played through one [SoundPool] that exists only while it is needed. */
object UiSounds {

    private const val MAX_STREAMS = 4
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val PREVIEW_KEEP_MS = 20_000L
    private const val RELEASE_DELAY_MS = 1_500L

    private class Bank(val key: String, val pool: SoundPool) {
        val ids = HashMap<UiSound, IntArray>()
        val names = HashMap<Int, String>()
        val loaded = HashSet<Int>()
        var pending: UiSound? = null
    }

    private class Target(val key: String, val dir: File, val manifest: SoundPackManifest)

    @Volatile private var target: Target? = null
    @Volatile private var enabled = false
    @Volatile private var volume = SoundSettings().volume
    @Volatile private var suspended = false
    @Volatile private var bank: Bank? = null
    @Volatile private var previewUntil = 0L
    private val lastNavigate = AtomicLong(0L)

    private val lock = Any()
    private var handler: Handler? = null

    private fun handler(): Handler = synchronized(lock) {
        handler ?: Handler(HandlerThread("UiSounds").apply { start() }.looper).also { handler = it }
    }

    /** Plays the active pack's sound for [sound]. Safe from any thread, returns at once, no-op when sounds are off, no pack is active, the pack has no file for it, or sounds are suspended. */
    fun play(sound: UiSound) {
        if (!enabled || suspended) return
        val b = bank ?: return
        if (sound == UiSound.NAVIGATE && !claimNavigate()) return
        handler?.post { playNow(b, sound) }
    }

    internal fun preview(sound: UiSound) {
        if (suspended) return
        val t = target ?: return
        previewUntil = SystemClock.uptimeMillis() + PREVIEW_KEEP_MS
        val h = handler()
        h.post {
            val b = ensureBank(t) ?: return@post
            if (!playNow(b, sound)) b.pending = sound
        }
        h.postDelayed({ reconcile() }, PREVIEW_KEEP_MS + 100)
    }

    /** Called by [SoundPackManager] whenever the active pack or sound settings change. [key] changes when the pack's files do. */
    internal fun configure(key: String?, dir: File?, manifest: SoundPackManifest?, enabled: Boolean, volume: Float) {
        target = if (key != null && dir != null && manifest != null && !manifest.music) Target(key, dir, manifest) else null
        this.enabled = enabled
        this.volume = SoundState.clampVolume(volume)
        schedule()
    }

    internal fun setSuspended(suspended: Boolean) {
        if (this.suspended == suspended) return
        this.suspended = suspended
        schedule()
    }

    private fun claimNavigate(): Boolean {
        val now = SystemClock.uptimeMillis()
        while (true) {
            val last = lastNavigate.get()
            if (!SteamSounds.navigateAllowed(last, now)) return false
            if (lastNavigate.compareAndSet(last, now)) return true
        }
    }

    private fun wanted(): Target? {
        val t = target ?: return null
        if (suspended) return null
        return if (enabled || SystemClock.uptimeMillis() < previewUntil) t else null
    }

    /** Releasing waits [RELEASE_DELAY_MS] so the last cue (toggle off, launch) is not cut off. */
    private fun schedule() {
        val w = wanted()
        if (w?.key == bank?.key) return
        val h = handler()
        if (w == null) h.postDelayed({ reconcile() }, RELEASE_DELAY_MS) else h.post { reconcile() }
    }

    private fun reconcile() {
        val t = wanted()
        if (t == null) {
            releaseBank()
        } else {
            ensureBank(t)
        }
    }

    private fun ensureBank(t: Target): Bank? {
        bank?.let { if (it.key == t.key) return it }
        releaseBank()
        val pool = try {
            SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
        } catch (e: Exception) {
            Timber.w(e, "UiSounds: SoundPool unavailable")
            return null
        }
        val b = Bank(t.key, pool)
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (bank !== b) return@setOnLoadCompleteListener
            if (status == 0) {
                b.loaded.add(sampleId)
                b.pending?.let { p -> if (b.ids[p]?.contains(sampleId) == true && playNow(b, p)) b.pending = null }
            } else {
                Timber.w("UiSounds: SoundPool could not load ${b.names[sampleId]} (status $status), skipped")
            }
        }
        bank = b
        Timber.d("UiSounds: SoundPool created for ${t.key}")
        val byPath = HashMap<String, Int>()
        for (sound in UiSound.entries) {
            val files = try {
                SteamSounds.candidates(t.dir, t.manifest, sound)
            } catch (e: Exception) {
                Timber.w(e, "UiSounds: cannot resolve $sound")
                emptyList()
            }
            val soundIds = files.mapNotNull { f ->
                byPath[f.path] ?: run {
                    if (f.length() > MAX_FILE_BYTES) {
                        Timber.w("UiSounds: skipping ${f.name}, larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB")
                        return@run null
                    }
                    try {
                        pool.load(f.path, 1).takeIf { it > 0 }?.also {
                            byPath[f.path] = it
                            b.names[it] = f.name
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "UiSounds: cannot load ${f.name}")
                        null
                    }
                }
            }
            if (soundIds.isNotEmpty()) b.ids[sound] = soundIds.toIntArray()
        }
        return b
    }

    private fun playNow(b: Bank, sound: UiSound): Boolean {
        if (bank !== b) return false
        val ready = b.ids[sound]?.filter { it in b.loaded } ?: return false
        val id = SteamSounds.pick(ready) ?: return false
        val v = volume
        return try {
            val stream = b.pool.play(id, v, v, if (sound == UiSound.NAVIGATE) 0 else 1, 0, 1f)
            Timber.d("UiSounds: play $sound ${b.names[id]} vol $v stream $stream")
            stream != 0
        } catch (e: Exception) {
            Timber.w(e, "UiSounds: play failed")
            false
        }
    }

    private fun releaseBank() {
        val b = bank ?: return
        bank = null
        Timber.d("UiSounds: SoundPool released for ${b.key}")
        try {
            b.pool.release()
        } catch (e: Exception) {
            Timber.w(e, "UiSounds: release failed")
        }
    }
}
