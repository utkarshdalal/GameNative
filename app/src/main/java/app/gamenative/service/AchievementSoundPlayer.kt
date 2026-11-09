package app.gamenative.service

import android.content.Context
import android.media.MediaPlayer
import app.gamenative.R
import timber.log.Timber

class AchievementSoundPlayer(context: Context) {
    private val lock = Any()
    private val player: MediaPlayer? = MediaPlayer.create(context.applicationContext, R.raw.achievement_pop)

    /** Plays the unlock sound, restarting it from the beginning if a prior unlock is still playing. */
    fun play() {
        val player = player ?: return
        synchronized(lock) {
            try {
                if (player.isPlaying) {
                    player.seekTo(0)
                } else {
                    player.start()
                }
            } catch (e: Exception) {
                Timber.tag("achievements").w(e, "Failed to play achievement unlock sound")
            }
        }
    }

    fun release() {
        synchronized(lock) {
            player?.release()
        }
    }
}
