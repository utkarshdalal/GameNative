package app.gamenative.discord

import app.gamenative.BuildConfig

/**
 * Build-time configuration for Discord Rich Presence.
 *
 * The application ID is public (it is broadcast with every presence update) but is injected at
 * build time so forks can point at their own Discord application.
 * See app/libs/README-discord-social-sdk.md.
 */
internal object DiscordConfig {

    /** Discord application ID, or 0 when this build wasn't configured with one. */
    val applicationId: Long = BuildConfig.DISCORD_APPLICATION_ID.trim().toLongOrNull() ?: 0L

    /** True when the Discord Social SDK AAR was bundled at build time. */
    val sdkBundled: Boolean = BuildConfig.DISCORD_SDK_BUNDLED

    // Art asset uploaded under the Discord application, used only for games with no cover art of
    // their own. Presence still publishes if it was never uploaded; the slot renders empty.
    const val FALLBACK_IMAGE_ASSET_KEY = "gamenative"

    const val FALLBACK_IMAGE_TEXT = "Played on GameNative"
}
