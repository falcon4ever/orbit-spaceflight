#!/usr/bin/env bash
# Copyright 2026 Laurence Muller — Apache 2.0
#
# Pulls the newest .orbitsession exported by the Android demo app to the host.
# Useful on emulators, where the share sheet has no target apps installed:
# tap Share in the app's Flight Recorder screen (the file is written before the
# sheet opens), then run this.
#
# Usage: tools/pull-session.sh [output-dir] [applicationId]

set -euo pipefail

OUT_DIR="${1:-.}"
APP_ID="${2:-net.multigesture.spaceflight.demo}"
ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb)"

NEWEST="$("$ADB" shell "run-as $APP_ID ls -t cache/sessions" | tr -d '\r' | head -1)"
if [ -z "$NEWEST" ]; then
  echo "No sessions found. Tap Share in the app's Flight Recorder screen first." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
"$ADB" shell "run-as $APP_ID cat cache/sessions/$NEWEST" > "$OUT_DIR/$NEWEST"
echo "$OUT_DIR/$NEWEST"
echo "Open it with: Mission Control → Open session…"
