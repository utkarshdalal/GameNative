package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import java.io.File
import java.nio.charset.StandardCharsets
import timber.log.Timber

private fun updateIniValue(content: String, key: String, value: String): String {
    val regex = Regex("(?im)^(${Regex.escape(key)}\\s*=\\s*).*$")
    return if (regex.containsMatchIn(content)) {
        content.replace(regex, "$1$value")
    } else {
        val suffix = if (content.endsWith("\n") || content.isEmpty()) "" else System.lineSeparator()
        content + suffix + "$key=$value" + System.lineSeparator()
    }
}

class IniFileFix(
    private val relativePath: String,
    private val defaultValues: Map<String, String>,
    private val migrationKey: String? = null,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        if (migrationKey != null && container.getExtra(migrationKey) == "1") {
            return false
        }

        val iniFile = File(installPath, relativePath)
        if (!iniFile.isFile) {
            return false
        }

        return runCatching {
            val original = iniFile.readText(StandardCharsets.UTF_8)
            var updated = original
            for ((key, value) in defaultValues) {
                updated = updateIniValue(updated, key, value)
            }

            val fileChanged = updated != original

            if (fileChanged) {
                iniFile.writeText(updated, StandardCharsets.UTF_8)
            }

            var migrationMarked = false
            if (migrationKey != null) {
                container.putExtra(migrationKey, "1")
                container.saveData()
                migrationMarked = true
            }

            if (fileChanged) {
                Timber.tag("GameFixes").i("Updated $relativePath for game $gameId")
            } else if (migrationMarked) {
                Timber.tag("GameFixes").d("Marked $relativePath migration as complete for game $gameId")
            }

            fileChanged || migrationMarked
        }.getOrElse { error ->
            Timber.tag("GameFixes").w(error, "Failed to update $relativePath for game $gameId")
            false
        }
    }
}

class KeyedIniFileFix(
    override val gameSource: GameSource,
    override val gameId: String,
    relativePath: String,
    defaultValues: Map<String, String>,
    migrationKey: String? = null,
) : KeyedGameFix, GameFix by IniFileFix(relativePath, defaultValues, migrationKey)
