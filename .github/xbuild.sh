#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BRANCH="ci/scratch"
REMOTE="ci"
WORKFLOW_FILE="ci.yml"
ARTIFACT_NAME="cove-unsigned-ipa"
OUTPUT_DIR=".xbuild-out"
IPA_DIR="$OUTPUT_DIR/ipa"
UDID="${1:-00008140-000438E80113001C}"
BUNDLE_ID="org.bitcoinppl.cove"

mkdir -p "$OUTPUT_DIR"

# stage everything except known sensitive files
if [[ -f "android/cove-release.keystore" ]]; then
  git update-index --assume-unchanged android/cove-release.keystore || true
fi
if [[ -f "cove-release.keystore" ]]; then
  git update-index --assume-unchanged cove-release.keystore || true
fi

git checkout -B "$BRANCH"
git add -A
# explicit unstage safety if present
(git reset -q -- android/cove-release.keystore cove-release.keystore 2>/dev/null) || true

if git diff --cached --quiet; then
  echo "No staged changes to push."
else
  git commit -m "ci: scratch ipa build"
fi

git push -f "$REMOTE" "$BRANCH"

echo "Triggering workflow $WORKFLOW_FILE on $BRANCH"
gh workflow run "$WORKFLOW_FILE" --ref "$BRANCH"

# wait for the newest run on this branch/workflow
RUN_ID="$(gh run list --workflow "$WORKFLOW_FILE" --branch "$BRANCH" --limit 1 --json databaseId --jq '.[0].databaseId')"
if [[ -z "$RUN_ID" || "$RUN_ID" == "null" ]]; then
  echo "Failed to discover workflow run id" >&2
  exit 1
fi

echo "Watching run $RUN_ID"
gh run watch "$RUN_ID"

rm -rf "$IPA_DIR"
mkdir -p "$IPA_DIR"
gh run download "$RUN_ID" -n "$ARTIFACT_NAME" -D "$IPA_DIR"

IPA_PATH="$(ls "$IPA_DIR"/*.ipa | head -n 1)"
if [[ -z "$IPA_PATH" ]]; then
  echo "No IPA found in artifact" >&2
  exit 1
fi

echo "Installing $IPA_PATH"
xtool install --usb --udid "$UDID" "$IPA_PATH"
xtool launch --usb --udid "$UDID" "$BUNDLE_ID"

echo "Done: $IPA_PATH"
