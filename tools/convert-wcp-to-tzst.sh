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

# Ensure output directory exists
mkdir -p "$(dirname "$OUTPUT")"

echo "Converting '$INPUT' -> '$OUTPUT' ..."

# Decompress XZ, extract only the two DLLs from system32/, strip the
# system32/ path component, then repack as a zstd-compressed tar.
#
# The reference format (fexcore-2601.tzst) contains:
#   ./libwow64fex.dll
#   ./libarm64ecfex.dll
# The .wcp source contains them under system32/, so we use --strip-components=1.

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

echo "  Extracting DLLs from archive..."
xz -dc "$INPUT" \
    | tar -x \
          --strip-components=1 \
          -C "$TMPDIR" \
          system32/libwow64fex.dll \
          system32/libarm64ecfex.dll

echo "  Repacking as zstd tar..."
# Use compression level 19 for smallest output (matches prior releases in size range).
tar -c -C "$TMPDIR" . \
    | zstd -19 -o "$OUTPUT" --force

echo "Done: $(du -sh "$OUTPUT" | cut -f1)  $OUTPUT"
