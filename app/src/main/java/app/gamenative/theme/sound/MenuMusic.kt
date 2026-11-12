package app.gamenative.theme.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import timber.log.Timber

/**
 * Menu music of the active music pack: the intro once, then the menu track looped. Plays only while music is on,
 * the app's UI is in the foreground and no game runs. All player work runs on one background thread.
 */
internal object MenuMusic {

    private const val FADE_IN_MS = 800L
    private const val FADE_OUT_MS = 400L
    private const val FADE_STEP_MS = 40L
    private const val DUCK_GAIN = 0.25f

    private class Target(val key: String, val dir: File, val manifest: SoundPackManifest)

    private class Session(val key: String) {
        var current: MediaPlayer? = null
        var next: MediaPlayer? = null
        var started = false
    }

    private val attributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var target: Target? = null
    @Volatile private var enabled = false
    @Volatile private var volume = SoundSettings().musicVolume
    @Volatile private var foreground = false
    @Volatile private var gameRunning = false

    private val lock = Any()
    private var handler: Handler? = null

    private var session: Session? = null
    private var playing = false
    private var gain = 0f
    private var duck = 1f
    private var fade: Runnable? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusPaused = false

    private fun handler(): Handler = synchronized(lock) {
        handler ?: Handler(HandlerThread("MenuMusic").apply { start() }.looper).also { handler = it }
    }

    fun configure(context: Context, key: String?, dir: File?, manifest: SoundPackManifest?, enabled: Boolean, volume: Float) {
        appContext = context.applicationContext
        target = if (key != null && dir != null && manifest != null && manifest.music) Target(key, dir, manifest) else null
        this.enabled = enabled
        this.volume = SoundState.clampVolume(volume)
        schedule()
    }

    fun setForeground(foreground: Boolean) {
        if (this.foreground == foreground) return
        this.foreground = foreground
        schedule()
    }

    fun setGameRunning(running: Boolean) {
        if (gameRunning == running) return
        gameRunning = running
        schedule()
    }

    /** Nothing to do, and no thread created, while music is off and nothing was ever played. */
    private fun schedule() {
        val h = synchronized(lock) { handler }
        if (h == null && !(enabled && target != null)) return
        (h ?: handler()).post { reconcile() }
    }

    private fun shouldPlay() = enabled && target != null && foreground && !gameRunning

    private fun reconcile() {
        val t = target
        val s = session
        if (s != null && (t == null || !enabled)) {
            if (playing) fadeTo(0f, FADE_OUT_MS) { releaseSession() } else releaseSession()
            return
        }
        if (s != null && t != null && s.key != t.key) releaseSession()
        if (shouldPlay() && t != null) {
            if (!focusPaused || focusRequest == null) start(t)
        } else if (playing) {
            fadeTo(0f, FADE_OUT_MS) { pauseNow(abandonFocus = true) }
        }
        applyVolume()
    }

    private fun start(t: Target) {
        if (playing && fade == null) return
        var s = session?.takeIf { it.key == t.key }
        if (s == null) releaseSession()
        if (!requestFocus()) return
        if (s == null) {
            s = openSession(t) ?: run {
                abandonFocus()
                return
            }
            session = s
        }
        val player = s.current ?: return
        try {
            if (!player.isPlaying) player.start()
            s.started = true
            playing = true
            Timber.d("MenuMusic: playing ${t.key}")
        } catch (e: Exception) {
            Timber.w(e, "MenuMusic: start failed")
            releaseSession()
            return
        }
        fadeTo(1f, FADE_IN_MS, null)
    }

