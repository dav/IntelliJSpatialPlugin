#!/usr/bin/env bash
# Build the Spatial plugin, install it into the user-level plugins directory
# for IntelliJ IDEA, and relaunch the IDE so the new version is live.
#
# Overrides (env vars):
#   APP           Path to the IDE .app bundle. Default: /Applications/IntelliJ IDEA.app
#   CONFIG_ROOT   JetBrains config root.       Default: $HOME/Library/Application Support/JetBrains
#   IDE_PRODUCT   Config-dir prefix.           Default: IntelliJIdea   (matches IntelliJIdea2026.1 etc.)
#   PLUGIN_DIR    Top-level dir inside the dist zip. Default: IntelliJSpatialPlugin

set -euo pipefail

APP="${APP:-/Applications/IntelliJ IDEA.app}"
CONFIG_ROOT="${CONFIG_ROOT:-$HOME/Library/Application Support/JetBrains}"
IDE_PRODUCT="${IDE_PRODUCT:-IntelliJIdea}"
PLUGIN_DIR="${PLUGIN_DIR:-IntelliJSpatialPlugin}"

cd "$(dirname "$0")"

echo "▸ Building plugin..."
./gradlew --console=plain buildPlugin

DIST_ZIP="$(ls -1t build/distributions/*.zip 2>/dev/null | head -n1 || true)"
if [[ -z "${DIST_ZIP}" || ! -f "${DIST_ZIP}" ]]; then
  echo "✗ No plugin zip found under build/distributions/." >&2
  exit 1
fi
echo "  built: ${DIST_ZIP}"

CONFIG_DIR="$(ls -1dt "${CONFIG_ROOT}/${IDE_PRODUCT}"* 2>/dev/null | head -n1 || true)"
if [[ -z "${CONFIG_DIR}" ]]; then
  echo "✗ No ${IDE_PRODUCT}* directory under ${CONFIG_ROOT}." >&2
  echo "  Launch ${APP##*/} once so it creates its config dir, then re-run." >&2
  exit 1
fi
PLUGINS_DIR="${CONFIG_DIR}/plugins"
mkdir -p "${PLUGINS_DIR}"
echo "▸ Target: ${PLUGINS_DIR}"

if pgrep -f "${APP}/Contents/MacOS/idea" >/dev/null 2>&1; then
  echo "▸ Quitting ${APP##*/}..."
  osascript -e "tell application \"${APP##*/}\" to quit" >/dev/null 2>&1 || true
  for _ in $(seq 1 40); do
    pgrep -f "${APP}/Contents/MacOS/idea" >/dev/null 2>&1 || break
    sleep 0.5
  done
  if pgrep -f "${APP}/Contents/MacOS/idea" >/dev/null 2>&1; then
    echo "  IDE didn't quit cleanly; sending SIGTERM."
    pkill -f "${APP}/Contents/MacOS/idea" 2>/dev/null || true
    sleep 1
  fi
fi

echo "▸ Installing ${PLUGIN_DIR}..."
rm -rf "${PLUGINS_DIR:?}/${PLUGIN_DIR}"
unzip -q "${DIST_ZIP}" -d "${PLUGINS_DIR}"

echo "▸ Launching ${APP##*/}..."
open -a "${APP}"

echo "✓ Installed $(basename "${DIST_ZIP}") into ${PLUGINS_DIR}/${PLUGIN_DIR}"
