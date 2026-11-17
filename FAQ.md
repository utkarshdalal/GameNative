# FAQ

## Why are some features paid?

GameNative is free and complete. Every setting is in the app, known-good configs are applied automatically, and help on [Discord](https://discord.gg/2hKv4VfZfE) is free.

Building GameNative is full-time work. Paid features are how we fund it, so we can keep improving GameNative for everyone, including people who never pay.

Everything our paid features do, you can do yourself, and the steps are below. Membership pays for us doing the work for you, with what we've learned:

- **Optimize** tunes each game on your device: it tries one change at a time, measures it while you play, keeps what helps and undoes what doesn't.
- **AI help** works out why a game won't run and fixes it. It reads your logs, traces errors through the source code of GameNative, Wine, DXVK, FEX and the drivers, and then does the fix in the app: it changes your settings (with one-tap restore), patches the game's files, or builds a new version of Turnip, DXVK, vkd3d-proton, FEX, Box64 or Proton with the fix and installs it for you.

You can try both for free.

## How can I optimize a game myself?

Optimize only changes things you can change yourself.

**1. Find what's holding the game back.** Turn on the Performance HUD in the Quick Menu and add **Limiting factor**. It shows CPU, GPU or heat. For the full picture, use **Export last run metrics** in the game's menu: it shares the same performance data Optimize uses. Read it yourself, or give it to your own AI.

**2. Change what matters for that limit:**

- **Game settings.** Lower shadows, effects, view distance and other settings in the game's own options menu, or in its config files.
- **Resolution.** Lower **Screen Size** in the container settings, and use **FSR** in Screen Effects to upscale.
- **Frame rate cap.** Set the **FPS limiter** to a rate the game can always hold. This is the best way to cut heat.
- **Underclocking.** On supported devices, the **Power Control** tab in the Quick Menu caps CPU and GPU clocks. This cuts heat, at some cost to frame rate.
- **Cores.** Change **Processor Affinity** in the container settings, or turn on **Game Pinning** in Power Control to keep the game on the fast cores.
- **Environment variables.** Under **Environment Variables** in the container settings: `DXVK_CONFIG` can lower texture detail, tessellation and shader compile threads; other variables change how Wine reports memory and CPU cores to the game; and any Turnip driver option, such as `heap_memory_percent`, can be set as a variable of the same name.
- **Components.** Try other **Wine**, **DXVK**, **FEXCore** and **Box64** versions, and other **FEXCore Presets**. Faster presets can make some games crash, so change one back if anything breaks.

**3. Test properly.** Change one thing at a time. Play each test for about 10 minutes, in the same place in the game, and let the device cool down between tests. Devices take that long to heat up, so short tests look better than the game really runs. Do this for every game on every device, and again when new builds come out.

That's the work Optimize does for you.

## Can I use my own AI to fix a game?

Yes, for free. Choose **Get AI help** on the game, play until the problem happens, and close the game. Then tap **Share instead** to send the same logs our AI reads to your own AI.

Then do the fix yourself:

- **Settings:** change them in the container settings.
- **Game files:** edit them, or use **Import Files** to put a fixed file in place.
- **Components:** GameNative's builds of Turnip, DXVK, vkd3d-proton, FEX, Box64 and Proton are open source. Patch and build them yourself, then install your build in the app, for example with **Import .wcp Package**.

## Why haven't you shared the algorithms behind Optimize and AI help?

All of GameNative's code is public. What we keep private is our know-how: which settings help each game, and how to diagnose and fix each kind of failure.

That know-how is what makes the paid features worth paying for, and the paid features are what fund GameNative's development. If we published it, other apps, including closed-source paid ones, could ship it as their own. Nobody would need to pay us, and the work that keeps GameNative improving for everyone would stop.

We want to keep working on GameNative and improving it for all our players. Paid features are the way we've found to do that while staying true to our open-source values and to our aim of making PC gaming accessible to everyone, without the need for expensive hardware.

Fixes still reach everyone: when AI help builds a fixed component, it opens a public pull request anyone can use.

## What we believe

- The app is free and complete, and stays that way.
- Everything you need to do the work yourself stays free. We charge for doing it for you.
- Nothing free today will become paid.
- Fixes to Proton, FEX and the graphics drivers go to everyone, and back to those projects.
- Your data stays private. We don't sell it or publish it.
