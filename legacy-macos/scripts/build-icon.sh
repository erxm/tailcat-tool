#!/usr/bin/env bash
set -euo pipefail

TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_SOURCE="$TASK_ROOT/resources/AppIcon.png"
TASK_ICON="$TASK_ROOT/resources/AppIcon.icns"
[[ -f "$TASK_SOURCE" ]] || { printf '%s\n' "Missing icon source: $TASK_SOURCE" >&2; exit 1; }
if [[ -f "$TASK_ICON" && "$TASK_ICON" -nt "$TASK_SOURCE" ]]; then exit 0; fi

TASK_TEMP="$(mktemp -d "${TMPDIR:-/tmp}/tailcat-icon.XXXXXX")"
trap 'rm -rf "$TASK_TEMP"' EXIT
TASK_ICONSET="$TASK_TEMP/AppIcon.iconset"
mkdir -p "$TASK_ICONSET"
for TASK_SIZE in 16 32 128 256 512; do
    /usr/bin/sips -z "$TASK_SIZE" "$TASK_SIZE" "$TASK_SOURCE" --out "$TASK_ICONSET/icon_${TASK_SIZE}x${TASK_SIZE}.png" >/dev/null
    TASK_RETINA="$((TASK_SIZE * 2))"
    /usr/bin/sips -z "$TASK_RETINA" "$TASK_RETINA" "$TASK_SOURCE" --out "$TASK_ICONSET/icon_${TASK_SIZE}x${TASK_SIZE}@2x.png" >/dev/null
done
/usr/bin/iconutil --convert icns --output "$TASK_ICON" "$TASK_ICONSET"
printf '%s\n' "$TASK_ICON"
