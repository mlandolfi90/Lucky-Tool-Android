#!/bin/bash
# Runs several probe rounds one after another and prints every summary at the end.
# usage: run_batch.sh <batch-file> [record.md]   (one run_probe.sh argument line per row; '#' rows are skipped)
# With a record file, each round's summary (or its error) is appended to it as soon as the round ends.
DIR="$(dirname "$0")"
REG="$2"
LOGS=()
while read -r line; do
    # A batch file saved with CRLF would pass "on\r" as the last argument of every row.
    line=${line%$'\r'}
    case "$line" in ''|'#'*) continue ;; esac
    tag="${line%% *}"
    # shellcheck disable=SC2086
    bash "$DIR/run_probe.sh" $line </dev/null
    log="$DIR/probe-results/$tag.log"
    LOGS+=("$log")
    if [ -n "$REG" ]; then
        result=$(python -I "$DIR/parse_probe.py" "$log" | sed -n '2,8p' | sed 's/^ *//' | tr '\n' ' ' | cut -c1-900)
        echo "- $(date +%H:%M) · $tag: ${result:-sin SUMMARY (ver $tag.diag.log)}" >> "$REG"
    fi
done < "$1"
python -I "$DIR/parse_probe.py" "${LOGS[@]}"
