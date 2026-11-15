#!/bin/sh
# Offline accuracy and speed check of the gesture decoder (app/.../pckeyboard/gesture).
#
#   tools/gesture-harness/run.sh --lang uk [--top 1000] [--skip 0] [--noise 0.25] [--cut 0]
#       [--seed 42]
#       [--layout qwerty|compact] [--width 1080 --keyh 160] [--set SIGMA_LOC=0.5,LAMBDA=1.0]
#       [--examples 20]
#
# Gestures are synthesized over the real layout XML (res/xml-<lang>/kbd_<layout>.xml) for the
# most frequent words of assets/gesture/<lang>.txt and decoded with the app's own code.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SRC=$ROOT/app/src/main/java/org/pocketworkstation/pckeyboard/gesture
if grep -l '^import android' "$SRC"/*.java; then
    echo "gesture core must not import android.*"
    exit 1
fi
OUT=${TMPDIR:-/tmp}/gesture-harness
mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" "$SRC"/*.java "$HERE"/*.java
exec java -cp "$OUT" GestureHarness --res "$ROOT/app/src/main/res" \
    --assets "$ROOT/app/src/main/assets" "$@"