    private fun openSession(t: Target): Session? {
        val menuFile = try {
            SteamSounds.menuMusic(t.dir, t.manifest)
        } catch (e: Exception) {
            null
        }
        if (menuFile == null) {
            Timber.w("MenuMusic: pack has no menu music")
            return null
        }
        val introFile = try {
            SteamSounds.introMusic(t.dir, t.manifest)
        } catch (e: Exception) {
            null
        }
        val s = Session(t.key)
        val menu = newPlayer(menuFile, loop = true)
        val intro = introFile?.let { newPlayer(it, loop = false) }
        if (intro != null && menu != null) {
            try {
                intro.setNextMediaPlayer(menu)
            } catch (e: Exception) {
                Timber.w(e, "MenuMusic: cannot chain the intro")
            }
            intro.setOnCompletionListener { done ->
                if (session !== s) return@setOnCompletionListener
                val following = s.next
                if (s.current === done && following != null) {
                    s.current = following
                    s.next = null
                    done.release()
                    applyVolume()
                    try {
                        if (playing && !following.isPlaying) following.start()
                    } catch (e: Exception) {
                        Timber.w(e, "MenuMusic: menu track did not start after the intro")
                    }
                }
            }
            s.current = intro
            s.next = menu
        } else {
            s.current = intro ?: menu
            s.next = null
        }
        if (s.current == null) {
            Timber.w("MenuMusic: no playable track in the pack")
            return null
        }
        return s
    }

    private fun newPlayer(file: File, loop: Boolean): MediaPlayer? {
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(attributes)
            p.setDataSource(file.path)
            p.isLooping = loop
            p.setVolume(0f, 0f)
            p.setOnErrorListener { mp, what, extra ->
                Timber.w("MenuMusic: player error $what/$extra on ${file.name}")
                if (session?.current === mp || session?.next === mp) {
                    cancelFade()
                    releaseSession()
                }
                true
            }
            p.prepare()
            p
        } catch (e: Exception) {
            Timber.w(e, "MenuMusic: cannot open ${file.name}")
            p.release()
            null
        }
    }

    private fun pauseNow(abandonFocus: Boolean) {
        val s = session
        try {
            s?.current?.takeIf { it.isPlaying }?.pause()
        } catch (e: Exception) {
            Timber.w(e, "MenuMusic: pause failed")
        }
        playing = false
        gain = 0f
        Timber.d("MenuMusic: paused")
        if (abandonFocus) abandonFocus()
    }

    private fun releaseSession() {
        cancelFade()
        val s = session ?: run {
            playing = false
            abandonFocus()
            return
        }
        session = null
        playing = false
        gain = 0f
        Timber.d("MenuMusic: released ${s.key}")
        for (p in listOfNotNull(s.current, s.next)) {
            try {
                p.release()
            } catch (e: Exception) {
                Timber.w(e, "MenuMusic: release failed")
            }
        }
        abandonFocus()
    }

    private fun fadeTo(to: Float, durationMs: Long, onEnd: (() -> Unit)?) {
        cancelFade()
        val h = handler()
        val from = gain
        val startAt = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (fade !== this) return
                val f = ((SystemClock.uptimeMillis() - startAt).toFloat() / durationMs).coerceIn(0f, 1f)
                gain = from + (to - from) * f
                applyVolume()
                if (f >= 1f) {
                    fade = null
                    onEnd?.invoke()
                } else {
                    h.postDelayed(this, FADE_STEP_MS)
                }
            }
        }
        fade = r
        h.post(r)
    }

    private fun cancelFade() {
        fade?.let { handler?.removeCallbacks(it) }
        fade = null
    }

    private fun applyVolume() {
        val s = session ?: return
        val v = (volume * gain * duck).coerceIn(0f, 1f)
        for (p in listOfNotNull(s.current, s.next)) {
            try {
                p.setVolume(v, v)
            } catch (e: Exception) {
                Timber.w(e, "MenuMusic: volume failed")
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                duck = 1f
                if (focusPaused) {
                    focusPaused = false
                    val t = target
                    if (shouldPlay() && t != null) start(t)
                }
                applyVolume()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duck = DUCK_GAIN
                applyVolume()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusPaused = true
                cancelFade()
                pauseNow(abandonFocus = false)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusPaused = false
                cancelFade()
                pauseNow(abandonFocus = true)
            }
        }
    }

    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, handler())
            .build()
        val granted = try {
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            Timber.w(e, "MenuMusic: audio focus request failed")
            false
        }
        if (granted) {
            focusRequest = req
            duck = 1f
            focusPaused = false
        }
        return granted
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        focusPaused = false
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.abandonAudioFocusRequest(req)
        } catch (e: Exception) {
            Timber.w(e, "MenuMusic: abandon audio focus failed")
        }
    }
}
