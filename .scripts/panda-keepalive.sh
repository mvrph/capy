#!/usr/bin/env bash
# Keeps Panda Gamepad Pro activated on the handheld, driven from the Olares
# server over wireless adb. Panda needs Shizuku (adb shell-level privilege);
# Shizuku's non-root server dies on every reboot, which de-activates Panda.
#
# This (re)starts Shizuku and re-activates Panda whenever it finds Shizuku down.
# Idempotent + non-intrusive: if Shizuku is already running it does nothing
# (no screen wake, no taps). Run it:
#   - on a timer (systemd) for hands-off reboot recovery, or
#   - manually as an on-demand "button":  ./panda-keepalive.sh
#   - force a re-activation even if Shizuku is up:  ./panda-keepalive.sh --force
#
# Pairing (one-time) and adb must already be set up on this host. The connect
# port rotates each reboot, so we cache the last one and nmap-scan to rediscover.
set -u

ADB="${ADB:-$HOME/platform-tools/adb}"
DEVICE_IP="${DEVICE_IP:-192.168.4.140}"
SCAN_RANGE="${SCAN_RANGE:-30000-60999}"
PANDA="com.panda.gamepad"
SHIZUKU="moe.shizuku.privileged.api"
ACT="$PANDA/com.chaozhuo.gameassistant.homepage.ActivationActivity"
PORT_CACHE="$HOME/.cache/panda-keepalive.port"
DISCORD_URL="${DISCORD_URL:-https://api.deepvoid.ai/send_message}"
FORCE="${1:-}"

log() { echo "[panda-keepalive $(date '+%F %T')] $*"; }

try_connect() { # $1 = port → 0 if an authorized adb session results
    $ADB connect "$DEVICE_IP:$1" >/dev/null 2>&1
    $ADB devices | grep -qE "$DEVICE_IP:$1[[:space:]]+device"
}

discover_and_connect() {
    if [ -f "$PORT_CACHE" ] && try_connect "$(cat "$PORT_CACHE")"; then
        DEV="$DEVICE_IP:$(cat "$PORT_CACHE")"; return 0
    fi
    log "scanning $DEVICE_IP:$SCAN_RANGE for the adb port..."
    for p in $(nmap -p "$SCAN_RANGE" --open -T4 "$DEVICE_IP" 2>/dev/null | grep -oE '^[0-9]+'); do
        if try_connect "$p"; then
            echo "$p" > "$PORT_CACHE"; DEV="$DEVICE_IP:$p"; return 0
        fi
    done
    return 1
}

tap_node() { # $1 = resource-id/text substring → tap its center
    $ADB -s "$DEV" shell uiautomator dump /sdcard/pk.xml >/dev/null 2>&1 || return 1
    local b
    b=$($ADB -s "$DEV" shell cat /sdcard/pk.xml 2>/dev/null | tr '>' '\n' \
        | grep -- "$1" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
    [ -z "$b" ] && return 1
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<<"$(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')"
    $ADB -s "$DEV" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}

mkdir -p "$(dirname "$PORT_CACHE")"
DEV=""
if ! discover_and_connect; then
    log "device unreachable (offline or wireless debugging off) — nothing to do"
    exit 0
fi
log "connected: $DEV"

if $ADB -s "$DEV" shell ps -A 2>/dev/null | grep -q shizuku_server && [ "$FORCE" != "--force" ]; then
    log "Shizuku running; Panda assumed active — no-op"
    exit 0
fi

log "starting Shizuku..."
APK=$($ADB -s "$DEV" shell pm path "$SHIZUKU" 2>/dev/null | sed 's/package://;q' | tr -d '\r')
LIB="$(dirname "$APK")/lib/arm64/libshizuku.so"
$ADB -s "$DEV" shell "$LIB" 2>&1 | sed 's/^/  /'
sleep 2

log "re-activating Panda..."
$ADB -s "$DEV" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
$ADB -s "$DEV" shell svc power stayon true >/dev/null 2>&1
$ADB -s "$DEV" shell am start -n "$ACT" >/dev/null 2>&1
sleep 3
tap_node 'id_activate_via_shizuku' && { log "  chose Shizuku method"; sleep 2; }
tap_node 'id_activate"'             && { log "  tapped ACTIVATE"; sleep 3; }
$ADB -s "$DEV" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
$ADB -s "$DEV" shell svc power stayon false >/dev/null 2>&1

# Heads-up DM so you know it self-healed.
curl -s --max-time 8 -X POST -H 'Content-Type: application/json' \
    -d '{"message":"🎮 Panda Gamepad Pro re-activated on Gameboy Holo (Shizuku was down — likely a reboot)."}' \
    "$DISCORD_URL" >/dev/null 2>&1 || true

log "done"
