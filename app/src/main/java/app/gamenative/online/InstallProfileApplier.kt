package app.gamenative.online

import android.content.Context
import com.winlator.container.Container
import timber.log.Timber

/**
 * Applies an Online/Offline install profile onto a container.
 * User choice wins over known-config Steam client defaults for this fork feature.
 */
object InstallProfileApplier {
    const val EXTRA_KEY = "installProfile"

    fun apply(context: Context, container: Container, profile: InstallProfile) {
        when (profile) {
            InstallProfile.ONLINE -> {
                // Headless Steam is the upstream online path that fixed multiplayer for several titles.
                container.isLaunchRealSteam = true
                container.isLaunchBionicSteam = false
                container.steamType = Container.STEAM_TYPE_HEADLESS
                container.isSteamOfflineMode = false
            }
            InstallProfile.OFFLINE -> {
                container.isLaunchRealSteam = false
                container.isLaunchBionicSteam = false
                // Leave steamOfflineMode alone — DRM / tickets still need a working Steam path for many SP titles.
            }
        }
        container.putExtra(EXTRA_KEY, profile.storageKey)
        container.saveData()
        InstallProfileStore.save(context, container.id, profile)
        Timber.i(
            "Applied install profile=%s to container=%s (realSteam=%s bionic=%s type=%s)",
            profile.storageKey,
            container.id,
            container.isLaunchRealSteam,
            container.isLaunchBionicSteam,
            container.steamType,
        )
    }

    /** Apply a previously saved choice if present (used when creating containers after install). */
    fun applyPendingIfAny(context: Context, container: Container) {
        val pending = InstallProfileStore.get(context, container.id) ?: return
        apply(context, container, pending)
    }

    fun containerIdForSteam(steamAppId: Int): String = "STEAM_$steamAppId"

    fun readFromContainer(container: Container): InstallProfile? {
        return InstallProfile.fromStorageKey(container.getExtra(EXTRA_KEY, ""))
    }
}
