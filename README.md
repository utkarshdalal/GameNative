[![Star History Rank](https://api.star-history.com/badge?repo=utkarshdalal/GameNative&theme=dark)](https://www.star-history.com/utkarshdalal/gamenative)

# GameNative


GameNative allows you to play games you own on Steam, Epic and GOG directly on Android devices, with cloud saves.

[Playing Stray on Poco F6](https://github.com/user-attachments/assets/1870fd14-7de9-4054-ba92-d3a5c73686b5)

## How to Use

(Note that GameNative is still in its early stages, and all games may not work, or may require tweaking to get working well)
1. Download the latest release [here](https://downloads.gamenative.app/releases/0.9.0/gamenative-v0.9.0.apk)
2. Install the APK on your Android device
3. Login to your Steam account
4. Install your game
5. Hit play and enjoy!

## Support
To report issues or receive support, join the [Discord server](https://discord.gg/2hKv4VfZfE)

Do not create issues on GitHub as they will be automatically closed!

You can support GameNative on Ko-fi at https://ko-fi.com/gamenative

## Building
### IF YOU JUST WANT TO USE THE APP, PLEASE SEE THE HOW TO USE SECTION ABOVE. THIS IS ONLY NEEDED IF YOU WANT TO CONTRIBUTE FOR DEVELOPMENT.
1. I use a normal build in Android studio. Hit me up if you can't figure out how to build.
2. **SteamGridDB API Key (Optional):** To enable automatic fetching of game images for Custom Games, add your SteamGridDB API key to `local.properties`:
   ```
   STEAMGRIDDB_API_KEY=your_api_key_here
   ```
   Get your API key from: https://www.steamgriddb.com/profile/preferences
   If the API key is not configured, the app will log a message but continue to work normally without fetching images.

## Community

Join our [Discord server](https://discord.gg/2hKv4VfZfE) for support and updates.

## License
[GPL 3.0](https://github.com/utkarshdalal/GameNative/blob/master/LICENSE)

See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for attributions, copyleft source offers, and notices about third-party and proprietary components bundled with the application.

## Analytics & Privacy

GameNative uses [PostHog](https://posthog.com) for anonymous analytics. No personal information is ever collected — no names, emails, IPs, or device identifiers.

**Always collected** (to improve game compatibility):
- Game launch, close, and exit events (game name, store, session length, average FPS, container config)
- Game install, cancel, and uninstall events

This data helps us understand which games work, how well they perform, and automatically apply known-good configurations for future users. It cannot identify you.

**Optional** (can be disabled in Settings > Info > Usage Analytics):
- Feature usage (on-screen keyboard, controller, HUD, control editor)
- Login success/failure events
- Recommendation interactions
- App lifecycle events (foreground/background)
- Cloud sync events

See our full [Privacy Policy](PrivacyPolicy/README.md) for more details.

**Disclaimer: This software is intended for playing games that you legally own. Do not use this software for piracy or any other illegal purposes. The maintainer of this fork assumes no
responsibility for misuse.**
