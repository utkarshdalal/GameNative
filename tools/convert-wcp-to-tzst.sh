#!/usr/bin/env bash
# convert-wcp-to-tzst.sh
# Converts a FEX .wcp release (XZ-compressed tar) into a fexcore .tzst file
# compatible with the GameNative app assets format.
#
# Usage: ./tools/convert-wcp-to-tzst.sh <input.wcp> <output.tzst>
# Example: ./tools/convert-wcp-to-tzst.sh FEX-2603.wcp app/src/main/assets/fexcore/fexcore-2603.tzst

set -euo pipefail

INPUT="${1:-}"
OUTPUT="${2:-}"

if [[ -z "$INPUT" || -z "$OUTPUT" ]]; then
    echo "Usage: $0 <input.wcp> <output.tzst>"
    exit 1
fi

if [[ ! -f "$INPUT" ]]; then
    echo "Error: input file '$INPUT' not found"
    exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"

echo "Converting '$INPUT' -> '$OUTPUT' ..."

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

EXTRACT_DIR="$TMPDIR/extract"
PACK_DIR="$TMPDIR/pack"
mkdir -p "$EXTRACT_DIR" "$PACK_DIR"

echo "  Extracting archive..."
xz -dc "$INPUT" | tar -x -C "$EXTRACT_DIR"

for dll in libwow64fex.dll libarm64ecfex.dll; do
    src="$(find "$EXTRACT_DIR" -type f -name "$dll" -print -quit)"
    if [[ -z "$src" ]]; then
        echo "Error: could not find $dll in $INPUT"
        exit 1
    fi
    cp "$src" "$PACK_DIR/$dll"
done

echo "  Repacking as zstd tar..."
tar -c -C "$PACK_DIR" . \
    | zstd -19 -o "$OUTPUT" --force

echo "Done: $(du -sh "$OUTPUT" | cut -f1)  $OUTPUT"
