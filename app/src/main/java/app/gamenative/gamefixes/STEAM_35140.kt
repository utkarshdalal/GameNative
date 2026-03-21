package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.MarkerUtils
import com.winlator.xenvironment.ImageFs
import timber.log.Timber
import java.io.File

val STEAM_Fix_35140: KeyedGameFix = object : KeyedGameFix {
    override val gameSource: GameSource = GameSource.STEAM
    override val gameId: String = "35140"

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
    ): Boolean {
        val container = ContainerUtils.getOrCreateContainer(context, "STEAM_$gameId")
        val userDocumentsConfigPath =
            ".wine/drive_c/users/${ImageFs.USER}/Documents/Square Enix/Batman Arkham Asylum GOTY/BmGame/Config"
        val staleFiles = listOf(
            File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam/ColdClientLoader.ini"),
            File(container.rootDir, "$userDocumentsConfigPath/BmEngine.ini"),
            File(container.rootDir, "$userDocumentsConfigPath/BmGame.ini"),
            File(container.rootDir, ".cache/ShippingPC-BmGame.dxvk-cache"),
        )

        var changed = false

        if (MarkerUtils.hasMarker(installPath, Marker.STEAM_COLDCLIENT_USED) &&
            MarkerUtils.removeMarker(installPath, Marker.STEAM_COLDCLIENT_USED)
        ) {
            changed = true
            Timber.tag("GameFixes").i("Cleared stale coldclient marker for Batman Arkham Asylum GOTY")
        }

        staleFiles.forEach { file ->
            if (file.exists() && file.delete()) {
                changed = true
                Timber.tag("GameFixes").i("Deleted stale Batman launch state: ${file.absolutePath}")
            } else if (file.exists()) {
                Timber.tag("GameFixes").w("Failed to delete stale Batman launch state: ${file.absolutePath}")
            }
        }

        return changed
    }
}
