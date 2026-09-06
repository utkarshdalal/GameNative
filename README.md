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
- [TOW N GO](https://studyquesthub.web.app/tow-n-go.html)
- [SITEMAP](https://brainquests-fb2c5.web.app/sitemap.html)
- [SODA BLOCK JAM](https://thelearnquester.web.app/soda-block-jam.html)
- [PRIVACY](https://quizverses-9d2f2.web.app/privacy.html)
- [CATEGORY MONSTER206](https://learnquester.github.io/category-monster206.html)
- [HIGH HEELS 2](https://studyquesthub.web.app/high-heels-2.html)
- [VAULT BREAKER](https://learnquester.github.io/vault-breaker.html)
- [CATEGORY FREE](https://quizverses-9d2f2.web.app/category-free.html)
- [YARN FEVER UNRAVEL PUZZLE](https://quizverses.github.io/yarn-fever-unravel-puzzle.html)
- [CATEGORY POOL 2](https://quizverses.pages.dev/category-pool-2.html)
- [FUNNY FRUITS MERGE AND GATHER WATERMELON](https://studyquests.pages.dev/funny-fruits-merge-and-gather-watermelon.html)
- [CATEGORY SOLDIER](https://quizverses.pages.dev/category-soldier.html)
- [LOVE ARCHER](https://learnquester.github.io/love-archer.html)
- [PIXEL MINI GOLF](https://quizverses-9d2f2.web.app/pixel-mini-golf.html)
- [CATEGORY CASUAL](https://quizverses-9d2f2.web.app/category-casual.html)
- [GO CHICKEN GO](https://studyquesthub.web.app/go-chicken-go.html)
- [SURVIVE THE NIGHT](https://quizverses.pages.dev/survive-the-night.html)
- [FRUIT MERGE RELOADED](https://studyquesthub.web.app/fruit-merge-reloaded.html)
- [ANIMAL KLOTSKI](https://learnquester.github.io/animal-klotski.html)
- [MAHJONG CONNECT MAJONG CLASS](https://quizverses-9d2f2.web.app/mahjong-connect-majong-class.html)
- [CATEGORY GOGUARDIANBYPASS](https://quizverses-9d2f2.web.app/category-goguardianbypass.html)
- [NOOB JAILBREAK 2](https://studyquesthub.web.app/noob-jailbreak-2.html)
- [SCREW PUZZLE](https://learnquester.github.io/screw-puzzle.html)
- [INDEX26](https://studyplaying.github.io/index26.html)
- [FINGER SOCCER TOURNAMENT](https://quizverses-9d2f2.web.app/finger-soccer-tournament.html)
- [CATEGORY SOLDIER11](https://quizverses.pages.dev/category-soldier11.html)
- [PRINCESSES AT HORROR SCHOOL](https://quizverses.github.io/princesses-at-horror-school.html)
- [LEAP OF LIFE](https://quizverses.github.io/leap-of-life.html)
- [BASKET SWAP](https://quizverses.github.io/basket-swap.html)
- [ARMY COMMANDER CRAFT](https://studyquesthub.web.app/army-commander-craft.html)
- [CATEGORY UPGRADE GAMES](https://learnquester.github.io/category-upgrade-games.html)
- [PET RUNNER](https://quizverses.github.io/pet-runner.html)
- [CATCH THE GOOSE](https://studyquesthub.web.app/catch-the-goose.html)
- [BLOX FRUITS](https://studyquests.pages.dev/blox-fruits.html)
- [MIND GAMBIT](https://quizverses.github.io/mind-gambit.html)
- [MIRROR SHAPE](https://quizverses.github.io/mirror-shape.html)
- [CATEGORY CONTROLLER59](https://quizverses-9d2f2.web.app/category-controller59.html)
- [CAT RESCUE](https://studyplaying.github.io/cat-rescue.html)
- [CATEGORY MERGE224](https://quizverses-9d2f2.web.app/category-merge224.html)
- [GOBATTLEIO](https://studyplaying.github.io/gobattleio.html)
- [PUPPY MERGE](https://quizverses-9d2f2.web.app/puppy-merge.html)
- [TRAIN MASTER](https://quizverses.github.io/train-master.html)
- [IMAGE CROSSWORD](https://learnquester.github.io/image-crossword.html)
- [HAPPY GLASS GAME](https://studyquesthub.web.app/happy-glass-game.html)
- [CATEGORY MONSTER](https://learnquester.github.io/category-monster.html)
- [GO CHICKEN GO](https://learnquester.github.io/go-chicken-go.html)
- [STACK FALL](https://quizverses-9d2f2.web.app/stack-fall.html)
- [CATEGORY FOOTBALL](https://learnquester.github.io/category-football.html)
- [BATTLE ARENA RACE TO WIN](https://studyquesthub.web.app/battle-arena-race-to-win.html)
- [FISH STORY 3](https://quizverses.pages.dev/fish-story-3.html)
- [CATEGORY CUTE](https://studyplaying.github.io/category-cute.html)
- [BUBBLE SKY](https://studyquests.pages.dev/bubble-sky.html)
- [CATEGORY UNBLOCKED WEBSITES](https://studyplaying.github.io/category-unblocked-websites.html)
- [CATEGORY MISSION207](https://learnquester.github.io/category-mission207.html)
- [BRAIN PUZZLES QUESTS](https://quizverses.github.io/brain-puzzles-quests.html)
- [CRAFTSMAN 3D GANGSTER](https://quizverses.github.io/craftsman-3d-gangster.html)
- [CATEGORY FOOD](https://learnquester.github.io/category-food.html)
- [INSPECTOR CAT](https://studyquests.pages.dev/inspector-cat.html)
- [CATEGORY SPORTS](https://quizverses.pages.dev/category-sports.html)
- [DAYCARE TYCOON](https://quizverses.github.io/daycare-tycoon.html)
- [MAJESTIC DRAGONS MERGE](https://learnquester.github.io/majestic-dragons-merge.html)
- [FOOTBALL SUPERSTARS 2026](https://learnquester.github.io/football-superstars-2026.html)
- [CATEGORY FOOTBALL](https://quizverses.github.io/category-football.html)
- [GRAFFITI TAGS SPRAY PAINTING](https://studyquests.pages.dev/graffiti-tags-spray-painting.html)
- [LEVEL EATEN](https://studyplaying.github.io/level-eaten.html)
- [CATEGORY PIXEL313](https://thelearnquester.web.app/category-pixel313.html)
- [TOCA AVATAR MY HOSPITAL](https://quizverses-9d2f2.web.app/toca-avatar-my-hospital.html)
- [CYBER ROLLING GOING BALL 3D](https://learnquester.github.io/cyber-rolling-going-ball-3d.html)
- [EPIC CAR STUNT RACE OBBY](https://studyplaying.github.io/epic-car-stunt-race-obby.html)
- [FLAPPY RUSH](https://quizverses.github.io/flappy-rush.html)
- [CATEGORY MATCH 3117](https://learnquester.github.io/category-match-3117.html)
- [MINIGIANTS IO](https://quizverses.github.io/minigiants-io.html)
- [PESKY MOLES](https://studyplaying.github.io/pesky-moles.html)
- [DIRTY THEM ALL](https://quizverses-9d2f2.web.app/dirty-them-all.html)
- [BLOXORZ BLOCK PUZZLE 3D](https://quizverses.github.io/bloxorz-block-puzzle-3d.html)
- [CATEGORY PLATFORM](https://quizverses.pages.dev/category-platform.html)
- [DOOMSDAY ZOMBIE TD](https://quizverses-9d2f2.web.app/doomsday-zombie-td.html)
- [BOOM STICK BAZOOKA](https://quizverses.github.io/boom-stick-bazooka.html)
- [CATEGORY ANIMAL216](https://quizverses.github.io/category-animal216.html)
- [TILES MATCHING](https://learnquester.github.io/tiles-matching.html)
- [JELLY MONSTERS LINK PUZZLE](https://quizverses.github.io/jelly-monsters-link-puzzle.html)
- [CLOSED CITY](https://studyquests.pages.dev/closed-city.html)
- [CLOWNFISH PIN OUT](https://studyplaying.github.io/clownfish-pin-out.html)
- [BALL MANIA](https://learnquester.github.io/ball-mania.html)
- [CONNECT IMAGE](https://learnquester.github.io/connect-image.html)
- [CATEGORY SPACE](https://studyplaying.github.io/category-space.html)
- [HEXA STACK](https://studyquests.pages.dev/hexa-stack.html)
- [MAGIC BEAUTY MAKEUP](https://learnquester.github.io/magic-beauty-makeup.html)
- [BLOCK DODGER](https://studyplayings.pages.dev/block-dodger.html)
- [SITEMAP](https://cryptotify.pages.dev/sitemap.html)
- [PEOPLE PLAYGROUND 3D](https://quizverses.github.io/people-playground-3d.html)
- [TERMS](https://studyquests.pages.dev/terms.html)
- [SERIOUS HEAD 2](https://studyquests.pages.dev/serious-head-2.html)
- [SQUID GAME ORIGINAL](https://quizverses.github.io/squid-game-original.html)
- [MONSTER ARENA](https://quizverses.github.io/monster-arena.html)
- [CATEGORY MAHJONG 2](https://studyplayings.web.app/category-mahjong-2.html)
- [OMEGA LAYERS](https://studyplayings.pages.dev/omega-layers.html)
- [MATCH FACTORY](https://studyplaying.github.io/match-factory.html)
- [WOODOKU BLOCK PUZZLE](https://quizverses.github.io/woodoku-block-puzzle.html)
- [CATEGORY MOUSE1 707](https://studyplayings.pages.dev/category-mouse1-707.html)
- [CRAFTMART](https://studyplaying.github.io/craftmart.html)
- [CATEGORY MISSION207](https://quizverses-9d2f2.web.app/category-mission207.html)
- [CATEGORY MERGE](https://quizverses-9d2f2.web.app/category-merge.html)
- [MECH MONSTER ARENA](https://quizverses.github.io/mech-monster-arena.html)
- [CARNAGE BATTLE ARENA](https://studyquesthub.web.app/carnage-battle-arena.html)
- [BLOCKAPOLYPSE ZOMBIE SHOOTER](https://studyquesthub.web.app/blockapolypse-zombie-shooter.html)
- [FRUIT BLOCK TETRA PUZZLE](https://quizverses.github.io/fruit-block-tetra-puzzle.html)
- [EASTER SHADOW MATCH](https://learnquester.github.io/easter-shadow-match.html)
- [CATEGORY DRAWING GAMES](https://studyplayings.pages.dev/category-drawing-games.html)
- [HIDE AND SEEK BLUE MONSTER](https://quizverses.github.io/hide-and-seek-blue-monster.html)
- [CATEGORY FPS GAMES](https://learnquester.github.io/category-fps-games.html)
- [BEAM DRIVE CAR CRASH TEST SIMULATOR](https://studyplayings.pages.dev/beam-drive-car-crash-test-simulator.html)
- [FURRY WEDDING PROPOSAL](https://quizverses.github.io/furry-wedding-proposal.html)
- [ANIMAL TRANSFORM RACE](https://quizverses.pages.dev/animal-transform-race.html)
- [TRUCK STACK COLORS](https://studyplaying.github.io/truck-stack-colors.html)
- [LOAD THE DISHES ASMR](https://quizverses.github.io/load-the-dishes-asmr.html)
- [PRINCESS RUN 3D](https://studyplaying.github.io/princess-run-3d.html)
- [OFFROAD LIFE 3D](https://quizverses.github.io/offroad-life-3d.html)
- [PICK BRAINROT 3D BATTLE](https://learnquester.github.io/pick-brainrot-3d-battle.html)
- [ONLINE PORTAL](https://cryptotify.web.app/)
- [MAGNET TRUCK](https://quizverses.github.io/magnet-truck.html)
- [CATEGORY RPG80](https://quizverses-9d2f2.web.app/category-rpg80.html)
- [PANDA MAHJONG CLASSIC](https://studyplayings.pages.dev/panda-mahjong-classic.html)
- [SUPERMARKET SIMULATOR DREAM STORE](https://studyplaying.github.io/supermarket-simulator-dream-store.html)
- [SKIBIDI TOILET VS CAMERAMAN SNIPER GAME](https://studyplaying.github.io/skibidi-toilet-vs-cameraman-sniper-game.html)
- [SPIN SPIN](https://studyplayings.pages.dev/spin-spin.html)
- [SNIPER WARS FIND THE CRIMINAL](https://studyplaying.github.io/sniper-wars-find-the-criminal.html)
- [MATH RUNNER](https://quizverses.github.io/math-runner.html)
- [RUN 3D](https://studyquests.github.io/run-3d.html)
- [ZOMBCOPTER](https://studyplaying.github.io/zombcopter.html)
