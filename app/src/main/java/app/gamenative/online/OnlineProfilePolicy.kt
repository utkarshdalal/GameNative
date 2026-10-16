package app.gamenative.online

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars

/**
 * Hardening rules for Online install / launch profiles.
 * Prefer Proton 10 + Headless Steam; keep TLS/DNS-friendly env for EOS and Steam networking.
 *
 * Note: BionicProgramLauncherComponent already injects SSL_CERT_* / WINE_ADDITIONAL_CERTS_DIR
 * at process start. Here we only persist container-level nudges that survive gamefixes.
 */
object OnlineProfilePolicy {
    const val PREFERRED_PROTON_PREFIX = "proton-10"
    const val EXTRA_POLICY_VERSION = "onlineProfilePolicyVersion"
    const val POLICY_VERSION = "4"

    /**
     * Env keys that help Steam/EOS TLS and hostname edge cases on Android Wine.
     * SSL cert bundle paths are set by the Bionic launcher at runtime (not duplicated here).
     */
    private val ONLINE_ENV = mapOf(
        // Prefer a stable hostname for SDKs that reject empty/invalid gethostname results.
        "HOSTNAME" to "gamenative-online",
        // Nudges some stacks away from offline-only Steam paths.
        "SteamOfflineMode" to "0",
        // Helps some OpenSSL/EOS clients that mis-detect CPU capabilities under box64.
        "OPENSSL_ia32cap" to "~0x200000200000000",
    )

    fun applySteamClientFlags(container: Container) {
        container.isLaunchRealSteam = true
        container.isLaunchBionicSteam = false
        container.steamType = Container.STEAM_TYPE_HEADLESS
        container.isSteamOfflineMode = false
    }

    fun mergeOnlineEnv(container: Container) {
        val env = EnvVars(container.envVars)
        ONLINE_ENV.forEach { (key, value) ->
            if (!env.has(key)) {
                env.put(key, value)
            }
        }
        container.envVars = env.toString()
    }

    /** Strip Online-only env nudges when switching back to Offline install. */
    fun clearOnlineOnlyEnv(container: Container) {
        val env = EnvVars(container.envVars)
        var changed = false
        ONLINE_ENV.keys.forEach { key ->
            if (env.has(key) && env.get(key) == ONLINE_ENV[key]) {
                env.remove(key)
                changed = true
            }
        }
        if (changed) {
            container.envVars = env.toString()
        }
    }

    /**
     * Prefer a Proton 10.* wineVersion when the container is still on a non-10 build.
     * Does not force a downgrade from proton-11+ if already set — only upgrades empty/legacy
     * identifiers that clearly are not proton-10+.
     */
    fun preferProton10IfNeeded(container: Container) {
        val current = container.wineVersion.orEmpty()
        if (current.contains(PREFERRED_PROTON_PREFIX, ignoreCase = true)) return
        if (current.contains("proton-11", ignoreCase = true) ||
            current.contains("proton-12", ignoreCase = true)
        ) {
            // Leave newer Proton alone; online path may still work, but we don't force-swap.
            return
        }
        // Default fork Proton 10 identifier used by ContainerUtils defaults.
        if (current.isBlank() ||
            current.contains("proton-9", ignoreCase = true) ||
            current.contains("proton-8", ignoreCase = true) ||
            !current.contains("proton-", ignoreCase = true)
        ) {
            container.wineVersion = "proton-10.0-arm64ec-2"
        }
    }

    fun markPolicyApplied(container: Container) {
        container.putExtra(EXTRA_POLICY_VERSION, POLICY_VERSION)
    }

    fun preflightWarnings(container: Container, status: OnlineStatus): List<String> {
        val warnings = mutableListOf<String>()
        if (status == OnlineStatus.ONLINE_BLOCKED) {
            warnings += "Online is blocked for this title (anti-cheat / unsupported)."
        }
        if (status == OnlineStatus.ONLINE_FLAKY) {
            warnings += "Online is marked flaky — multiplayer may fail."
        }
        if (container.isSteamOfflineMode) {
            warnings += "steamOfflineMode is on; Online profile should disable it."
        }
        if (!container.isLaunchRealSteam || !container.isLaunchHeadlessSteam) {
            warnings += "Headless Steam is not fully enabled for Online profile."
        }
        val wine = container.wineVersion.orEmpty()
        if (wine.contains("proton-9", ignoreCase = true) ||
            wine.contains("proton-8", ignoreCase = true)
        ) {
            warnings += "Proton 8/9 detected; Online path is more reliable on Proton 10."
        }
        val env = EnvVars(container.envVars)
        if (env.has("SteamOfflineMode") && env.get("SteamOfflineMode") == "1") {
            warnings += "Env SteamOfflineMode=1 may force offline Steam networking."
        }
        return warnings
    }
}
