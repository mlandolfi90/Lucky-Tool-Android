#!/bin/bash
# Runs one probe round on the phone and saves the JUEZ log lines.
# usage: run_probe.sh <tag> <model-file> <cpu|gpu|npu|geniex> [delaySec] [fg|bg] [config|context|none|on] [sino|paso] [texto|imagenes] [greedy|none]
# bg: brings the Calculator to the front right after launching, so the run happens with another app on screen.
export MSYS_NO_PATHCONV=1
PKG=com.danielealbano.androidremotecontrolmcp.probejuez
ACTIVITY=com.danielealbano.androidremotecontrolmcp.probejuez.ProbeActivity
TAG=$1; MODEL=$2; BACKEND=$3; DELAY=${4:-0}; MODE=${5:-fg}; THINK=${6:-config}; PROMPT=${7:-sino}; SET=${8:-texto}; SAMPLER=${9:-greedy}
# The geniex backend lives in its own build flavor, installed as a separate package.
[ "$BACKEND" = geniex ] && PKG="$PKG.geniex"
OUT="$(dirname "$0")/probe-results"
mkdir -p "$OUT"
adb logcat -c
adb shell am start -S -n "$PKG/$ACTIVITY" --es model "$MODEL" --es backend "$BACKEND" --ei delay "$DELAY" \
    --es tag "$TAG" --es thinking "$THINK" --es prompt "$PROMPT" --es set "$SET" --es sampler "$SAMPLER" >/dev/null
if [ "$MODE" = bg ]; then
    sleep 2
    adb shell am start -n com.sec.android.app.popupcalculator/.Calculator >/dev/null
    sleep 1
fi
echo "top: $(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*u0 //')"
for i in $(seq 1 1200); do
    if adb logcat -d -s JUEZ:* | grep -qE "JUEZ (SUMMARY|ERROR)"; then break; fi
    # A native crash leaves no SUMMARY or ERROR line: stop waiting once the app process is gone.
    if [ "$i" -gt 5 ] && [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then echo "process $PKG is gone"; break; fi
    sleep 2
done
echo "top at end: $(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*u0 //')"
adb logcat -d -s JUEZ:* > "$OUT/$TAG.log"
# Diagnosis: crashes plus the runtime and NPU lines, for runs that fail.
adb logcat -d -b crash > "$OUT/$TAG.diag.log"
adb logcat -d -v time | grep -iE "litert|qnn|dispatch|geniex|genie|htp|fastrpc|AndroidRuntime|FATAL|tflite" | tail -400 >> "$OUT/$TAG.diag.log"
grep -E "JUEZ (INIT|SUMMARY|ERROR)" "$OUT/$TAG.log" | sed 's/^.*JUEZ /JUEZ /'
