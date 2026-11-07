package app.gamenative.ui.screen.xserver

import android.content.Context
import android.os.SystemClock
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Mirrors steamhost's one-word stage file onto the boot splash while it says "Starting Steam…".
 * steamhost writes starting / logon / connecting / retry / launching / launched / failed.
 */
object SteamHostStatusWatch {
    private const val TIMEOUT_MS = 10 * 60_000L
    private val generation = AtomicLong(0)

    private fun statusFile(imageFs: ImageFs) =
        File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steamhost_status")

    fun start(context: Context, imageFs: ImageFs, onFailed: ((String) -> Unit)? = null) {
        val file = statusFile(imageFs)
        val watchGeneration = generation.incrementAndGet()
        file.delete()
        thread(name = "steamhost-status") {
            val startedAt = SystemClock.elapsedRealtime()
            var last = ""
            while (generation.get() == watchGeneration) {
                if (SystemClock.elapsedRealtime() - startedAt >= TIMEOUT_MS) {
                    val stage = last.ifBlank { "no status" }
                    onFailed?.invoke("Steam timed out starting the game (steamhost: $stage)")
                    PluviaApp.events.emit(AndroidEvent.GuestProgramTerminated)
                    break
                }

                val status = runCatching { file.readText().trim() }.getOrDefault("")
                if (status != last) {
                    last = status
                    val text = when (status.substringBefore(' ')) {
                        "logon" -> context.getString(R.string.steam_logging_in)
                        "connecting" -> context.getString(R.string.steam_connecting)
                        "retry" -> context.getString(R.string.steam_retrying_login)
                        "launching" -> "Launching game..."
                        else -> null
                    }
                    if (text != null) PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(text))
                    if (status.startsWith("failed")) {
                        onFailed?.invoke("Steam could not start the game (steamhost: $status)")
                        PluviaApp.events.emit(AndroidEvent.GuestProgramTerminated)
                        break
                    }
                    if (status.startsWith("launched")) break
                }
                Thread.sleep(500)
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
    }
}
