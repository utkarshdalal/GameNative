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
- [CLOWNFISH PIN OUT](https://themindzone.pages.dev/clownfish-pin-out.html)
- [CATEGORY ROGUELIKE38](https://studyquesthub.web.app/category-roguelike38.html)
- [CATEGORY HORROR 2](https://studyplaying.github.io/category-horror-2.html)
- [WORDS WITH PROF WISELY](https://quizverses.github.io/words-with-prof-wisely.html)
- [CATEGORY MAKEUP51](https://quizverses.pages.dev/category-makeup51.html)
- [STICKMAN TEAM DETROIT](https://studyquests.github.io/stickman-team-detroit.html)
- [TONY ARCHER](https://learnquester.github.io/tony-archer.html)
- [ITALIAN BRAINROT QUIZ](https://learnquester.github.io/italian-brainrot-quiz.html)
- [PIXEL SHOOT](https://studyplaying.github.io/pixel-shoot.html)
- [CATEGORY FPS GAMES](https://studyplayings.web.app/category-fps-games.html)
- [DOGE MATCH](https://studyplaying.github.io/doge-match.html)
- [CHALLENGE YOUR FRIENDS](https://studyplaying.github.io/challenge-your-friends.html)
- [INDEX15](https://studyplayings.web.app/index15.html)
- [BLOCKS AND THATS IT](https://studyplayings.web.app/blocks-and-thats-it.html)
- [CATEGORY BUILDING182](https://studyplayings.web.app/category-building182.html)
- [STICK NINJA SURVIVAL](https://studyplaying.github.io/stick-ninja-survival.html)
- [CATEGORY BATTLE524](https://thelearnquester.web.app/category-battle524.html)
- [COLOR COCKTAIL](https://learnquester.github.io/color-cocktail.html)
- [CATEGORY FASHION105](https://thelearnquester.web.app/category-fashion105.html)
- [GOOBER DASH](https://studyquesthub.web.app/goober-dash.html)
- [GIRLS FUN NAIL SALON](https://quizverses.github.io/girls-fun-nail-salon.html)
- [CATEGORY INTERSTELLAR](https://studyplayings.pages.dev/category-interstellar.html)
- [MOTOR TOUR](https://studyplaying.github.io/motor-tour.html)
- [AIRPORT MASTER PLANE TYCOON](https://learnquester.github.io/airport-master-plane-tycoon.html)
- [ENCHANTED EASTER ADVENTURE](https://studyquests.github.io/enchanted-easter-adventure.html)
- [CATEGORY JIGSAW](https://quizverses.pages.dev/category-jigsaw.html)
- [PARKING DRIVER](https://studyplaying.github.io/parking-driver.html)
- [CATEGORY JUMPING147](https://learnquester.github.io/category-jumping147.html)
- [CATEGORY SECURLY BYPASS](https://quizverses-9d2f2.web.app/category-securly-bypass.html)
- [CATEGORY DRIFTING116](https://studyplayings.pages.dev/category-drifting116.html)
- [SLIME ATTACK PUZZLE](https://studyplayings.pages.dev/slime-attack-puzzle.html)
- [CATEGORY AVOID](https://studyquesthub.web.app/category-avoid.html)
- [BUBBLE SHOOTER AURA](https://quizverses.github.io/bubble-shooter-aura.html)
- [DOGS VS ALIENS](https://learnquester.github.io/dogs-vs-aliens.html)
- [CLASSIC LABYRINTH 3D MAZE](https://quizverses.github.io/classic-labyrinth-3d-maze.html)
- [FIDGET TRADING CARD TOY](https://studyplaying.github.io/fidget-trading-card-toy.html)
- [CATEGORY FOOD](https://quizverses.pages.dev/category-food.html)
- [CATEGORY FASHION105](https://quizverses.github.io/category-fashion105.html)
- [CATEGORY DRIFTING116](https://quizverses-9d2f2.web.app/category-drifting116.html)
- [VARIETY MECHA](https://studyplaying.github.io/variety-mecha.html)
- [MAKEUP FRUITS](https://studyplayings.pages.dev/makeup-fruits.html)
- [CATEGORY RPG80](https://learnquester.github.io/category-rpg80.html)
- [BATTLE SIMULATOR SANDBOX](https://studyplaying.github.io/battle-simulator-sandbox.html)
- [SITEMAP](https://studyplayings.web.app/sitemap.html)
- [MOW IT](https://quizverses.pages.dev/mow-it.html)
- [CUBE DROP PUZZLE](https://quizverses.github.io/cube-drop-puzzle.html)
- [MOJICON EMOJI CONNECT](https://studyplaying.github.io/mojicon-emoji-connect.html)
- [WINTER GIFTS](https://studyplaying.github.io/winter-gifts.html)
- [FIDGET TRADING CARD TOY](https://quizverses.github.io/fidget-trading-card-toy.html)
- [INDEX8](https://studyplayings.web.app/index8.html)
- [CLAY CRAFT TYCOON](https://studyplayings.web.app/clay-craft-tycoon.html)
- [DIRTY MONEY THE RICH GET RICH](https://studyquests.github.io/dirty-money-the-rich-get-rich.html)
- [BLOCKPUZZLE COLOR BLAST](https://studyplayings.pages.dev/blockpuzzle-color-blast.html)
- [VIKINGS AN ARCHERS JOURNEY](https://studyplayings.pages.dev/vikings-an-archers-journey.html)
- [MERGE SHOOTER](https://studyquests.github.io/merge-shooter.html)
- [NIGHT CLUB SECURITY](https://quizverses.github.io/night-club-security.html)
- [SMASH THE CAR TO PIECES](https://studyplayings.pages.dev/smash-the-car-to-pieces.html)
- [MY TINY LAND](https://studyplaying.github.io/my-tiny-land.html)
- [CATEGORY IO](https://studyplayings.web.app/category-io.html)
- [CATEGORY CAR376](https://studyquesthub.web.app/category-car376.html)
- [JUST MAHJONG](https://studyplayings.web.app/just-mahjong.html)
- [INDEX18](https://studyplayings.web.app/index18.html)
- [CATEGORY SPACE57](https://quizverses.pages.dev/category-space57.html)
- [BRAIN FIND CAN YOU FIND IT](https://quizverses.github.io/brain-find-can-you-find-it.html)
- [INDEX12](https://thelearnquester.web.app/index12.html)
- [SUMMER MAZE](https://studyplayings.pages.dev/summer-maze.html)
- [CATEGORY SOCCER](https://quizverses.pages.dev/category-soccer.html)
- [CATEGORY ESCAPE](https://quizverses.pages.dev/category-escape.html)
- [CHRISTMAS MERGE](https://studyplaying.github.io/christmas-merge.html)
- [CATEGORY FPS174](https://thelearnquester.web.app/category-fps174.html)
- [DREAMY HOME](https://studyplaying.github.io/dreamy-home.html)
- [CATEGORY FASHION105](https://quizverses-9d2f2.web.app/category-fashion105.html)
- [DESTRUCTION OF STICKMAN ZOMBIE](https://studyquests.github.io/destruction-of-stickman-zombie.html)
- [STARDOM ALT GIRLS FASHION DUEL](https://studyplayings.pages.dev/stardom-alt-girls-fashion-duel.html)
- [LAST WAR SURVIVAL](https://studyplaying.github.io/last-war-survival.html)
- [CATEGORY IDLE](https://learnquester.github.io/category-idle.html)
- [CATEGORY MATCH 3117](https://studyquesthub.web.app/category-match-3117.html)
- [CATEGORY MANAGEMENT209](https://quizverses.pages.dev/category-management209.html)
- [ZOMBIES AND GUNS](https://studyplaying.github.io/zombies-and-guns.html)
- [SPOTDIFFERS](https://studyplayings.pages.dev/spotdiffers.html)
- [CATEGORY CONTROLLER](https://quizverses.github.io/category-controller.html)
- [CATEGORY SPACE](https://quizverses.pages.dev/category-space.html)
- [CHRISTMAS SORTING](https://studyquests.github.io/christmas-sorting.html)
- [THE SORTING MART](https://studyplayings.pages.dev/the-sorting-mart.html)
- [DUCKLINGS](https://quizverses.github.io/ducklings.html)
- [INDEX5](https://studyplayings.web.app/index5.html)
- [GREEDY SNAKE BRAIN HOLE EXPLOSION](https://studyplaying.github.io/greedy-snake-brain-hole-explosion.html)
- [SCARY BANBAN ESCAPE](https://studyplayings.pages.dev/scary-banban-escape.html)
- [GAS STATION STICK SIMULATOR](https://studyquests.github.io/gas-station-stick-simulator.html)
- [SOLITAIRE EMPEROR SECRETS OF FATE](https://quizverses.github.io/solitaire-emperor-secrets-of-fate.html)
- [INDEX13](https://thelearnquesters.pages.dev/index13.html)
- [CATEGORY ARENA255](https://studyquesthub.web.app/category-arena255.html)
- [CATEGORY POOL 3](https://thequizzone.pages.dev/category-pool-3.html)
- [FOOTBALL PENALTY](https://studyplayings.pages.dev/football-penalty.html)
- [THOR MERGE](https://learnquesters.pages.dev/thor-merge.html)
- [BESTIES CHINESE NEW YEAR CELEBRATION](https://studyplaying.github.io/besties-chinese-new-year-celebration.html)
- [CUBATORIA MERGE 2048](https://thelearnquester.web.app/cubatoria-merge-2048.html)
- [CATEGORY MOBILE2 112](https://learnquester.pages.dev/category-mobile2-112.html)
- [HOLE DEFENSE](https://thequizzone.pages.dev/hole-defense.html)
- [HOLIDAY HEX SORT](https://studyquests.github.io/holiday-hex-sort.html)
- [MERGE THE COINS USSR](https://studyplaying.github.io/merge-the-coins-ussr.html)
- [FRUIT CAFE MATCH 3](https://learnquester.pages.dev/fruit-cafe-match-3.html)
- [ZOO SHAP](https://studyplaying.github.io/zoo-shap.html)
- [FIRE BALL AND WATER BALL PARKOUR LOVE BALLS](https://thelearnquester.web.app/fire-ball-and-water-ball-parkour-love-balls.html)
- [CATEGORY CASUAL 9](https://thequizzone.pages.dev/category-casual-9.html)
- [CATEGORY DEEP IMMERSIVE24](https://learnquester.github.io/category-deep-immersive24.html)
- [OFFROAD CLIMB 4X4](https://studyplaying.github.io/offroad-climb-4x4.html)
- [DELICIOUS EMILYS NEW BEGINNING VALENTINES EDITION](https://studyquests.github.io/delicious-emilys-new-beginning-valentines-edition.html)
- [CATEGORY CARE](https://studyquesthub.web.app/category-care.html)
- [CATEGORY FOOD95](https://learnquester.github.io/category-food95.html)
- [INDEX16](https://studyplayings.web.app/index16.html)
- [FISH EAT GROW MEGA](https://studyquests.github.io/fish-eat-grow-mega.html)
- [WORD ART COLOR BOOK PUZZLE](https://studyquests.github.io/word-art-color-book-puzzle.html)
- [CATEGORY FPS 2](https://quizverses.github.io/category-fps-2.html)
- [CATEGORY 2D1 070](https://learnquester.pages.dev/category-2d1-070.html)
- [DESIGN WITH ME SUPERHERO TUTU OUTFITS](https://thelearnquester.web.app/design-with-me-superhero-tutu-outfits.html)
- [CAR DEALER IDLE](https://studyquests.github.io/car-dealer-idle.html)
- [CATEGORY WEB PROXY](https://quizverses.pages.dev/category-web-proxy.html)
- [TRAFFIC RUN PUZZLE](https://thelearnquester.web.app/traffic-run-puzzle.html)
- [COUNTRY LIFE MEADOWS](https://thelearnquesters.pages.dev/country-life-meadows.html)
- [CATEGORY 2D1 070](https://learnquesters.pages.dev/category-2d1-070.html)
- [CATEGORY BUSINESS135](https://studyquesthub.web.app/category-business135.html)
- [CATCH THE GOOSE](https://thequizzone.pages.dev/catch-the-goose.html)
- [CATEGORY STRATEGY 3](https://thelearnquesters.pages.dev/category-strategy-3.html)
- [SLINGER BLOCK](https://thelearnquesters.pages.dev/slinger-block.html)
- [RELAX MINI GAMES COLLECTION](https://studyplaying.github.io/relax-mini-games-collection.html)
- [CATEGORY FARMING87](https://thelearnquesters.pages.dev/category-farming87.html)
- [CATEGORY CAR376](https://learnquesters.pages.dev/category-car376.html)
- [BOLT CLIMB TAP TO THE TOP](https://learnquester.pages.dev/bolt-climb-tap-to-the-top.html)
- [MERGE ARCHER DEFENSE](https://learnquester.pages.dev/merge-archer-defense.html)
