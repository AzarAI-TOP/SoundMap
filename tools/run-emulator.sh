#!/usr/bin/env bash
# Launch the SoundMap test emulator, tuned for fast startup.
#
# First run cold-boots (~60-90s) then SAVES a quick-boot snapshot on clean exit,
# so subsequent runs boot in ~5-10s. Requires /dev/kvm (run outside any sandbox
# that masks /dev).
#
#   tools/run-emulator.sh            # launch (foreground; Ctrl-C to stop & save)
#   tools/run-emulator.sh --cold     # force a clean cold boot
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/21.0.11-tem}"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:$PATH"

AVD_NAME="soundmap_test"
IMAGE="system-images;android-36.1;google_apis_playstore;x86_64"

if [ ! -e /dev/kvm ]; then
  echo "ERROR: /dev/kvm not visible. Run outside the sandbox (it masks /dev)." >&2
  exit 1
fi

if ! avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then
  echo "no" | avdmanager create avd -n "$AVD_NAME" -k "$IMAGE" --force
fi

# swangle_indirect (ANGLE) — swiftshader_indirect/gfxstream SIGSEGVs headless on this host.
FLAGS=(-no-window -no-audio -no-boot-anim -gpu swangle_indirect -no-metrics)
[ "${1:-}" = "--cold" ] && FLAGS+=(-no-snapshot-load)

# exec so this PID *is* the emulator — clean to run via a background runner.
exec emulator -avd "$AVD_NAME" "${FLAGS[@]}"
