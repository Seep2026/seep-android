#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./scripts/replace-app-icons.sh \
    --foreground /absolute/path/to/logo_foreground.png \
    [--legacy /absolute/path/to/logo_legacy.png] \
    [--content-scale 0.5] \
    [--background-color '#RRGGBB'] \
    [--no-debug-sync]

What this script updates:
  1) src/main/res/mipmap-*/ic_launcher_foreground.png
  2) src/main/res/mipmap-*/ic_launcher.png
  3) src/debug/res/mipmap-*/ic_launcher_foreground.png (default: synced from main)
  4) src/debug/res/mipmap-*/ic_launcher.png (default: synced from main)
  5) src/main/res/values/ic_launcher_background.xml (only if --background-color is set)

Notes:
  - --foreground should usually be a transparent PNG (logo only).
  - --legacy is optional. If omitted, --foreground will also be used for ic_launcher.png.
  - --content-scale controls visual size of the logo in the icon canvas (default: 1.0).
    Example: 0.5 means the logo's max side is 50% of the icon canvas.
  - This script is intended for macOS and uses `sips` and/or `magick`.
EOF
}

if ! command -v sips >/dev/null 2>&1 && ! command -v magick >/dev/null 2>&1; then
  echo "Error: neither 'sips' nor 'magick' found. Install ImageMagick or run on macOS with sips." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

FOREGROUND=""
LEGACY=""
BACKGROUND_COLOR=""
CONTENT_SCALE="1.0"
SYNC_DEBUG=true

while [[ $# -gt 0 ]]; do
  case "$1" in
    --foreground)
      FOREGROUND="${2:-}"
      shift 2
      ;;
    --legacy)
      LEGACY="${2:-}"
      shift 2
      ;;
    --background-color)
      BACKGROUND_COLOR="${2:-}"
      shift 2
      ;;
    --content-scale)
      CONTENT_SCALE="${2:-}"
      shift 2
      ;;
    --no-debug-sync)
      SYNC_DEBUG=false
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${FOREGROUND}" ]]; then
  echo "Error: --foreground is required." >&2
  usage
  exit 1
fi

if [[ ! -f "${FOREGROUND}" ]]; then
  echo "Error: foreground file not found: ${FOREGROUND}" >&2
  exit 1
fi

if [[ -z "${LEGACY}" ]]; then
  LEGACY="${FOREGROUND}"
fi

if [[ ! -f "${LEGACY}" ]]; then
  echo "Error: legacy file not found: ${LEGACY}" >&2
  exit 1
fi

if [[ -n "${BACKGROUND_COLOR}" ]]; then
  if [[ ! "${BACKGROUND_COLOR}" =~ ^#[0-9A-Fa-f]{6}$ ]]; then
    echo "Error: --background-color must be in #RRGGBB format." >&2
    exit 1
  fi
fi

if ! [[ "${CONTENT_SCALE}" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "Error: --content-scale must be a decimal number in range (0, 1]." >&2
  exit 1
fi
if ! awk -v s="${CONTENT_SCALE}" 'BEGIN { exit !(s > 0 && s <= 1) }'; then
  echo "Error: --content-scale must be in range (0, 1]." >&2
  exit 1
fi

densities=(mdpi hdpi xhdpi xxhdpi xxxhdpi)
legacy_sizes=(48 72 96 144 192)
foreground_sizes=(108 162 216 324 432)

resize_png() {
  local src="$1"
  local size="$2"
  local out="$3"
  local scaled_size
  scaled_size="$(awk -v sz="${size}" -v sc="${CONTENT_SCALE}" 'BEGIN {
    v = int(sz * sc + 0.5);
    if (v < 1) v = 1;
    print v
  }')"
  mkdir -p "$(dirname "$out")"

  # Prefer ImageMagick because it supports SVG and preserves aspect ratio
  # while fitting into the target square.
  if command -v magick >/dev/null 2>&1; then
    magick "${src}" \
      -background none \
      -resize "${scaled_size}x${scaled_size}" \
      -gravity center \
      -extent "${size}x${size}" \
      "PNG32:${out}"
    return
  fi

  # Fallback for macOS environments without ImageMagick.
  # 1) Scale to desired visual size while preserving aspect ratio.
  # 2) Pad to final square canvas.
  local tmp
  tmp="$(mktemp /tmp/replace-icon.XXXXXX.png)"
  sips -s format png --resampleHeightWidthMax "${scaled_size}" "${src}" --out "${tmp}" >/dev/null
  sips -s format png --padToHeightWidth "${size}" "${size}" "${tmp}" --out "${out}" >/dev/null
  rm -f "${tmp}"
}

for i in "${!densities[@]}"; do
  d="${densities[$i]}"
  lsize="${legacy_sizes[$i]}"
  fsize="${foreground_sizes[$i]}"

  resize_png "${LEGACY}" "${lsize}" "${ROOT_DIR}/src/main/res/mipmap-${d}/ic_launcher.png"
  resize_png "${FOREGROUND}" "${fsize}" "${ROOT_DIR}/src/main/res/mipmap-${d}/ic_launcher_foreground.png"
done

if [[ "${SYNC_DEBUG}" == true ]]; then
  for d in "${densities[@]}"; do
    mkdir -p "${ROOT_DIR}/src/debug/res/mipmap-${d}"
    cp "${ROOT_DIR}/src/main/res/mipmap-${d}/ic_launcher.png" \
      "${ROOT_DIR}/src/debug/res/mipmap-${d}/ic_launcher.png"
    cp "${ROOT_DIR}/src/main/res/mipmap-${d}/ic_launcher_foreground.png" \
      "${ROOT_DIR}/src/debug/res/mipmap-${d}/ic_launcher_foreground.png"
  done
fi

if [[ -n "${BACKGROUND_COLOR}" ]]; then
  cat > "${ROOT_DIR}/src/main/res/values/ic_launcher_background.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">${BACKGROUND_COLOR}</color>
</resources>
EOF
fi

echo "Done."
echo "Applied content scale: ${CONTENT_SCALE}"
echo "Updated main icon assets in: ${ROOT_DIR}/src/main/res/mipmap-*"
if [[ "${SYNC_DEBUG}" == true ]]; then
  echo "Synced debug icon assets in: ${ROOT_DIR}/src/debug/res/mipmap-*"
fi
if [[ -n "${BACKGROUND_COLOR}" ]]; then
  echo "Updated launcher background color to ${BACKGROUND_COLOR}"
fi
echo "Next step: run ./gradlew assembleFossDebug"
