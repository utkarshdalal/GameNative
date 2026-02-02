# GameNative


GameNative allows you to play games you own on Steam directly on Android devices.

[Playing Stray on Poco F6](https://github.com/user-attachments/assets/1870fd14-7de9-4054-ba92-d3a5c73686b5)

This is a fork of [Pluvia](https://github.com/oxters168/Pluvia), a Steam client for Android.

## How to Use

(Note that GameNative is still in its early stages, and all games may not work, or may require tweaking to get working well)
1. Download the latest release [here](https://github.com/utkarshdalal/GameNative/releases/download/v0.7.1/gamenative-v0.7.1.apk)
2. Install the APK on your Android device
3. Login to your Steam account
4. Install your game
5. Hit play and enjoy!

## Support
To report issues or receive support, join the [Discord server](https://discord.gg/2hKv4VfZfE)

You can support GameNative on Ko-fi at https://ko-fi.com/gamenative

## Building
### IF YOU JUST WANT TO USE THE APP, PLEASE SEE THE HOW TO USE SECTION ABOVE. THIS IS ONLY NEEDED IF YOU WANT TO CONTRIBUTE FOR DEVELOPMENT.
1. I use a normal build in Android studio. Hit me up if you can't figure out how to build.
2. You may need to download some additional files and place them in the `src/main/assets` folder.
These files are available on request for legitimate development purposes. Please write to me on Discord and I can share them with you there.
3. **SteamGridDB API Key (Optional):** To enable automatic fetching of game images for Custom Games, add your SteamGridDB API key to `local.properties`:
   ```
   STEAMGRIDDB_API_KEY=your_api_key_here
   ```
   Get your API key from: https://www.steamgriddb.com/profile/preferences
   If the API key is not configured, the app will log a message but continue to work normally without fetching images.

## Community

Join our [Discord server](https://discord.gg/2hKv4VfZfE) for support and updates.

## License
[GPL 3.0](https://github.com/utkarshdalal/GameNative/blob/master/LICENSE)

## Privacy Policy
[Privacy Policy](https://github.com/utkarshdalal/GameNative/blob/master/PrivacyPolicy/README.md)

**Disclaimer: This software is intended for playing games that you legally own. Do not use this software for piracy or any other illegal purposes. The maintainer of this fork assumes no
responsibility for misuse.**
