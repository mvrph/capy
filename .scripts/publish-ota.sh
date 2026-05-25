#!/usr/bin/env bash
set -euo pipefail

# Publishes the built release APK to the homebody OTA server on Olares:
#   - uploads app-release.apk as homebody-<versionCode>.apk
#   - writes latest.json so installed apps detect + download the update
#
# Build the release APK first:  (cd src && JAVA_HOME=... ./gradlew assembleRelease)
# Then:  ./.scripts/publish-ota.sh

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$REPO_ROOT/src/app/build/outputs/apk/release/app-release.apk"
OLARES="${OLARES_HOST:-olares@192.168.4.222}"
OTA_DIR="homebody-ota/homebody"
APK_BASE_URL="${APK_BASE_URL:-http://192.168.4.222:8002/homebody}"

if [ ! -f "$APK" ]; then
    echo "Error: release APK not found at $APK — build it first (assembleRelease)." >&2
    exit 1
fi

AAPT=$(ls "$HOME"/Library/Android/sdk/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
if [ -z "${AAPT:-}" ]; then
    echo "Error: aapt2 not found under Android SDK build-tools." >&2
    exit 1
fi

BADGING=$("$AAPT" dump badging "$APK")
VCODE=$(echo "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
VNAME=$(echo "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
echo "Publishing homebody versionCode=$VCODE versionName=$VNAME"

scp "$APK" "$OLARES:$OTA_DIR/homebody-$VCODE.apk"
ssh "$OLARES" "cat > ~/$OTA_DIR/latest.json" <<EOF
{
  "versionCode": $VCODE,
  "versionName": "$VNAME",
  "apkUrl": "$APK_BASE_URL/homebody-$VCODE.apk",
  "notes": "homebody $VNAME"
}
EOF

echo "Published. Manifest now:"
ssh "$OLARES" "cat ~/$OTA_DIR/latest.json"
