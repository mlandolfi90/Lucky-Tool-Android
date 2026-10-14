#!/bin/bash
# Runs several probe rounds one after another and prints every summary at the end.
# usage: run_batch.sh <batch-file>   (one run_probe.sh argument line per row; '#' rows are skipped)
DIR="$(dirname "$0")"
LOGS=()
while read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    # shellcheck disable=SC2086
    bash "$DIR/run_probe.sh" $line </dev/null
    LOGS+=("$DIR/probe-results/${line%% *}.log")
done < "$1"
python -I "$DIR/parse_probe.py" "${LOGS[@]}"
