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
- [CATEGORY PUZZLE 11](https://iskillquest.pages.dev/category-puzzle-11.html)
- [SNOW RACE 3D FUN RACING](https://themindplay.github.io/snow-race-3d-fun-racing.html)
- [CATEGORY 3D1 371](https://themindplay.github.io/category-3d1-371.html)
- [CATEGORY 3D1 371](https://iskillquest.pages.dev/category-3d1-371.html)
- [BOW AND ARROW](https://iskillquest.pages.dev/bow-and-arrow.html)
- [PURRFECT PUZZLE](https://themindplay.pages.dev/purrfect-puzzle.html)
- [BADLANDS HERO](https://iskillquest.pages.dev/badlands-hero.html)
- [CHECKERS DELUXE EDITION](https://themindplay.pages.dev/checkers-deluxe-edition.html)
- [ONLINE PORTAL](https://iskillquest.pages.dev/)
- [SUMMER MAZE](https://theskillquest.pages.dev/summer-maze.html)
- [CATEGORY CASUAL 6](https://iskillplay.web.app/category-casual-6.html)
- [PIN BOARD PUZZLE](https://skillplay.github.io/pin-board-puzzle.html)
- [GLOVES OF BLOCK](https://iskillquest.pages.dev/gloves-of-block.html)
- [HOTGEAR](https://themindplay.pages.dev/hotgear.html)
- [MAHJONG GARDEN](https://skillplay.github.io/mahjong-garden.html)
- [LIVE 100 DAYS](https://skillplay.github.io/live-100-days.html)
- [TRIANGLES](https://iskillquest.pages.dev/triangles.html)
- [VEGAMIX MATCH 3 VILLAGE](https://iskillquest.pages.dev/vegamix-match-3-village.html)
- [DARK ACADEMIA WEDDING](https://iskillquest.pages.dev/dark-academia-wedding.html)
- [INDEX23](https://themindplay.pages.dev/index23.html)
- [EGG ADVENTURE](https://skillplay.github.io/egg-adventure.html)
- [STICKMAN SORT](https://skillplay.github.io/stickman-sort.html)
- [PARK THEM ALL](https://iskillplay.web.app/park-them-all.html)
- [SID GINNY Y2K GLAM CLASH](https://iskillquest.pages.dev/sid-ginny-y2k-glam-clash.html)
- [MOTO CABBIE SIMULATOR](https://iskillquest.pages.dev/moto-cabbie-simulator.html)
- [BLOWUP ATM](https://iskillquest.pages.dev/blowup-atm.html)
- [FALLING BLOCKS PUZZLE](https://theskillquest.pages.dev/falling-blocks-puzzle.html)
- [COLORING BY NUMBERS PIXEL ROOMS](https://themindplay.pages.dev/coloring-by-numbers-pixel-rooms.html)
- [CATEGORY ART](https://themindplay.pages.dev/category-art.html)
- [GRAFFITI TAGS SPRAY PAINTING](https://theskillquest.pages.dev/graffiti-tags-spray-painting.html)
- [SITEMAP](https://iskillquest.pages.dev/sitemap.html)
- [PIXEL MINI GOLF](https://iskillquest.pages.dev/pixel-mini-golf.html)
- [FIGHT FOR THE TREE](https://skillplay.github.io/fight-for-the-tree.html)
- [POPPYTILE](https://iskillquest.pages.dev/poppytile.html)
- [BOLTS UNSCREW IT](https://skillplay.github.io/bolts-unscrew-it.html)
- [INDEX13](https://themindplay.github.io/index13.html)
- [HALLOWEEN CHALLENGE](https://themindskillplayplay.pages.dev/halloween-challenge.html)
- [REAL IMPOSSIBLE SKY TRACKS CAR DRIVING](https://iskillquest.pages.dev/real-impossible-sky-tracks-car-driving.html)
- [GARAGE MASTER NUTS AND BOLTS](https://theskillquest.pages.dev/garage-master-nuts-and-bolts.html)
- [BFFS Y2K FASHION](https://iskillquest.pages.dev/bffs-y2k-fashion.html)
- [MEMORY LANE](https://themindplay.github.io/memory-lane.html)
- [STICKMAN ARMY THE DEFENDERS](https://skillplay.github.io/stickman-army-the-defenders.html)
- [AIRPORT MASTER PLANE TYCOON](https://themindskillplayplay.pages.dev/airport-master-plane-tycoon.html)
- [SURVIVAL ISLAND EVO](https://iskillquest.pages.dev/survival-island-evo.html)
- [TICTOC URBAN OUTFITS](https://skillplay.github.io/tictoc-urban-outfits.html)
- [INDEX2](https://themindplay.github.io/index2.html)
- [ONLINE CAR DESTRUCTION SIMULATOR 3D](https://themindplay.pages.dev/online-car-destruction-simulator-3d.html)
- [ITALIAN BRAINROT NEURO BEASTS](https://theskillquest.pages.dev/italian-brainrot-neuro-beasts.html)
- [PAINT TILES PUZZLE](https://iskillplay.web.app/paint-tiles-puzzle.html)
- [RESIDENT EVIL PURGE OPERATION](https://skillplay.github.io/resident-evil-purge-operation.html)
- [FINGER SOCCER TOURNAMENT](https://themindskillplayplay.pages.dev/finger-soccer-tournament.html)
- [PERFECT PIANO MAGIC](https://skillplay.github.io/perfect-piano-magic.html)
- [FIND OBJECTS HIDDEN ITEM](https://theskillquest.pages.dev/find-objects-hidden-item.html)
- [KITTEN NEVER DIES](https://skillplay.github.io/kitten-never-dies.html)
- [MONSTER MERGE LEGENDS ALIVE](https://themindskillplayplay.pages.dev/monster-merge-legends-alive.html)
- [MOLANG MATCHN MUNCH](https://skillplay.github.io/molang-matchn-munch.html)
- [SWAP COLOR](https://theskillquest.pages.dev/swap-color.html)
- [COLOR RING SORTING MATCH](https://skillplay.github.io/color-ring-sorting-match.html)
- [COSMIC AVIATOR](https://theskillquest.pages.dev/cosmic-aviator.html)
- [COIN BLITZ](https://themindskillplayplay.pages.dev/coin-blitz.html)
- [OBBY DUMB OR GENIUS IQ TEST](https://iskillquest.pages.dev/obby-dumb-or-genius-iq-test.html)
- [SCHOOLBOY RUNAWAY ROOM ESCAPE](https://skillplay.github.io/schoolboy-runaway-room-escape.html)
- [HEAD SOCCER ARENA](https://skillplay.github.io/head-soccer-arena.html)
- [INDEX7](https://themindplay.github.io/index7.html)
- [SWORDEDIO SPIN AND RUB](https://iskillplay.web.app/swordedio-spin-and-rub.html)
- [CLOSED CITY](https://iskillplay.web.app/closed-city.html)
- [FREECELL](https://iskillquest.pages.dev/freecell.html)
- [MAZE ESCAPE CRAFT MAN](https://iskillquest.pages.dev/maze-escape-craft-man.html)
- [KNOCK AND RUN 100 DOORS ESCAPE](https://iskillplay.web.app/knock-and-run-100-doors-escape.html)
- [FROG KNIGHT](https://themindskillplayplay.pages.dev/frog-knight.html)
- [GUN SHOOTING RANGE](https://theskillquest.pages.dev/gun-shooting-range.html)
- [CRAZY BAR BRAWL](https://skillplay.github.io/crazy-bar-brawl.html)
- [CATEGORY ADVENTURE 5](https://iskillquest.pages.dev/category-adventure-5.html)
- [RADIANT RUSH](https://iskillplay.web.app/radiant-rush.html)
- [LABUBU COLORING ADVENTURE](https://iskillquest.pages.dev/labubu-coloring-adventure.html)
- [CATEGORY BALL175](https://iskillquest.pages.dev/category-ball175.html)
- [BASKET SWAP](https://iskillplay.web.app/basket-swap.html)
- [RAINBOW FRIENDS HIDE AND SEEK](https://skillplay.github.io/rainbow-friends-hide-and-seek.html)
- [SUPERMARKET MANAGER SIMULATOR](https://iskillplay.web.app/supermarket-manager-simulator.html)
- [WE ARE IN A SIMULATION SIMULATOR](https://themindplay.pages.dev/we-are-in-a-simulation-simulator.html)
- [HOW TO DRESS YOUR DRAGON](https://skillplay.github.io/how-to-dress-your-dragon.html)
- [INDEX4](https://iskillplay.web.app/index4.html)
- [GLOVES GROW RUSH](https://skillplay.github.io/gloves-grow-rush.html)
- [HAND OVER HAND](https://iskillquest.pages.dev/hand-over-hand.html)
- [ASMR TATTOO TREATMENT](https://iskillquest.pages.dev/asmr-tattoo-treatment.html)
- [OKAY](https://skillplay.github.io/okay.html)
- [CAR COLLISION MASTER](https://iskillplay.web.app/car-collision-master.html)
- [DRAW BRIDGE BRAIN GAME](https://thelearnquester.web.app/draw-bridge-brain-game.html)
- [FALLING ART RAGDOLL SIMULATOR](https://skillplay.github.io/falling-art-ragdoll-simulator.html)
- [CONQ](https://iskillquest.pages.dev/conq.html)
- [LOVIE CHICS SPRING BREAK FASHION](https://studyquests.github.io/lovie-chics-spring-break-fashion.html)
- [CATEGORY PROXY LIST](https://thelearnquester.web.app/category-proxy-list.html)
- [2248 BLAST](https://theskillquest.pages.dev/2248-blast.html)
- [HEROIC KNIGHT](https://quizverses.pages.dev/heroic-knight.html)
- [CATEGORY SOLITAIRE27](https://studyquesthub.web.app/category-solitaire27.html)
- [ASMR PET TREATMENT](https://iskillplay.web.app/asmr-pet-treatment.html)
- [BANANA FARM](https://iskillplay.web.app/banana-farm.html)
- [REVOXEL 3D VOXEL RPG SHOOTER](https://studyplayings.pages.dev/revoxel-3d-voxel-rpg-shooter.html)
- [CUTE SHEEP SKYBLOCK](https://studyquests.github.io/cute-sheep-skyblock.html)
- [TOBININ](https://studyplayings.pages.dev/tobinin.html)
- [BLOCK COLOR PUZZLE BLAST](https://themindplay.pages.dev/block-color-puzzle-blast.html)
- [SOUL NOT FOUND](https://thelearnquester.web.app/soul-not-found.html)
- [LOVE TILE TRIO](https://studyquesthub.web.app/love-tile-trio.html)
- [ROBOT RUNNER FIGHT](https://studyplaying.github.io/robot-runner-fight.html)
- [GLOVES GROW RUSH](https://quizverses-9d2f2.web.app/gloves-grow-rush.html)
- [DEAD FACES CLONE ONLINE](https://themindskillplayplay.pages.dev/dead-faces-clone-online.html)
- [INDEX34](https://iskillplay.web.app/index34.html)
- [WORLD CUP 2026 SOCCER GAME](https://theskillquest.pages.dev/world-cup-2026-soccer-game.html)
- [ROAD CHASE SHOOTER REALISTIC GUNS](https://skillplay.github.io/road-chase-shooter-realistic-guns.html)
- [TAPE SORT 3D](https://quizverses.github.io/tape-sort-3d.html)
- [K WEDDING DREAM](https://quizverses.github.io/k-wedding-dream.html)
- [THE WALKING DEADBLOCKS](https://quizverses.github.io/the-walking-deadblocks.html)
- [FALL BEAN 2](https://themindplay.pages.dev/fall-bean-2.html)
- [CATEGORY GITHUB IO](https://thelearnquester.web.app/category-github-io.html)
- [PETS VS BEES](https://iskillquest.pages.dev/pets-vs-bees.html)
- [SOLITAIRE KLONDIKE](https://iskillquest.pages.dev/solitaire-klondike.html)
- [CATEGORY AVOID295](https://iskillquest.pages.dev/category-avoid295.html)
- [COLOR BRAIN TEST GAMES](https://skillplay.github.io/color-brain-test-games.html)
- [INDEX34](https://quizverses.github.io/index34.html)
- [K POP HUNTERS VALENTINE STYLE](https://iskillplay.web.app/k-pop-hunters-valentine-style.html)
- [BLOONS SURVIVALIO](https://iskillquest.pages.dev/bloons-survivalio.html)
- [BADLAND](https://studyquests.github.io/badland.html)
- [MUKI WIZARD](https://thelearnquester.web.app/muki-wizard.html)
- [REVERSI](https://studyplayings.pages.dev/reversi.html)
- [BONE DOCTOR SHOULDER CASE](https://skillplay.github.io/bone-doctor-shoulder-case.html)
- [CATEGORY IDLE](https://thelearnquester.web.app/category-idle.html)
- [DINO DIGG](https://skillplay.github.io/dino-digg.html)
- [CATEGORY BASKETBALL32](https://themindplay.github.io/category-basketball32.html)
- [NUMBER BUBBLE SHOOTER WILD WEST](https://theskillquest.pages.dev/number-bubble-shooter-wild-west.html)
- [SLENDERMAN BACK TO SCHOOL](https://learnquester.github.io/slenderman-back-to-school.html)
