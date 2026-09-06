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
- [NONOGRAM DAILY](https://themindplay.pages.dev/nonogram-daily.html)
- [MERGE SHOOTER](https://studyquests.github.io/merge-shooter.html)
- [INDEX14](https://studyquests.pages.dev/index14.html)
- [ANIMALS MERGE](https://studyplayings.web.app/animals-merge.html)
- [CATEGORY CASUAL 4](https://thelearnquester.web.app/category-casual-4.html)
- [CATEGORY PUZZLE](https://learnquesters.pages.dev/category-puzzle.html)
- [CATEGORY IO 2](https://thelearnquesters.pages.dev/category-io-2.html)
- [SUPER DOG HERO DASH](https://thelearnquesters.pages.dev/super-dog-hero-dash.html)
- [PRISMROLL 3D](https://learnquesters.pages.dev/prismroll-3d.html)
- [MY ARCADE CENTER](https://learnquesters.pages.dev/my-arcade-center.html)
- [OBBY VS ZOMBIES](https://learnquesters.pages.dev/obby-vs-zombies.html)
- [INDEX18](https://thelearnquesters.pages.dev/index18.html)
- [COLOR HOOP SORT](https://learnquesters.pages.dev/color-hoop-sort.html)
- [K POP HUNTERS VALENTINE STYLE](https://learnquesters.pages.dev/k-pop-hunters-valentine-style.html)
- [CATEGORY MOBILE2 112](https://learnquesters.pages.dev/category-mobile2-112.html)
- [INDEX14](https://thelearnquesters.pages.dev/index14.html)
- [INDEX15](https://thelearnquesters.pages.dev/index15.html)
- [CATEGORY CONTROLLER](https://learnquesters.pages.dev/category-controller.html)
- [CATEGORY ART](https://thelearnquesters.pages.dev/category-art.html)
- [CATEGORY FPS GAMES](https://thelearnquesters.pages.dev/category-fps-games.html)
- [PRISON MASTER ESCAPE JOURNEY](https://learnquesters.pages.dev/prison-master-escape-journey.html)
- [SUDOKU GURU CLASSIC SUDOKU](https://learnquesters.pages.dev/sudoku-guru-classic-sudoku.html)
- [CATEGORY THINKY 2](https://learnquesters.pages.dev/category-thinky-2.html)
- [INDEX3](https://thelearnquesters.pages.dev/index3.html)
- [CATEGORY COLLECT566](https://thelearnquesters.pages.dev/category-collect566.html)
- [INDEX39](https://thelearnquesters.pages.dev/index39.html)
- [CATEGORY BATTLE 3](https://thelearnquesters.pages.dev/category-battle-3.html)
- [INDEX26](https://thelearnquesters.pages.dev/index26.html)
- [CATEGORY CARE](https://thelearnquesters.pages.dev/category-care.html)
- [CATEGORY ADVENTURE 3](https://thelearnquesters.pages.dev/category-adventure-3.html)
- [CATEGORY BIKE](https://thelearnquesters.pages.dev/category-bike.html)
- [CATEGORY COLLECT600](https://thelearnquesters.pages.dev/category-collect600.html)
- [SPRUNKI TORCHES MAZE](https://learnquesters.pages.dev/sprunki-torches-maze.html)
- [CRAFT DRILL](https://learnquesters.pages.dev/craft-drill.html)
- [STICKER PUZZLE BOOK](https://learnquesters.pages.dev/sticker-puzzle-book.html)
- [INDEX9](https://thelearnquesters.pages.dev/index9.html)
- [XIBLBA MATCH](https://learnquesters.pages.dev/xiblba-match.html)
- [CATEGORY DRESS UP](https://learnquesters.pages.dev/category-dress-up.html)
- [TRUE LOVE CALCULATOR NZW](https://learnquesters.pages.dev/true-love-calculator-nzw.html)
- [CATEGORY FOOTBALL](https://thelearnquesters.pages.dev/category-football.html)
- [CATEGORY FLASH](https://thelearnquesters.pages.dev/category-flash.html)
- [ANIMAL RACING IDLE PARK](https://learnquesters.pages.dev/animal-racing-idle-park.html)
- [VSCO GIRL AESTHETIC](https://learnquesters.pages.dev/vsco-girl-aesthetic.html)
- [CATEGORY DESTROY256](https://thelearnquesters.pages.dev/category-destroy256.html)
- [CATEGORY BUSINESS137](https://thelearnquesters.pages.dev/category-business137.html)
- [TANGLE MASTER 3D](https://learnquesters.pages.dev/tangle-master-3d.html)
- [SAVE HER TOUR](https://learnquesters.pages.dev/save-her-tour.html)
- [CATEGORY SURVIVAL366](https://learnquesters.pages.dev/category-survival366.html)
- [WIPE INSIGHT MASTER](https://learnquesters.pages.dev/wipe-insight-master.html)
- [OBSTACLE CAR DRIVING](https://learnquesters.pages.dev/obstacle-car-driving.html)
- [SNAKEMAXX](https://learnquesters.pages.dev/snakemaxx.html)
- [BLOCK PARKOUR TRIALS](https://learnquesters.pages.dev/block-parkour-trials.html)
- [CATEGORY CONTROLLER 2](https://thelearnquesters.pages.dev/category-controller-2.html)
- [PATH ICE](https://learnquesters.pages.dev/path-ice.html)
- [HOBO SPEEDSTER](https://learnquesters.pages.dev/hobo-speedster.html)
- [HIDDEN OBJECT FARM ADVENTURE](https://learnquesters.pages.dev/hidden-object-farm-adventure.html)
- [IDLE RESTAURANT TYCOON](https://learnquesters.pages.dev/idle-restaurant-tycoon.html)
- [CATEGORY RACING DRIVING](https://learnquesters.pages.dev/category-racing-driving.html)
- [CATEGORY FLASH 3](https://thelearnquesters.pages.dev/category-flash-3.html)
- [BUILD YOUR AQUARIUM](https://learnquesters.pages.dev/build-your-aquarium.html)
- [THE SORT AGENCY](https://learnquesters.pages.dev/the-sort-agency.html)
- [HERITAGE MAHJONG CLASSIC](https://learnquesters.pages.dev/heritage-mahjong-classic.html)
- [SANDSTORM COVERT OPS](https://learnquesters.pages.dev/sandstorm-covert-ops.html)
- [CATEGORY BLOCK94](https://learnquester.pages.dev/category-block94.html)
- [CATEGORY CRASH32](https://thelearnquesters.pages.dev/category-crash32.html)
- [2048 SORT FACTORY](https://learnquester.pages.dev/2048-sort-factory.html)
- [CATEGORY ARCHERY](https://learnquester.pages.dev/category-archery.html)
- [AMMO RUSH MASTER](https://learnquesters.pages.dev/ammo-rush-master.html)
- [CATEGORY CASUAL 3](https://learnquesters.pages.dev/category-casual-3.html)
- [SCREW PUZZLE](https://thelearnquesters.pages.dev/screw-puzzle.html)
- [CATEGORY TANK58](https://thelearnquesters.pages.dev/category-tank58.html)
- [POPPING SUSHI](https://thelearnquesters.pages.dev/popping-sushi.html)
- [JELLY MATH 3D](https://learnquesters.pages.dev/jelly-math-3d.html)
- [SUPER CLONER 3D](https://thelearnquesters.pages.dev/super-cloner-3d.html)
- [EASTER STYLE JUNCTION EGG HUNT EXTRAVAGANZA](https://learnquester.pages.dev/easter-style-junction-egg-hunt-extravaganza.html)
- [MATH KING MATH SKILL GAME](https://studyquests.pages.dev/math-king-math-skill-game.html)
- [SPRUNKI COLORING BOOK](https://iskillquest.pages.dev/sprunki-coloring-book.html)
- [WOOL SORTING](https://theskillquest.pages.dev/wool-sorting.html)
- [CATEGORY DRESS UP 3](https://themindplay.pages.dev/category-dress-up-3.html)
- [ELEMENTAL DRESSUP MAGIC](https://studyquests.github.io/elemental-dressup-magic.html)
- [STICK KILL 3D](https://theskillquest.pages.dev/stick-kill-3d.html)
- [CATEGORY SHOP](https://themindplay.pages.dev/category-shop.html)
- [PECKSHOT](https://themindplay.pages.dev/peckshot.html)
- [CATEGORY MAKEUP51](https://themindplay.pages.dev/category-makeup51.html)
- [SLINGSHOT MASTER](https://theskillquest.pages.dev/slingshot-master.html)
- [CHILDREN HAPPY FARM DUDU](https://studyquests.github.io/children-happy-farm-dudu.html)
- [SLENDER BOY ESCAPE ROBBIE](https://thelearnquesters.pages.dev/slender-boy-escape-robbie.html)
- [TIMEWALKER SURVIVE](https://theskillquest.pages.dev/timewalker-survive.html)
- [SUGAR HEROES](https://themindplay.github.io/sugar-heroes.html)
- [CATEGORY MATCH 3117](https://learnquester.pages.dev/category-match-3117.html)
- [CANDY MONSTER RAFFI](https://theskillquest.pages.dev/candy-monster-raffi.html)
- [CLOAK MASTER SHOOTER RUN](https://theskillquest.pages.dev/cloak-master-shooter-run.html)
- [PET DOCTOR BUSINESS TYCOON PET CARE GAME](https://iskillquest.pages.dev/pet-doctor-business-tycoon-pet-care-game.html)
- [RAINBOW FRIENDS SURVIVAL](https://thelearnquesters.pages.dev/rainbow-friends-survival.html)
- [LOGIC SLIDE](https://themindplay.github.io/logic-slide.html)
- [IDLE BASEBALL TYCOON](https://studyquests.github.io/idle-baseball-tycoon.html)
- [LABUBU MERGE](https://studyplaying.github.io/labubu-merge.html)
- [CATCH THIEF](https://studyquests.github.io/catch-thief.html)
- [ZOMBIE RAFT](https://studyquests.pages.dev/zombie-raft.html)
- [WATER SORT](https://iskillquest.pages.dev/water-sort.html)
- [CATEGORY FASHION105](https://themindplay.pages.dev/category-fashion105.html)
- [CANDY RIDDLES](https://themindplay.github.io/candy-riddles.html)
- [SWEET BUSINESS OF CATS CAKES](https://theskillquest.pages.dev/sweet-business-of-cats-cakes.html)
- [CATEGORY 2D1 070](https://learnquester.pages.dev/category-2d1-070.html)
- [CATEGORY PARKOUR55](https://themindplay.github.io/category-parkour55.html)
- [CATEGORY COLLECT](https://studyquests.pages.dev/category-collect.html)
- [GRAVITY SPEED RUN](https://studyquests.github.io/gravity-speed-run.html)
- [CATEGORY MOBILE2 112](https://thelearnquesters.pages.dev/category-mobile2-112.html)
- [SAND BLOCK BLAST](https://studyquests.github.io/sand-block-blast.html)
- [CANDY MATCH 4](https://studyquests.github.io/candy-match-4.html)
- [CATEGORY FOOD](https://themindplay.pages.dev/category-food.html)
- [CATEGORY ADVENTURE 5](https://studyplaying.github.io/category-adventure-5.html)
- [CATEGORY PARTY23](https://thelearnquesters.pages.dev/category-party23.html)
- [CATEGORY ANIMAL215](https://iskillquest.pages.dev/category-animal215.html)
- [CRUSH IT ALL](https://studyquests.pages.dev/crush-it-all.html)
- [STRIKE IT](https://iskillquest.pages.dev/strike-it.html)
- [CATEGORY BUBBLE SHOOTER GAMES](https://theskillquest.pages.dev/category-bubble-shooter-games.html)
- [FALLING DUMMY](https://studyquests.github.io/falling-dummy.html)
- [BARREL ROLLER AMAZING RUNNER](https://themindplay.github.io/barrel-roller-amazing-runner.html)
- [LAST TO LEAVE CIRCLE OBBY](https://themindzone.pages.dev/last-to-leave-circle-obby.html)
- [CATEGORY MEME BLOXY24](https://learnquester.pages.dev/category-meme-bloxy24.html)
- [COLOR RACE OBBY](https://themindplay.github.io/color-race-obby.html)
- [CATEGORY HUB](https://learnquester.pages.dev/category-hub.html)
- [ANIMALON EPIC MONSTERS BATTLE](https://themindplay.github.io/animalon-epic-monsters-battle.html)
- [ITALIAN BRAINROT CHALLENGE](https://themindplay.github.io/italian-brainrot-challenge.html)
- [CATEGORY CLASSIC98](https://themindzone.pages.dev/category-classic98.html)
- [TRAFFIC TAP PUZZLE](https://iskillquest.pages.dev/traffic-tap-puzzle.html)
- [CATEGORY HORROR90](https://learnquester.pages.dev/category-horror90.html)
- [TRIPLE CUPS](https://learnquesters.pages.dev/triple-cups.html)
- [SNAKE IO](https://themindzone.pages.dev/snake-io.html)
