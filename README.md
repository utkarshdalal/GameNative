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

[**Download**](https://downloads.gamenative.app/releases/1.2.0/gamenative-v1.2.0.apk) · [**Discord**](https://discord.gg/2hKv4VfZfE) · [**Support on Ko-fi**](https://ko-fi.com/gamenative)

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

1. Download the latest release [here](https://downloads.gamenative.app/releases//gamenative-v.apk)
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

[![Star History Chart](https://star-history.dera.page/svg?repos=utkarshdalal/GameNative&type=Date&theme=dark)](https://star-history.dera.page/#utkarshdalal/GameNative&Date)

## License

[GPL 3.0](https://github.com/utkarshdalal/GameNative/blob/master/LICENSE).

See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for attributions, copyleft source offers, and notices about third-party and proprietary components bundled with the app.

---

**Disclaimer:** This software is meant for playing games that you legally own. Don't use it for piracy or anything else illegal. The maintainer takes no responsibility for misuse.


## 🌐 Web Resources & Interactive Index
- [ARROW ESCAPE MASTER](https://studyplayings.pages.dev/arrow-escape-master.html)
- [CRIME THEFT GANGSTER PARADISE](https://studyplaying.github.io/crime-theft-gangster-paradise.html)
- [ROOM SORT FLOOR PLAN](https://learnquesters.pages.dev/room-sort-floor-plan.html)
- [COLOR DOTS CHALLENGE](https://studyplayings.pages.dev/color-dots-challenge.html)
- [INDEX21](https://quizverses.pages.dev/index21.html)
- [ELLIE AND BEN CHRISTMAS EVE](https://studyplayings.web.app/ellie-and-ben-christmas-eve.html)
- [CATEGORY UPGRADE GAMES](https://studyplayings.web.app/category-upgrade-games.html)
- [IDLE BATHROOM EMPIRE TYCOON](https://studyplayings.web.app/idle-bathroom-empire-tycoon.html)
- [VENETIAN LOVE AFFAIR](https://quizverses.github.io/venetian-love-affair.html)
- [SCARY TEACHER 3D RETURNS](https://quizverses.github.io/scary-teacher-3d-returns.html)
- [POPPY PLAYTIME 3 GAME](https://quizverses.pages.dev/poppy-playtime-3-game.html)
- [BUBBLE SHOOTER VINTAGE](https://studyquests.github.io/bubble-shooter-vintage.html)
- [ZINDEX](https://studyquests.github.io/zindex.html)
- [CATEGORY SNAKE40](https://studyplayings.web.app/category-snake40.html)
- [FIGHT TRIVIA](https://studyquests.pages.dev/fight-trivia.html)
- [IBIZA FOAM PARTY](https://quizverses.pages.dev/ibiza-foam-party.html)
- [ASMR BEAUTY JAPANESE SPA](https://studyquests.pages.dev/asmr-beauty-japanese-spa.html)
- [GLAM GURU PUZZLE COLLECTION](https://studyquests.github.io/glam-guru-puzzle-collection.html)
- [NUBIK IN THE MONSTER WORLD](https://quizverses.pages.dev/nubik-in-the-monster-world.html)
- [CATEGORY BUBBLE SHOOTER](https://studyplaying.github.io/category-bubble-shooter.html)
- [CATEGORY FPS174](https://studyplaying.github.io/category-fps174.html)
- [CRAFT DRILL](https://studyquests.github.io/craft-drill.html)
- [CATEGORY CASUAL 5](https://studyplayings.web.app/category-casual-5.html)
- [CATEGORY PARKOUR55](https://studyplayings.web.app/category-parkour55.html)
- [STACK N SORT](https://studyquests.github.io/stack-n-sort.html)
- [SIGMA BOY MUSICAL CLICKER](https://studyplayings.web.app/sigma-boy-musical-clicker.html)
- [GEOMETRY DASH MAZE MAPS](https://studyquests.github.io/geometry-dash-maze-maps.html)
- [STACK TOWER PRO](https://studyplayings.web.app/stack-tower-pro.html)
- [CLEAN THE FLOOR](https://quizverses.pages.dev/clean-the-floor.html)
- [TAP BEAD](https://studyplayings.web.app/tap-bead.html)
- [ITALIAN BRAINROT DRAG MERGE PUZZLE](https://studyquests.github.io/italian-brainrot-drag-merge-puzzle.html)
- [AMMO RUSH MASTER](https://studyplaying.github.io/ammo-rush-master.html)
- [CATEGORY CUTE](https://studyplaying.github.io/category-cute.html)
- [SOFT GIRLS WINTER AESTHETICS](https://studyplaying.github.io/soft-girls-winter-aesthetics.html)
- [CATEGORY STRATEGY](https://studyplaying.github.io/category-strategy.html)
- [GOODELUXE](https://studyquests.github.io/goodeluxe.html)
- [PAPERWARIO](https://studyquests.github.io/paperwario.html)
- [CATEGORY IDLE448](https://studyplaying.github.io/category-idle448.html)
- [OMG WORD RAINBOW](https://quizverses.pages.dev/omg-word-rainbow.html)
- [BEST FRIENDS PUZZLE](https://studyplaying.github.io/best-friends-puzzle.html)
- [SOLITAIRE DELUXE EDITION](https://studyplaying.github.io/solitaire-deluxe-edition.html)
- [AVOID THE SPIKES](https://studyquests.github.io/avoid-the-spikes.html)
- [CATEGORY PUZZLE](https://studyplaying.github.io/category-puzzle.html)
- [GT CHAMPIONSHIP ARCADE](https://studyquests.github.io/gt-championship-arcade.html)
- [CATEGORY MAHJONG 2](https://studyplaying.github.io/category-mahjong-2.html)
- [IDLE BARBER SHOP](https://quizverses.pages.dev/idle-barber-shop.html)
- [PATH ICE](https://quizverses.pages.dev/path-ice.html)
- [FARM VS ZOMBIES](https://studyplayings.pages.dev/farm-vs-zombies.html)
- [SQUAD ASSEMBLER](https://studyplayings.pages.dev/squad-assembler.html)
- [CALL OF THE JUNGLE ANIMAL EVOLUTION](https://studyplaying.github.io/call-of-the-jungle-animal-evolution.html)
- [OBBY PINATA PARTY](https://studyquests.github.io/obby-pinata-party.html)
- [HARBOR OPERATOR](https://studyplaying.github.io/harbor-operator.html)
- [DUMMIES WORLD CUP](https://studyplayings.pages.dev/dummies-world-cup.html)
- [MERGE COMBO](https://studyquests.github.io/merge-combo.html)
- [CATEGORY CLASSIC98](https://studyplaying.github.io/category-classic98.html)
- [TURNFIGHT COM UAP](https://studyplayings.pages.dev/turnfight-com-uap.html)
- [DEAD BRAIN](https://studyquests.github.io/dead-brain.html)
- [WEAPONS AND RAGDOLLS](https://studyplayings.pages.dev/weapons-and-ragdolls.html)
- [CATEGORY CASUAL 12](https://studyquests.github.io/category-casual-12.html)
- [CARS MERGE](https://studyquests.github.io/cars-merge.html)
- [DESSERT DIY](https://studyquests.github.io/dessert-diy.html)
- [CATEGORY PIXEL313](https://studyplayings.web.app/category-pixel313.html)
- [CATEGORY BUILDING](https://studyplaying.github.io/category-building.html)
- [KIRKA IO](https://studyquests.github.io/kirka-io.html)
- [MAGIC PIANO MUSIC](https://studyquests.github.io/magic-piano-music.html)
- [WAVE DASH GEOMETRY ARROW](https://studyplayings.pages.dev/wave-dash-geometry-arrow.html)
- [MERGE HEROES](https://quizverses.pages.dev/merge-heroes.html)
- [CATEGORY GOGUARDIAN](https://studyplaying.github.io/category-goguardian.html)
- [LOST PUPPY RESCUE AND CARE](https://studyquests.github.io/lost-puppy-rescue-and-care.html)
- [RACING FOR TWO ON ONE PC](https://studyquests.github.io/racing-for-two-on-one-pc.html)
- [BATTLE OF PIRATE CARIBBEAN BATTLE](https://studyquests.github.io/battle-of-pirate-caribbean-battle.html)
- [DRAGON DRAW JOUST](https://studyquests.github.io/dragon-draw-joust.html)
- [THE FLOWERS MERGE AND SELL BOUQUETS](https://quizverses.pages.dev/the-flowers-merge-and-sell-bouquets.html)
- [ONU LIVE](https://studyquests.github.io/onu-live.html)
- [ZOMBIE DRIFT 3D](https://studyplayings.web.app/zombie-drift-3d.html)
- [MONSTER SCHOOL 2](https://quizverses-9d2f2.web.app/monster-school-2.html)
- [BATTLE RACING STARS](https://quizverses.pages.dev/battle-racing-stars.html)
- [HAPPY MONSTERS 2](https://studyplayings.pages.dev/happy-monsters-2.html)
- [BATTLE ARENA](https://studyquests.github.io/battle-arena.html)
- [WORDMEISTER HD](https://studyplayings.web.app/wordmeister-hd.html)
- [CATEGORY YOUTUBE](https://studyquests.github.io/category-youtube.html)
- [CATEGORY JIGSAW](https://studyplaying.github.io/category-jigsaw.html)
- [TIED UP](https://studyplayings.web.app/tied-up.html)
- [SMART DOTS RELOADED](https://studyquests.github.io/smart-dots-reloaded.html)
- [HALLOWEEN FRUIT SLICE](https://studyplaying.github.io/halloween-fruit-slice.html)
- [GEOMETRY PLATFORMER](https://quizverses.pages.dev/geometry-platformer.html)
- [CATEGORY BATTLE ROYALE25](https://studyplayings.web.app/category-battle-royale25.html)
- [MOJICON GARDEN CONNECT](https://quizverses.github.io/mojicon-garden-connect.html)
- [CLICKER KNIGHTS VS DRAGONS](https://quizverses.github.io/clicker-knights-vs-dragons.html)
- [CATEGORY MEDIEVAL15](https://studyquests.github.io/category-medieval15.html)
- [CHEERFUL PLUMBER](https://quizverses.pages.dev/cheerful-plumber.html)
- [CATEGORY MINECRAFT81](https://studyplaying.github.io/category-minecraft81.html)
- [SOLITAIRE KLONDIKE](https://studyplayings.pages.dev/solitaire-klondike.html)
- [FISH KINGDOM](https://studyplayings.pages.dev/fish-kingdom.html)
- [CATEGORY MOUSE1 707](https://studyplayings.web.app/category-mouse1-707.html)
- [CATEGORY MAGIC46](https://studyplaying.github.io/category-magic46.html)
- [OBBY BLOX HOOK](https://quizverses.github.io/obby-blox-hook.html)
- [CATEGORY PUZZLE](https://studyplayings.web.app/category-puzzle.html)
- [INDEX19](https://quizverses-9d2f2.web.app/index19.html)
- [CATEGORY SIMULATION 2](https://studyplaying.github.io/category-simulation-2.html)
- [CATEGORY SANDBOX40](https://studyplayings.web.app/category-sandbox40.html)
- [CATEGORY SOLDIER](https://studyplaying.github.io/category-soldier.html)
- [IDLE TRADE ROUTES](https://studyquests.github.io/idle-trade-routes.html)
- [TIKTOK TRENDS COLORED DENIM](https://quizverses.github.io/tiktok-trends-colored-denim.html)
- [MILITARY CUBES 2048](https://studyquests.github.io/military-cubes-2048.html)
- [CATEGORY BUILDING179](https://studyplaying.github.io/category-building179.html)
- [GUN CRAFT RUN WEAPON FIRE](https://studyplayings.pages.dev/gun-craft-run-weapon-fire.html)
- [QUEST BY COUNTRY](https://studyplayings.pages.dev/quest-by-country.html)
- [FINGER HEART MONSTER REFILL](https://quizverses.github.io/finger-heart-monster-refill.html)
- [CATEGORY DRESS UP 2](https://studyplaying.github.io/category-dress-up-2.html)
- [MR DISC SLINGSHOT STRIKE](https://studyplaying.github.io/mr-disc-slingshot-strike.html)
- [INDEX17](https://studyplayings.web.app/index17.html)
- [BRAINROT CLEANING](https://studyplayings.pages.dev/brainrot-cleaning.html)
- [CATEGORY ANIMAL](https://studyplayings.web.app/category-animal.html)
- [CATEGORY MERGE224](https://studyplaying.github.io/category-merge224.html)
- [CATEGORY SPORTS](https://studyplayings.web.app/category-sports.html)
- [SITEMAP](https://quizverses.pages.dev/sitemap.html)
- [STICKMAN PUNISHMENT](https://studyquests.github.io/stickman-punishment.html)
- [CATEGORY ROBOT49](https://studyplayings.web.app/category-robot49.html)
- [MOTO STUNTS DRIVING RACING](https://studyquests.github.io/moto-stunts-driving-racing.html)
- [INDEX7](https://quizverses.pages.dev/index7.html)
- [FLIGHT PILOT AIRPLANE GAMES 24](https://studyplayings.web.app/flight-pilot-airplane-games-24.html)
- [ESCAPE ROOM MYSTERY KEY](https://studyplayings.pages.dev/escape-room-mystery-key.html)
- [CRAZY MOTORCYCLE](https://quizverses.github.io/crazy-motorcycle.html)
- [PRINCESS RESCUE SAVE GIRL](https://studyquests.github.io/princess-rescue-save-girl.html)
- [STICKMAN ARCHERO FIGHT STICK SHADOW FIGHT WAR](https://studyplayings.web.app/stickman-archero-fight-stick-shadow-fight-war.html)
- [MAZE CRAZE](https://studyplayings.pages.dev/maze-craze.html)
- [BRAINROT MEMORY](https://studyquests.pages.dev/brainrot-memory.html)
- [WITCHY SISTERS RELAX PUZZLE](https://quizverses-9d2f2.web.app/witchy-sisters-relax-puzzle.html)
- [MATCH ARENA](https://quizverses.pages.dev/match-arena.html)
