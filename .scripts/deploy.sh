#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK_PATH="$REPO_ROOT/src/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.custom.minimizer"

# --- Configuration ---
# Set your device IP here or pass as argument: ./deploy.sh 192.168.1.100
DEVICE_IP="${1:-}"
ADB_PORT="${2:-5555}"

usage() {
    echo "Usage: ./deploy.sh <device-ip> [port]"
    echo ""
    echo "  device-ip   Your Android device's IP address (check Settings > Wi-Fi > your network)"
    echo "  port        ADB port (default: 5555)"
    echo ""
    echo "Prerequisites:"
    echo "  1. Enable Developer Options on your device (tap Build Number 7 times)"
    echo "  2. Enable 'Wireless debugging' in Developer Options"
    echo "  3. On Android 11+, use 'Pair device with pairing code' first:"
    echo "       adb pair <ip>:<pairing-port> <pairing-code>"
    echo "  4. Make sure your device and this machine are on the same Wi-Fi network"
    exit 1
}

if [ -z "$DEVICE_IP" ]; then
    usage
fi

# Check adb is installed
if ! command -v adb &> /dev/null; then
    echo "Error: adb not found. Install Android platform-tools:"
    echo "  brew install android-platform-tools"
    exit 1
fi

# --- Connect wirelessly ---
echo "Connecting to $DEVICE_IP:$ADB_PORT..."
adb connect "$DEVICE_IP:$ADB_PORT"

# Wait for device to be ready
echo "Waiting for device..."
adb -s "$DEVICE_IP:$ADB_PORT" wait-for-device

# --- Build ---
echo ""
echo "Building debug APK..."
cd "$REPO_ROOT/src"
chmod +x gradlew
./gradlew assembleDebug

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH"
    exit 1
fi

# --- Install ---
echo ""
echo "Installing APK on device..."
adb -s "$DEVICE_IP:$ADB_PORT" install -r "$APK_PATH"

# --- Launch ---
echo ""
echo "Launching app..."
adb -s "$DEVICE_IP:$ADB_PORT" shell am start -n "$PACKAGE/.MainActivity"

echo ""
echo "Done! App is running on your device."
echo ""
echo "Useful commands:"
echo "  adb -s $DEVICE_IP:$ADB_PORT logcat -s MotionWake:D Minimizer:D test:D   # view app logs"
echo "  adb -s $DEVICE_IP:$ADB_PORT uninstall $PACKAGE                           # uninstall"
echo "  adb disconnect $DEVICE_IP:$ADB_PORT                                       # disconnect"
