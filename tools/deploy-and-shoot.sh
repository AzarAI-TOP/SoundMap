#!/usr/bin/env bash
# Wait for the running emulator, install the debug APK, launch SoundMap, and
# capture a screenshot. Also saves a quick-boot snapshot so the next start is fast.
#
#   tools/deploy-and-shoot.sh [screenshot_name]
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
OUT="$ROOT/screenshots"; mkdir -p "$OUT"
NAME="${1:-launch}"

echo "Waiting for device + full boot..."
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "Booted."

[ -f "$APK" ] || { echo "APK missing; run ./gradlew :app:assembleDebug first" >&2; exit 1; }
echo "Installing..."; adb install -r -g "$APK" | tail -2
echo "Launching..."; adb shell am start -n top.azarai.soundmap/.MainActivity | tail -1
sleep 6
adb exec-out screencap -p > "$OUT/$NAME.png"
echo "Saved $OUT/$NAME.png ($(stat -c %s "$OUT/$NAME.png") bytes)"

echo "Recent crash/effect logs:"
adb logcat -d -t 200 2>/dev/null | grep -E "FATAL|AndroidRuntime|SpatialAudioController|top.azarai.soundmap" | tail -15 || echo "(none)"

# Persist a quick-boot snapshot for fast future starts.
echo "Saving quick-boot snapshot..."
adb emu avd snapshot save default_boot >/dev/null 2>&1 && echo "snapshot saved" || echo "snapshot save skipped"
