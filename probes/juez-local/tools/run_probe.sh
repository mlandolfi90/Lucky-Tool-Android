#!/bin/bash
# Runs one probe round on the phone and saves the JUEZ log lines.
# usage: run_probe.sh <tag> <model-file> <gpu|cpu> [delaySec] [fg|bg] [config|context|none|on] [prompt] [texto|imagenes|todo]
#                     [greedy|none] [questions-file] [default|on|off] [visual-token-budget] [off|on]
# The questions file (config/<file>, default casos-v1.json) is pushed before every run, so changing the questions or
# the prompts needs no new build; [prompt] names one of its prompts. The last three: speculative decoding (default =
# the model's own setting), image token budget (0 = the model's own) and the engine's token counts and speeds (bench).
# bg: brings the Calculator to the front right after launching, so the run happens with another app on screen.
export MSYS_NO_PATHCONV=1
PKG=com.danielealbano.androidremotecontrolmcp.probejuez
ACTIVITY=com.danielealbano.androidremotecontrolmcp.probejuez.ProbeActivity
TAG=$1; MODEL=$2; BACKEND=$3; DELAY=${4:-0}; MODE=${5:-fg}; THINK=${6:-config}; PROMPT=${7:-sino}; SET=${8:-texto}; SAMPLER=${9:-greedy}
CASES=${10:-casos-v1.json}; SPEC=${11:-default}; VISUAL=${12:-0}; BENCH=${13:-off}
DIR="$(dirname "$0")"
OUT="$DIR/probe-results"
CONFIG_DIR="/sdcard/Android/data/$PKG/files/config"
mkdir -p "$OUT"
# The app creates its question folder when it opens; a folder made by adb could stay unreadable for the app.
if ! adb shell ls "$CONFIG_DIR" >/dev/null 2>&1; then
    adb shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
    sleep 1
fi
adb push "$DIR/../config/$CASES" "$CONFIG_DIR/$CASES" >/dev/null || { echo "could not push $CASES"; exit 1; }
adb logcat -c
adb shell am start -S -n "$PKG/$ACTIVITY" --es model "$MODEL" --es backend "$BACKEND" --ei delay "$DELAY" \
    --es tag "$TAG" --es thinking "$THINK" --es prompt "$PROMPT" --es set "$SET" --es sampler "$SAMPLER" \
    --es cases "$CASES" --es speculative "$SPEC" --ei visual "$VISUAL" --es bench "$BENCH" >/dev/null
if [ "$MODE" = bg ]; then
    sleep 2
    adb shell am start -n com.sec.android.app.popupcalculator/.Calculator >/dev/null
    sleep 1
fi
echo "top: $(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*u0 //')"
for i in $(seq 1 2400); do
    if adb logcat -d -s JUEZ:* | grep -qE "JUEZ (SUMMARY|ERROR)"; then break; fi
    # A native crash leaves no SUMMARY or ERROR line: stop waiting once the app process is gone.
    if [ "$i" -gt 5 ] && [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then echo "process $PKG is gone"; break; fi
    sleep 2
done
echo "top at end: $(adb shell dumpsys activity activities | grep -m1 topResumedActivity | sed 's/.*u0 //')"
adb logcat -d -s JUEZ:* > "$OUT/$TAG.log"
# Diagnosis: crashes plus the runtime lines, for runs that fail.
adb logcat -d -b crash > "$OUT/$TAG.diag.log"
adb logcat -d -v time | grep -iE "litert|tflite|opencl|AndroidRuntime|FATAL" | tail -400 >> "$OUT/$TAG.diag.log"
grep -E "JUEZ (INIT|SUMMARY|ERROR)" "$OUT/$TAG.log" | sed 's/^.*JUEZ /JUEZ /'
