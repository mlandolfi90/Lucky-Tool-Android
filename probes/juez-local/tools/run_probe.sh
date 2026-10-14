#!/bin/bash
# Runs one probe round on the phone and saves the JUEZ log lines.
# usage: run_probe.sh <tag> <model-file> <cpu|gpu> [delaySec] [fg|bg] [config|context|none|on] [sino|paso] [texto|imagenes] [greedy|none]
# bg: brings the Calculator to the front right after launching, so the run happens with another app on screen.
export MSYS_NO_PATHCONV=1
PKG=com.danielealbano.androidremotecontrolmcp.probejuez
TAG=$1; MODEL=$2; BACKEND=$3; DELAY=${4:-0}; MODE=${5:-fg}; THINK=${6:-config}; PROMPT=${7:-sino}; SET=${8:-texto}; SAMPLER=${9:-greedy}
OUT="$(dirname "$0")/probe-results"
mkdir -p "$OUT"
adb logcat -c
adb shell am start -S -n "$PKG/.ProbeActivity" --es model "$MODEL" --es backend "$BACKEND" --ei delay "$DELAY" \
    --es tag "$TAG" --es thinking "$THINK" --es prompt "$PROMPT" --es set "$SET" --es sampler "$SAMPLER" >/dev/null
if [ "$MODE" = bg ]; then
    sleep 2
    adb shell am start -n com.sec.android.app.popupcalculator/.Calculator >/dev/null
    sleep 1
fi
echo "top: $(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*u0 //')"
for _ in $(seq 1 450); do
    if adb logcat -d -s JUEZ:* | grep -qE "JUEZ (SUMMARY|ERROR)"; then break; fi
    sleep 2
done
echo "top at end: $(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*u0 //')"
adb logcat -d -s JUEZ:* > "$OUT/$TAG.log"
grep -E "JUEZ (INIT|SUMMARY|ERROR)" "$OUT/$TAG.log" | sed 's/^.*JUEZ /JUEZ /'
