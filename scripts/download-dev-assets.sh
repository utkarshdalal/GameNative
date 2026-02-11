#!/bin/bash
# Downloads runtime assets for testing game launching during development.
# The app compiles without these, but needs them to execute games.

set -e

CDN="https://downloads.gamenative.app"
DEST="app/src/main/assets"
FILES=("imagefs_gamenative.txz" "imagefs_patches_gamenative.tzst" "imagefs_bionic.txz")

[ ! -d "$DEST" ] && echo "Error: Run from GameNative root directory" && exit 1

for file in "${FILES[@]}"; do
    [ -f "$DEST/$file" ] && echo "Skipping $file (exists)" && continue
    echo "Downloading $file..."
    curl -fL "$CDN/$file" -o "$DEST/$file" || echo "Failed: $file"
done

echo "Done"
