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
     * True (the default) means the command is run as `cmd /c "<command> & taskkill … & wineserver -k"`, so
     * it may chain with `&` and redirect — but `cmd.exe` is a console application, and under a virtual
     * desktop that console is a black window on screen for as long as the step runs. A step whose work is
     * a single windowed program says false instead: it is launched directly, with no console, and the
     * session ends when that program exits.
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
