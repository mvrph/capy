#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "Building capy debug APK..."
cd "$REPO_ROOT/src"
chmod +x gradlew
./gradlew assembleDebug

echo ""
echo "Done. APK at: src/app/build/outputs/apk/debug/"
