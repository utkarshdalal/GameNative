package app.gamenative.utils

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import timber.log.Timber

object ModDllOverrideLauncher {
    // GUI/headless Steam receives the launch environment before it starts the selected game.
    // Bionic Steam launches the game directly and takes precedence over the other Steam modes.
    fun supportsLaunch(launchRealSteam: Boolean, launchBionicSteam: Boolean): Boolean = !launchRealSteam || launchBionicSteam

    fun drives(value: String): Map<String, File> = Container.drivesIterator(value).asSequence()
        .associate { it[0] to File(it[1]) }

    fun apply(container: Container, env: EnvVars, gameLaunch: Boolean) {
        if (!gameLaunch || !container.getExtra(ModDllOverrides.SETTING, "true").toBoolean()) return
        if (!supportsLaunch(container.isLaunchRealSteam, container.isLaunchBionicSteam)) return
        try {
            val prefix = File(container.rootDir, ".wine")
            val inspection = ModDllOverrides.inspect(container.executablePath, drives(container.drives), prefix)
            val executable = inspection.executable
            if (executable == null) {
                Timber.tag("ModDllOverrides").i("Skipped: game executable could not be resolved")
                return
            }
            if (inspection.detected.isEmpty()) return
            val result = inspection.merge(env.get("WINEDLLOVERRIDES"))
            if (result.added.isNotEmpty()) env.put("WINEDLLOVERRIDES", result.value)
            Timber.tag("ModDllOverrides").i(
                "Game=%s; automatic=%s; existing settings preserved=%s",
                executable.path, result.added.joinToString(), result.preserved.joinToString(),
            )
        } catch (e: Exception) {
            // A missing/unreadable directory or registry must not prevent a game from starting.
            Timber.tag("ModDllOverrides").w(e, "Could not detect mod DLL overrides")
        }
    }
}
