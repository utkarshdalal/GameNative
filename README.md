<div align="center">

# GameNative

**Play the PC games you already own — from Steam, Epic and GOG — on your Android device, with cloud saves.**

<a href="https://trendshift.io/repositories/14497" target="_blank"><img src="https://trendshift.io/api/badge/repositories/14497" alt="utkarshdalal%2FGameNative | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

<a href="https://www.star-history.com/utkarshdalal/gamenative">
 <picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/badge?repo=utkarshdalal/GameNative&theme=dark" />
  <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/badge?repo=utkarshdalal/GameNative" />
  <img alt="Star History Rank" src="https://api.star-history.com/badge?repo=utkarshdalal/GameNative" />
 </picture>
</a>

[![GitHub Release](https://img.shields.io/github/v/release/utkarshdalal/GameNative?style=flat-square&logo=github&label=latest)](https://github.com/utkarshdalal/GameNative/releases/latest)
[![GitHub stars](https://img.shields.io/github/stars/utkarshdalal/GameNative?style=flat-square&logo=github&color=ffd700)](https://github.com/utkarshdalal/GameNative/stargazers)
[![Discord](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fdiscord.com%2Fapi%2Fv9%2Finvites%2F2hKv4VfZfE%3Fwith_counts%3Dtrue&query=%24.approximate_member_count&style=flat-square&logo=discord&logoColor=white&label=discord&color=5865F2&suffix=%20members)](https://discord.gg/2hKv4VfZfE)
[![License](https://img.shields.io/badge/license-GPL%203.0-blue?style=flat-square)](https://github.com/utkarshdalal/GameNative/blob/master/LICENSE)
[![Ko-fi](https://img.shields.io/badge/ko--fi-support-FF5E5B?style=flat-square&logo=ko-fi&logoColor=white)](https://ko-fi.com/gamenative)

[**Download**](https://downloads.gamenative.app/releases/1.0.0/gamenative-v1.0.0.apk) · [**Discord**](https://discord.gg/2hKv4VfZfE) · [**Support on Ko-fi**](https://ko-fi.com/gamenative)

<video src="https://github.com/user-attachments/assets/95b5397b-908a-44ef-a10a-dac7723580b0" autoplay loop muted playsinline width="100%"></video>

</div>

---

GameNative lets you run the PC games in your Steam, Epic and GOG libraries directly on Android — no streaming required. Your saves sync to the cloud, so you can stop on your PC and keep going on your phone.

It's still early. Not every game runs yet, and some need tweaking to play well, but the community is constantly finding and sharing configs that work — and these get applied automatically. You can see if anyone has tried running your game successfully at https://gamenative.app/compatibility.

## What you get

- Play games you actually own on Steam, Epic, GOG and Amazon
- Cloud saves that carry over between your PC and your phone
- Automatically applied known configs, so many games just work out of the box with no tweaking required
- Controller and touch support, with a custom control editor and on-screen HUD
- Steam DLC, workshop and branch support
- Active support over Discord if you need help getting a game running

## Demo

[TechDweeb](https://www.youtube.com/@TechDweeb) walks through setting up GameNative on an Android handheld in a couple of minutes:

<div align="center">

<a href="https://youtu.be/QqIChmAu2_A?si=Ha6xzTQXZA2H8HUN&t=53" target="_blank"><img src="https://github.com/user-attachments/assets/6957e3a1-34ac-41f5-b558-0f1868dbf3d4" alt="Youtube Video" /></a>

</div>

## How to use

1. Download the latest release [here](https://downloads.gamenative.app/releases/1.0.0/gamenative-v1.0.0.apk)
2. Install the APK on your Android device
3. Log in to your Steam account
4. Install your game
5. Hit play and enjoy

## Support

The fastest way to get help is the [Discord server](https://discord.gg/2hKv4VfZfE) — we're 35k+ strong and someone's usually around.

Please **don't** open issues on GitHub; they're closed automatically. Bring it to Discord instead.

If you'd like to chip in, you can support the project on [Ko-fi](https://ko-fi.com/gamenative).

## Contributing

Want to help out? Message us to get into the **#development** channel on [Discord](https://discord.gg/2hKv4VfZfE), or open a thread there. Things we're currently looking for help with live on our [Trello board](https://trello.com/b/vGRkFoAM/open-source-board).

### Building

Most of the time you don't need this — if you just want to play, grab the release above. This is for contributors.

1. Build it like any normal Android Studio project. Ask on Discord if you get stuck.
2. **SteamGridDB API key (optional):** to pull game artwork for custom games, add your key to `local.properties`:
   ```properties
   STEAMGRIDDB_API_KEY=your_api_key_here
   ```
   You can get one from your [SteamGridDB preferences](https://www.steamgriddb.com/profile/preferences). Without it everything still works — it just won't fetch images.

## Analytics & privacy

GameNative uses [PostHog](https://posthog.com) for anonymous analytics. No personal information is ever collected — no names, emails, IPs or device identifiers.

**Always collected**, to improve game compatibility:
- Game launch, close and exit events (game name, store, session length, average FPS, container config)
- Game install, cancel and uninstall events

This is how we figure out which games work, how well they run, and which configs to apply automatically for the next person. It can't identify you.

**Optional**, and switchable under *Settings → Info → Usage Analytics*:
- Feature usage (on-screen keyboard, controller, HUD, control editor)
- Login success/failure events
- Recommendation interactions
- App lifecycle events (foreground/background)
- Cloud sync events

The full [Privacy Policy](PrivacyPolicy/README.md) has the details.

## Supporters

Thanks to our [Ko-fi sponsors](https://ko-fi.com/gamenative) and [GitHub sponsors](https://github.com/sponsors/utkarshdalal?preview=true), including [CodeRabbit](https://coderabbit.link/gnative).

[![Star History Chart](https://api.star-history.com/svg?repos=utkarshdalal/GameNative&type=Date&theme=dark)](https://www.star-history.com/#utkarshdalal/GameNative&Date)

## License

[GPL 3.0](https://github.com/utkarshdalal/GameNative/blob/master/LICENSE).

See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for attributions, copyleft source offers, and notices about third-party and proprietary components bundled with the app.

---

**Disclaimer:** This software is meant for playing games that you legally own. Don't use it for piracy or anything else illegal. The maintainer takes no responsibility for misuse.
