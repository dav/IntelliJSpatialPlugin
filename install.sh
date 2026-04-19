#!/usr/bin/env bash
# Build the Spatial plugin, install it into the user-level plugins directory
# for the configured JetBrains IDEs, and relaunch any running targets so the
# new version is live.
#
# Overrides (env vars):
#   APP           Path to one IDE .app bundle. When set with IDE_PRODUCT,
#                 installs only into that IDE.
#   CONFIG_ROOT   JetBrains config root. Default: $HOME/Library/Application Support/JetBrains
#   IDE_PRODUCT   Config-dir prefix for APP. Example: IntelliJIdea, RustRover.
#   TARGETS       Comma-separated install targets in IDE_PRODUCT:APP form.
#                 Default: IntelliJIdea:/Applications/IntelliJ IDEA.app,
#                          RustRover:$HOME/Applications/RustRover.app
#   PLUGIN_DIR    Top-level dir inside the dist zip. Default: IntelliJSpatialPlugin

set -euo pipefail

CONFIG_ROOT="${CONFIG_ROOT:-$HOME/Library/Application Support/JetBrains}"
PLUGIN_DIR="${PLUGIN_DIR:-IntelliJSpatialPlugin}"
TARGETS="${TARGETS:-}"

cd "$(dirname "$0")"

if [[ -n "${APP:-}" || -n "${IDE_PRODUCT:-}" ]]; then
  APP="${APP:-/Applications/IntelliJ IDEA.app}"
  IDE_PRODUCT="${IDE_PRODUCT:-IntelliJIdea}"
  TARGETS="${IDE_PRODUCT}:${APP}"
elif [[ -z "${TARGETS}" ]]; then
  TARGETS="IntelliJIdea:/Applications/IntelliJ IDEA.app,RustRover:${HOME}/Applications/RustRover.app"
fi

echo "▸ Building plugin..."
./gradlew --console=plain buildPlugin

DIST_ZIP="$(ls -1t build/distributions/*.zip 2>/dev/null | head -n1 || true)"
if [[ -z "${DIST_ZIP}" || ! -f "${DIST_ZIP}" ]]; then
  echo "✗ No plugin zip found under build/distributions/." >&2
  exit 1
fi
echo "  built: ${DIST_ZIP}"

IFS=',' read -r -a target_specs <<< "${TARGETS}"
installed_any=0

for target_spec in "${target_specs[@]}"; do
  [[ -n "${target_spec}" ]] || continue
  IDE_PRODUCT="${target_spec%%:*}"
  APP="${target_spec#*:}"

  if [[ -z "${IDE_PRODUCT}" || -z "${APP}" || "${APP}" == "${target_spec}" ]]; then
    echo "✗ Invalid target '${target_spec}'. Expected IDE_PRODUCT:/Applications/App.app" >&2
    exit 1
  fi

  CONFIG_DIR="$(ls -1dt "${CONFIG_ROOT}/${IDE_PRODUCT}"* 2>/dev/null | head -n1 || true)"
  if [[ -z "${CONFIG_DIR}" ]]; then
    echo "▸ Skipping ${APP##*/}: no ${IDE_PRODUCT}* directory under ${CONFIG_ROOT}."
    echo "  Launch ${APP##*/} once so it creates its config dir, then re-run."
    continue
  fi

  PLUGINS_DIR="${CONFIG_DIR}/plugins"
  mkdir -p "${PLUGINS_DIR}"
  echo "▸ Target: ${PLUGINS_DIR}"

  APP_PROCESS_PATTERN="${APP}/Contents/MacOS/"
  was_running=0
  if pgrep -f "${APP_PROCESS_PATTERN}" >/dev/null 2>&1; then
    was_running=1
    echo "▸ Quitting ${APP##*/}..."
    osascript -e "tell application \"${APP##*/}\" to quit" >/dev/null 2>&1 || true
    for _ in $(seq 1 40); do
      pgrep -f "${APP_PROCESS_PATTERN}" >/dev/null 2>&1 || break
      sleep 0.5
    done
    if pgrep -f "${APP_PROCESS_PATTERN}" >/dev/null 2>&1; then
      echo "  IDE didn't quit cleanly; sending SIGTERM."
      pkill -f "${APP_PROCESS_PATTERN}" 2>/dev/null || true
      sleep 1
    fi
  fi

  echo "▸ Installing ${PLUGIN_DIR} into ${APP##*/}..."
  rm -rf "${PLUGINS_DIR:?}/${PLUGIN_DIR}"
  unzip -q "${DIST_ZIP}" -d "${PLUGINS_DIR}"

  if [[ "${was_running}" -eq 1 ]]; then
    echo "▸ Relaunching ${APP##*/}..."
    open -a "${APP}"
  else
    echo "▸ Leaving ${APP##*/} closed (it was not running)."
  fi

  echo "✓ Installed $(basename "${DIST_ZIP}") into ${PLUGINS_DIR}/${PLUGIN_DIR}"
  installed_any=$((installed_any + 1))
done

if [[ "${installed_any}" -eq 0 ]]; then
  echo "✗ No install targets were available." >&2
  exit 1
fi
