package app.gamenative.ui.screen.xserver

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Mirrors steamhost's one-word stage file onto the boot splash while it says "Starting Steam…".
 * steamhost writes starting / logon / connecting / retry / launching / launched / failed.
 */
object SteamHostStatusWatch {
    private val active = AtomicBoolean(false)

    private fun statusFile(imageFs: ImageFs) =
        File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steamhost_status")

    fun start(context: Context, imageFs: ImageFs) {
        val file = statusFile(imageFs)
        file.delete()
        if (!active.compareAndSet(false, true)) return
        thread(name = "steamhost-status") {
            val startedAt = System.currentTimeMillis()
            var last = ""
            try {
                while (active.get() && System.currentTimeMillis() - startedAt < 10 * 60_000L) {
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
                        if (status.startsWith("launched") || status.startsWith("failed")) break
                    }
                    Thread.sleep(500)
                }
            } finally {
                active.set(false)
            }
        }
    }

    fun stop() {
        active.set(false)
    }
}
