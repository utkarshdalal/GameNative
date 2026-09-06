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
- [X TO Y ALMOST IMPOSSIBLE](https://studyquests.github.io/x-to-y-almost-impossible.html)
- [BEAR VS HUMANS](https://quizverses.github.io/bear-vs-humans.html)
- [CATEGORY DRESS UP 3](https://quizverses.github.io/category-dress-up-3.html)
- [SQUIRREL WITH A GUN](https://quizverses.github.io/squirrel-with-a-gun.html)
- [ASMR BEAUTY CLINIC](https://quizverses.github.io/asmr-beauty-clinic.html)
- [CHARGER CITY DRIVER](https://quizverses.github.io/charger-city-driver.html)
- [CATEGORY CAR](https://quizverses.github.io/category-car.html)
- [CATEGORY CASUAL 3](https://quizverses.github.io/category-casual-3.html)
- [COMBINATIONS DAILY](https://quizverses.github.io/combinations-daily.html)
- [TILE FARM STORY MATCHING GAME](https://quizverses.github.io/tile-farm-story-matching-game.html)
- [OVERFLOWING PALETTE](https://quizverses.github.io/overflowing-palette.html)
- [SWEET BUSINESS OF CATS CAKES](https://quizverses.github.io/sweet-business-of-cats-cakes.html)
- [CUBICA](https://quizverses.github.io/cubica.html)
- [DELIVERY NOW](https://quizverses.github.io/delivery-now.html)
- [CATEGORY DEEP IMMERSIVE24](https://quizverses.pages.dev/category-deep-immersive24.html)
- [CLICKER KNIGHTS VS DRAGONS](https://quizverses.github.io/clicker-knights-vs-dragons.html)
- [WOODS OF NEVIA FOREST SURVIVAL](https://quizverses.github.io/woods-of-nevia-forest-survival.html)
- [CATEGORY 3D1 371](https://thequizzone.pages.dev/category-3d1-371.html)
- [TRICKY CASTLE](https://quizverses.github.io/tricky-castle.html)
- [BUBBLE BLASTERS](https://quizverses.github.io/bubble-blasters.html)
- [EMOJI SORT FUN PUZZLE GAME](https://quizverses.github.io/emoji-sort-fun-puzzle-game.html)
- [TANGLE MASTER 3D](https://quizverses.github.io/tangle-master-3d.html)
- [CATEGORY CAR 3](https://quizverses.github.io/category-car-3.html)
- [SORT TILES](https://quizverses.github.io/sort-tiles.html)
- [CATEGORY CASUAL 8](https://quizverses.github.io/category-casual-8.html)
- [KLONDIKE SOLITAIRE](https://quizverses.github.io/klondike-solitaire.html)
- [MERGE BLOCKS 2048](https://quizverses.github.io/merge-blocks-2048.html)
- [CATEGORY CASUAL 7](https://quizverses.github.io/category-casual-7.html)
- [IDLE ARCHER TOWER DEFENSE RPG](https://quizverses.github.io/idle-archer-tower-defense-rpg.html)
- [CATEGORY CASUAL 4](https://quizverses.github.io/category-casual-4.html)
- [CATEGORY CASUAL971](https://quizverses.github.io/category-casual971.html)
- [GEOMETRY ARROW 2](https://quizverses.github.io/geometry-arrow-2.html)
- [CATEGORY CARTOON76](https://quizverses.github.io/category-cartoon76.html)
- [CATEGORY CAN T STOP PLAYING215](https://quizverses.github.io/category-can-t-stop-playing215.html)
- [FASHION CHALLENGE CATWALK RUN](https://quizverses.github.io/fashion-challenge-catwalk-run.html)
- [ARTILLERY VS TANKS](https://quizverses.github.io/artillery-vs-tanks.html)
- [HIDE AND SEEK BLUE MONSTER](https://quizverses.github.io/hide-and-seek-blue-monster.html)
- [MATH OBBY](https://quizverses.github.io/math-obby.html)
- [COLOR BLOCK SORT](https://quizverses.github.io/color-block-sort.html)
- [MONSTERELLA FANTASY MAKEUP](https://quizverses.github.io/monsterella-fantasy-makeup.html)
- [ZOO RESTAURANT](https://quizverses.github.io/zoo-restaurant.html)
- [ARMY DEFENCE DINO SHOOT](https://quizverses.github.io/army-defence-dino-shoot.html)
- [BACKGAMMON DUEL](https://quizverses.github.io/backgammon-duel.html)
- [U SHAPE PUZZLE](https://quizverses.github.io/u-shape-puzzle.html)
- [CATEGORY CONTROLLER](https://quizverses.github.io/category-controller.html)
- [BLOCKSSS](https://quizverses.github.io/blocksss.html)
- [K POP HUNTERS VALENTINE STYLE](https://quizverses.github.io/k-pop-hunters-valentine-style.html)
- [MATH RUNNER](https://quizverses.github.io/math-runner.html)
- [TOY ASSEMBLY 3D](https://quizverses.github.io/toy-assembly-3d.html)
- [KAWAII FRIENDS TILES MATCHER](https://quizverses.github.io/kawaii-friends-tiles-matcher.html)
- [SECRETS OF CHARMLAND](https://quizverses.github.io/secrets-of-charmland.html)
- [KIDS COLORING](https://quizverses.github.io/kids-coloring.html)
- [PET ME MAZE](https://quizverses.github.io/pet-me-maze.html)
- [BLOCK BLAST JEWEL PUZZLE](https://studyplaying.github.io/block-blast-jewel-puzzle.html)
- [MERGE FELLAS ONLINE](https://studyplaying.github.io/merge-fellas-online.html)
- [BULLET SUPERHERO](https://quizverses.github.io/bullet-superhero.html)
- [CATEGORY CONTROLLER](https://studyplaying.github.io/category-controller.html)
- [CATEGORY SCHOOL](https://learnquester.github.io/category-school.html)
- [INDEX23](https://quizverses.github.io/index23.html)
- [PRIVACY](https://cryptotify.netlify.app/privacy.html)
- [SHAPE TRANSFORMING SHIFTING RUN](https://studyquesthub.web.app/shape-transforming-shifting-run.html)
- [CATEGORY TURN BASED30](https://studyplaying.github.io/category-turn-based30.html)
- [SCARY BABY YELLOW GAME](https://studyquests.pages.dev/scary-baby-yellow-game.html)
- [CATEGORY RUNNING107](https://studyplaying.github.io/category-running107.html)
- [INDEX19](https://quizverses.github.io/index19.html)
- [INDEX7](https://quizverses.github.io/index7.html)
- [CATEGORY MAHJONG](https://studyquests.pages.dev/category-mahjong.html)
- [HIDDEN OBJECT ROOMS EXPLORATION](https://studyplaying.github.io/hidden-object-rooms-exploration.html)
- [BOUNCY BLOB RACE OBSTACLE COURSE](https://quizverses.github.io/bouncy-blob-race-obstacle-course.html)
- [GEOMETRY VIBES MONSTER](https://quizverses.github.io/geometry-vibes-monster.html)
- [CLASSIC LABYRINTH 3D MAZE](https://quizverses.github.io/classic-labyrinth-3d-maze.html)
- [BLOCK EATING SIMULATOR](https://studyquests.pages.dev/block-eating-simulator.html)
- [BATTLE ZONE 2D](https://studyquests.pages.dev/battle-zone-2d.html)
- [DEVIL DUCK NOT A TROLL GAME](https://quizverses.github.io/devil-duck-not-a-troll-game.html)
- [ANIMAL BUS TRAFFIC JAM](https://quizverses.github.io/animal-bus-traffic-jam.html)
- [INDEX20](https://quizverses.github.io/index20.html)
- [VENETIAN LOVE AFFAIR](https://quizverses.github.io/venetian-love-affair.html)
- [INDEX30](https://studyplaying.github.io/index30.html)
- [DEVIL DASH](https://quizverses.github.io/devil-dash.html)
- [SPRUNKI GETS SURGERY](https://quizverses.github.io/sprunki-gets-surgery.html)
- [PRIVACY](https://cryptotify9.onrender.com/privacy.html)
- [GOING BALLS 3D](https://quizverses.github.io/going-balls-3d.html)
- [CUTE CRAFT LAB](https://quizverses.github.io/cute-craft-lab.html)
- [SITEMAP](https://quizverses.github.io/sitemap.html)
- [CHALLENGE YOUR FRIENDS](https://studyplaying.github.io/challenge-your-friends.html)
- [DRAW TO SMASH ZOMBIE](https://studyplaying.github.io/draw-to-smash-zombie.html)
- [ARCHERS RANDOM](https://studyplaying.github.io/archers-random.html)
- [RESTAURANT VIP MASTERCHEF](https://studyplaying.github.io/restaurant-vip-masterchef.html)
- [FIRE BALL AND WATER BALL PARKOUR LOVE BALLS](https://studyplaying.github.io/fire-ball-and-water-ball-parkour-love-balls.html)
- [BUSY BEE HIVE](https://quizverses.github.io/busy-bee-hive.html)
- [EPIC MINE](https://studyquesthub.web.app/epic-mine.html)
- [RED STICKMAN VS CRAFTMANS](https://studyquesthub.web.app/red-stickman-vs-craftmans.html)
- [CATEGORY RPG](https://studyquests.pages.dev/category-rpg.html)
- [CATEGORY CASUAL 5](https://quizverses.github.io/category-casual-5.html)
- [STICKMAN ZOMBIE VS STICKMAN HERO](https://studyplaying.github.io/stickman-zombie-vs-stickman-hero.html)
- [MAHJONG CONNECT MAJONG CLASS](https://studyquests.pages.dev/mahjong-connect-majong-class.html)
- [INDEX11](https://studyquests.pages.dev/index11.html)
- [THE SURVEY](https://quizverses.github.io/the-survey.html)
- [MINDBLOW](https://studyplaying.github.io/mindblow.html)
- [MERGE SQUARES](https://studyquests.pages.dev/merge-squares.html)
- [MAGIC AND WIZARDS MATCH](https://studyplaying.github.io/magic-and-wizards-match.html)
- [HOSPITAL GAME HAPPY CLINIC](https://studyplaying.github.io/hospital-game-happy-clinic.html)
- [MARATHON RACE IO](https://studyquests.pages.dev/marathon-race-io.html)
- [BALL TOWER OF HELL](https://quizverses.github.io/ball-tower-of-hell.html)
- [SANTA GO](https://studyplaying.github.io/santa-go.html)
- [MAHJONG RIDDLES EGYPT](https://quizverses.github.io/mahjong-riddles-egypt.html)
- [ANTISTRESS SIMULATOR OF SEQUINS DIY](https://quizverses.github.io/antistress-simulator-of-sequins-diy.html)
- [CATEGORY COLOR197](https://quizverses.github.io/category-color197.html)
- [UNSCREW THEM ALL](https://quizverses.github.io/unscrew-them-all.html)
- [ZOMBIE OUTBREAK SURVIVE](https://studyplaying.github.io/zombie-outbreak-survive.html)
- [ELYTRA FLIGHT](https://studyplaying.github.io/elytra-flight.html)
- [LAVA JUMP](https://quizverses.github.io/lava-jump.html)
- [PRIVACY](https://brainquests.onrender.com/privacy.html)
- [MOTO RACE CITY](https://studyplaying.github.io/moto-race-city.html)
- [MANYUNYA SAVING THE PRINCESS](https://quizverses.github.io/manyunya-saving-the-princess.html)
- [PANDA KITCHEN IDLE TYCOON](https://quizverses.github.io/panda-kitchen-idle-tycoon.html)
- [SOCCER ARENA X](https://studyquests.github.io/soccer-arena-x.html)
- [FRUIT MATCH](https://studyquests.github.io/fruit-match.html)
- [CATEGORY RPG80](https://studyplaying.github.io/category-rpg80.html)
- [PIZZA PUZZLE](https://quizverses.github.io/pizza-puzzle.html)
- [MAX CRUSHER CRAZY DESTRUCTION AND CAR CRASHES](https://studyquests.github.io/max-crusher-crazy-destruction-and-car-crashes.html)
- [HAWAII MATCH 5](https://quizverses.github.io/hawaii-match-5.html)
- [INDEX3](https://studyplaying.github.io/index3.html)
- [TURBO CAR TRACK](https://studyquesthub.web.app/turbo-car-track.html)
- [BACK 2 SCHOOL MAKEOVER](https://quizverses.pages.dev/back-2-school-makeover.html)
- [2 PLAYER GAMES KIDS KITCHEN](https://studyquests.pages.dev/2-player-games-kids-kitchen.html)
- [CATEGORY CUTE](https://quizverses.github.io/category-cute.html)
- [MERGE NUMBERS](https://studyquests.pages.dev/merge-numbers.html)
- [INDEX13](https://studyplaying.github.io/index13.html)
- [COSMIC AVIATOR](https://studyplaying.github.io/cosmic-aviator.html)
