package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File

interface PreInstallStep {
    val marker: Marker

    /**
     * Whether [buildCommand] returns a *shell* command line.
     *
     * True (the default) means the command runs as `winhandler.exe cmd /c "<command> & taskkill … &
     * wineserver -k"`, so it may chain with `&` and redirect — and pays for a `cmd.exe` console on
     * the virtual desktop while it does. False means the command is the guest executable itself, run
     * as-is: no `cmd`, no chain, no console of ours. A batch file still needs an interpreter once
     * Wine gets to it, which is the one thing this cannot take away.
     */
    val viaShell: Boolean get() = true

    fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean

    fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String?
}
