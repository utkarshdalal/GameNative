# Discord Rich Presence: enabling the official Discord Social SDK

GameNative can publish the running game as the user's Discord activity ("Playing GTA V", with an
elapsed-time counter). It does this through Discord's official Discord Social SDK, using the SDK's
unauthenticated RPC path, which since Social SDK 1.10 publishes presence through the Discord
Android app when that app is installed and signed in.

No Discord Desktop, no PC, no Gateway connection, no self-bot, no user token: GameNative never
authenticates as the user and never reads anything back from their account.

Reference: <https://docs.discord.com/developers/discord-social-sdk/development-guides/setting-rich-presence>
and <https://docs.discord.com/developers/discord-social-sdk/core-concepts/mobile>.

## Why the SDK is not checked in

Discord distributes the Social SDK as a per-application download from the Developer Portal under a
licence that does not allow redistribution. It is not published to Maven Central or Google's Maven,
so it cannot be a normal Gradle coordinate.

`app/build.gradle.kts` therefore probes for the file. Without it, everything below is skipped and
GameNative builds and runs exactly as before: the Discord settings tile reports "Not available in
this build of GameNative" and no Discord code ever executes.

## Enabling it

1. Create or choose a Discord application at <https://discord.com/developers/applications> and note
   its Application ID (Settings > General Information). This is a public identifier, not a secret,
   and is broadcast with every presence update. There is no client secret or bot token involved.

2. Set the Rich Presence name. Discord shows the application's name as the activity title unless
   the activity carries its own name; GameNative always sets the name explicitly to the game's
   name, so the application name only matters as a fallback.

3. Optionally upload one art asset, under Developer Portal > your app > Rich Presence > Art Assets.
   Upload a GameNative logo keyed `gamenative` (`DiscordConfig.FALLBACK_IMAGE_ASSET_KEY`).

   Per-game cover art does not go here. Discord's image fields accept an external image URL as well
   as an uploaded key, and an application is capped at 300 uploaded assets, so GameNative sends
   each game's art straight from the store CDN it already reads (Steam library capsule, GOG icon,
   Epic square art, Amazon art). The `gamenative` asset is only used for custom games, whose art is
   local files Discord cannot fetch. Presence publishes fine if it was never uploaded; that slot
   just renders empty.

4. Download the SDK from Developer Portal > your app > Discord Social SDK > Download. Take the
   Android/mobile archive for 1.10 or newer; earlier versions have no Android support.

5. Drop the AAR in place, exactly at:

   ```
   app/libs/discord_partner_sdk.aar
   ```

   (`app/libs/*.aar` is gitignored, do not commit it.)

6. Build with the application ID, the same way this project passes its other build parameters:

   ```bash
   ./gradlew assembleModernDebug -PDISCORD_APPLICATION_ID=000000000000000000
   # or
   DISCORD_APPLICATION_ID=000000000000000000 ./gradlew assembleModernDebug
   ```

Gradle then also compiles `app/src/main/cpp/discordrpc` (the JNI bridge from Kotlin to the SDK's
C++ `discordpp` API) via prefab, which is already enabled in this project. That step needs the NDK
this project pins (`ndkVersion` in `app/build.gradle.kts`) installed. It is not required for builds
without the AAR, since no native code is compiled then.

Users still have to opt in per-device, under Settings > Discord > "Show game on Discord" (off by
default, because it makes what someone plays visible to their Discord friends).

## What the pieces are

| Path | Role |
| --- | --- |
| `app/src/main/cpp/discordrpc/` | JNI bridge to `discordpp`; owns the SDK client and its `RunCallbacks()` pump |
| `app/src/main/java/app/gamenative/discord/DiscordNative.kt` | `System.loadLibrary("discordbridge")` + `external fun`s; `isAvailable` is false when the bridge is absent |
| `app/src/main/java/app/gamenative/discord/DiscordRichPresence.kt` | The integration itself: start/stop/clear, state, retry, failure reporting |
| `app/src/main/java/app/gamenative/discord/DiscordConfig.kt` | Build-time application ID and art-asset keys |
| `app/src/main/java/app/gamenative/discord/DiscordSocialSdkCompat.kt` | Reflective `DiscordSocialSdkInit.setEngineActivity(...)`, so `src/main/java` compiles with or without the AAR |
| `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDiscord.kt` | The opt-in toggle and a plain-language status line |

## Limitations

- Android 7.0+, per Discord's platform requirements. GameNative's `minSdk` is already 26.
- The Discord Android app must be installed and signed in. The RPC path talks to it locally, and
  there is no fallback that would work without it.
- No per-game detail. GameNative reports the game name, its cover art and the start time. A game's
  own state ("Story Mode", a level name) would have to come from inside the game; `DiscordRichPresence`
  has a `details` field ready for it.
- Custom games get no cover art. Their art is scanned from local files, which Discord's servers
  cannot fetch; they fall back to the `gamenative` asset.
