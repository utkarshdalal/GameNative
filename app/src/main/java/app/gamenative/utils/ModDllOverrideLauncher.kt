package app.gamenative.utils

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import timber.log.Timber

object ModDllOverrideLauncher {
    fun drives(value: String): Map<String, File> = Container.drivesIterator(value).asSequence()
        .associate { it[0] to File(it[1]) }

    fun apply(container: Container, env: EnvVars, gameLaunch: Boolean) {
        if (!gameLaunch || !container.getExtra(ModDllOverrides.SETTING, "true").toBoolean()) return
        try {
            val prefix = File(container.rootDir, ".wine")
            val executable = ModDllOverrides.resolveExecutable(container.executablePath, drives(container.drives), File(prefix, "drive_c"))
            if (executable == null) {
                Timber.tag("ModDllOverrides").i("Skipped: game executable could not be resolved")
                return
            }
            val detected = ModDllOverrides.detect(executable)
            if (detected.isEmpty()) return
            val result = ModDllOverrides.merge(
                env.get("WINEDLLOVERRIDES"), detected,
                ModDllOverrides.readRegistry(File(prefix, "user.reg"), executable.name),
            )
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
