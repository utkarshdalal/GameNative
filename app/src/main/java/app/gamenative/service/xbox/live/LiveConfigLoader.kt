package app.gamenative.service.xbox.live

import app.gamenative.service.xbox.XboxHelperConfig
import java.io.File

/** Reads [LiveConfig] from the installed helper archive's xboxhost.properties. */
fun LiveConfig.Companion.load(filesDir: File): LiveConfig = LiveConfig.from { XboxHelperConfig.optional(filesDir, it) }
