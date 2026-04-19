#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

usage() {
  cat <<'EOF'
Usage: ./publish-github-release.sh [--skip-build] [--notes-file PATH]

Builds the plugin ZIP locally, then creates or updates a GitHub Release named
after the version in gradle.properties (for example: v0.1.1).

Requirements:
  - gh CLI installed and authenticated
  - version already updated in gradle.properties
  - current commit already pushed to GitHub if you want the release tag to point to it
EOF
}

SKIP_BUILD=0
NOTES_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --notes-file)
      NOTES_FILE="${2:-}"
      if [[ -z "$NOTES_FILE" ]]; then
        echo "Missing path after --notes-file" >&2
        exit 1
      fi
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required." >&2
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "gh is not authenticated. Run 'gh auth login' first." >&2
  exit 1
fi

VERSION="$(sed -n 's/^version = //p' gradle.properties)"
if [[ -z "$VERSION" ]]; then
  echo "Could not read version from gradle.properties" >&2
  exit 1
fi

echo "Did you remember to update the version in gradle.properties and the RELEASE_NOTES.md?"
read -r -p "Type Y and press Enter to continue: " CONFIRM_RELEASE
if [[ "$CONFIRM_RELEASE" != "Y" ]]; then
  echo "Aborted."
  exit 1
fi

TAG="v${VERSION}"
TITLE="Spatial ${TAG}"
DEFAULT_NOTES="Install the attached plugin ZIP from disk in a compatible JetBrains IDE: Settings/Preferences > Plugins > gear icon > Install Plugin from Disk..."
RESOLVED_NOTES_FILE=""

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "▸ Building plugin ZIP..."
  ./gradlew --console=plain buildPlugin
fi

DIST_ZIP="$(ls -1t build/distributions/*.zip 2>/dev/null | head -n1 || true)"
if [[ -z "$DIST_ZIP" || ! -f "$DIST_ZIP" ]]; then
  echo "No plugin ZIP found under build/distributions." >&2
  exit 1
fi

echo "▸ Using artifact: $DIST_ZIP"

if [[ -n "$NOTES_FILE" && ! -f "$NOTES_FILE" ]]; then
  echo "Notes file does not exist: $NOTES_FILE" >&2
  exit 1
fi

if [[ -n "$NOTES_FILE" ]]; then
  RESOLVED_NOTES_FILE="$(mktemp)"
  sed "s/<version>/${VERSION}/g" "$NOTES_FILE" > "$RESOLVED_NOTES_FILE"
fi

if gh release view "$TAG" >/dev/null 2>&1; then
  echo "▸ Updating GitHub Release $TAG..."
  gh release upload "$TAG" "$DIST_ZIP" --clobber
  if [[ -n "$RESOLVED_NOTES_FILE" ]]; then
    gh release edit "$TAG" --title "$TITLE" --notes-file "$RESOLVED_NOTES_FILE"
  else
    gh release edit "$TAG" --title "$TITLE" --notes "$DEFAULT_NOTES"
  fi
else
  echo "▸ Creating GitHub Release $TAG..."
  if [[ -n "$RESOLVED_NOTES_FILE" ]]; then
    gh release create "$TAG" "$DIST_ZIP" --title "$TITLE" --notes-file "$RESOLVED_NOTES_FILE"
  else
    gh release create "$TAG" "$DIST_ZIP" --title "$TITLE" --generate-notes
  fi
fi

if [[ -n "$RESOLVED_NOTES_FILE" ]]; then
  rm -f "$RESOLVED_NOTES_FILE"
fi

echo "✓ Published $TAG"
