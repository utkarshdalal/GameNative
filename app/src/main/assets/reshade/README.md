# Bundled ReShade effects

The effect set the app ships. gamescope provides only the ReShade compiler, so without these there is
nothing to select.

## Layout

```
all_shaders.json      every effect the QuickMenu offers: {path, name}, sorted by name
gamenative/           a pack: effects plus the ReShade.fxh they include
```

An entry's `path` is relative to gamescope's `Shaders` directory, so `gamenative/Vivid.fx` resolves to
`<files>/reshade/Shaders/gamenative/Vivid.fx`. `name` is what the QuickMenu shows.

## How it reaches gamescope

At every gamescope launch the launcher rebuilds `<files>/reshade` from this directory — one
`Shaders/<pack>` per subdirectory, the manifest at the root — and symlinks it into the container home
as `~/.local/share/gamescope/reshade`, which is where gamescope looks. The rebuild is unconditional,
so the APK is the only source of truth: a file removed here disappears on device at the next launch.

An effect's `#include`s resolve against its own directory first, then against `Shaders/`, which is why
`ReShade.fxh` sits inside the pack.

## Adding an effect

Drop the `.fx` into a pack directory and add `{path, name}` to `all_shaders.json`. No code change.
An effect that loads an image declares `texture <name> { source = "<file>"; };` and the file goes under
`Textures/`, created on demand.

## Limits

- gamescope's ReShade support is a subset of ReShade 5: single-pass colour effects are safe, multi-pass
  with render targets and compute passes are not guaranteed.
- The depth buffer is not available in this port (`bufready_depth` is always false), so effects that
  sample depth — SSAO, depth of field, depth-based anti-aliasing — cannot work.
- One effect runs at a time; the QuickMenu rows behave as a radio group.

## Provenance

`gamenative/ReShade.fxh` is CC0-1.0 from crosire/reshade-shaders; its SPDX header is retained. The
effects in `gamenative/` are written for GameNative.
