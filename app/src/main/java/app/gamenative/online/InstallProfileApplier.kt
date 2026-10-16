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
                OnlineProfilePolicy.applySteamClientFlags(container)
                OnlineProfilePolicy.mergeOnlineEnv(container)
                OnlineProfilePolicy.preferProton10IfNeeded(container)
                OnlineProfilePolicy.markPolicyApplied(container)
            }
            InstallProfile.OFFLINE -> {
                container.isLaunchRealSteam = false
                container.isLaunchBionicSteam = false
                // Leave steamOfflineMode alone — DRM / tickets still need a working Steam path for many SP titles.
                OnlineProfilePolicy.clearOnlineOnlyEnv(container)
            }
        }
        container.putExtra(EXTRA_KEY, profile.storageKey)
        container.saveData()
        InstallProfileStore.save(context, container.id, profile)
        Timber.i(
            "Applied install profile=%s to container=%s (realSteam=%s bionic=%s type=%s wine=%s)",
            profile.storageKey,
            container.id,
            container.isLaunchRealSteam,
            container.isLaunchBionicSteam,
            container.steamType,
            container.wineVersion,
        )
    }

    /** Apply a previously saved choice if present (used when creating containers after install). */
    fun applyPendingIfAny(context: Context, container: Container) {
        val pending = InstallProfileStore.get(context, container.id) ?: return
        apply(context, container, pending)
    }

    /**
     * Re-assert Online hardening on every launch so gamefixes/known-configs cannot silently
     * disable Headless Steam or turn steamOfflineMode back on.
     */
    fun reassertOnLaunchIfNeeded(context: Context, container: Container) {
        val profile = readFromContainer(container)
            ?: InstallProfileStore.get(context, container.id)
            ?: return
        if (profile != InstallProfile.ONLINE) return
        // Reassert Steam client flags / online env only. Do NOT rewrite wineVersion here —
        // preferProton10IfNeeded at install is enough; forcing Proton 10 every launch breaks
        // titles pinned to a known-config Proton build (black screen / hang).
        OnlineProfilePolicy.applySteamClientFlags(container)
        OnlineProfilePolicy.mergeOnlineEnv(container)
        OnlineProfilePolicy.markPolicyApplied(container)
        container.putExtra(EXTRA_KEY, profile.storageKey)
        container.saveData()
        Timber.i("Reasserted Online profile policy on launch for %s", container.id)
    }

    fun containerIdForSteam(steamAppId: Int): String = "STEAM_$steamAppId"

    fun readFromContainer(container: Container): InstallProfile? {
        return InstallProfile.fromStorageKey(container.getExtra(EXTRA_KEY, ""))
    }
}
