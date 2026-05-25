#!/usr/bin/env bash
# Best-effort UI automation: individual taps / lookups may legitimately find
# nothing, so don't abort on non-zero (no `set -e`).
set -u

# Re-activates Panda Gamepad Pro on the handheld over wireless adb.
#
# Panda needs Shizuku (running as the adb `shell` user) to inject input.
# Shizuku's non-root server dies on reboot, de-activating Panda — this script
# drives Panda's activation UI again. Run it whenever the gamepad mapping stops
# working after a restart. No USB cable needed; the device just has to be awake
# and reachable on Wi-Fi.
#
# Usage: ./reactivate-panda.sh [device-ip:port]
#   With no arg it auto-discovers the device via adb mDNS (the connect port rotates).

PANDA="com.panda.gamepad"
ACT="$PANDA/com.chaozhuo.gameassistant.homepage.ActivationActivity"
DEVICE_IP="${DEVICE_IP:-192.168.4.140}"

# --- Resolve the device ---------------------------------------------------
D="${1:-}"
if [ -z "$D" ]; then
    PORT=$(adb mdns services 2>/dev/null | grep "$DEVICE_IP" | grep tls-connect \
           | grep -oE ':[0-9]+$' | tr -d ':' | head -1)
    [ -n "$PORT" ] && D="$DEVICE_IP:$PORT"
fi
if [ -z "$D" ]; then
    # fall back to whatever wireless device is already attached
    D=$(adb devices | awk '/:[0-9]+\tdevice/{print $1; exit}')
fi
[ -z "$D" ] && { echo "No device found. Pass ip:port, or pair Wireless debugging first." >&2; exit 1; }

echo "Device: $D"
adb connect "$D" >/dev/null 2>&1 || true

# --- Keep the screen awake for the UI taps --------------------------------
adb -s "$D" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$D" shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb -s "$D" shell svc power stayon true >/dev/null 2>&1 || true

# --- Check Shizuku is running (its server must be alive) ------------------
if ! adb -s "$D" shell ps -A 2>/dev/null | grep -q shizuku_server; then
    echo "WARNING: Shizuku server is not running."
    echo "  Start it first — open the Shizuku app and use 'Start via Wireless debugging'"
    echo "  (on-device, no PC), then re-run this script."
    adb -s "$D" shell svc power stayon false >/dev/null 2>&1 || true
    exit 2
fi

# Tap the center of the first UI node matching a resource-id or text.
tap_node() {
    local match="$1"
    adb -s "$D" shell uiautomator dump /sdcard/panda_ui.xml >/dev/null 2>&1 || return 1
    local bounds
    bounds=$(adb -s "$D" shell cat /sdcard/panda_ui.xml 2>/dev/null | tr '>' '\n' \
             | grep -- "$match" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
    [ -z "$bounds" ] && return 1
    local nums; nums=$(echo "$bounds" | grep -oE '[0-9]+')
    local x1 y1 x2 y2; read -r x1 y1 x2 y2 <<<"$(echo "$nums" | tr '\n' ' ')"
    adb -s "$D" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
    return 0
}

echo "Opening Panda activation screen..."
adb -s "$D" shell am start -n "$ACT" >/dev/null 2>&1
sleep 3

# Choose the Shizuku activation method (no-op if already on the detail screen).
if tap_node 'id_activate_via_shizuku'; then echo "  -> chose Shizuku method"; sleep 2; fi
# Press ACTIVATE.
if tap_node 'id_activate"'; then echo "  -> tapped ACTIVATE"; sleep 3; fi

# --- Verify -----------------------------------------------------------------
adb -s "$D" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
sleep 1
adb -s "$D" shell uiautomator dump /sdcard/panda_ui.xml >/dev/null 2>&1 || true
if adb -s "$D" shell cat /sdcard/panda_ui.xml 2>/dev/null | grep -q 'Activated'; then
    echo "✅ Panda Gamepad Pro: Activated"
else
    echo "⚠️  Could not confirm 'Activated' — open Panda manually to check."
fi

adb -s "$D" shell svc power stayon false >/dev/null 2>&1 || true
