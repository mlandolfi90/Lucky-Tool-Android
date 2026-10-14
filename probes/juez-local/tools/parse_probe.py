"""Summarize probe logs: one line per run plus every wrong answer.

usage: python -I parse_probe.py <log> [<log> ...]
"""
import json
import re
import sys

CASE = re.compile(
    r"JUEZ CASE tag=(\S+) round=(\d+) id=(\S+) expected=(\S+) verdict=(\S+) ms=(\d+) importance=(\d+) reply=(.*)$"
)
SUMMARY = re.compile(r"JUEZ SUMMARY (\{.*\})\s*$")
ERROR = re.compile(r"JUEZ ERROR (.*)$")


def show(path):
    cases, summary, errors = [], None, []
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            if m := CASE.search(line):
                cases.append(m.groups())
            elif m := SUMMARY.search(line):
                summary = json.loads(m.group(1))
            elif m := ERROR.search(line):
                errors.append(m.group(1).strip())
    print(f"== {path}")
    if summary:
        keys = [
            "model", "backend", "initMs", "pssAfterInitKb", "pssEndKb", "peakRssKb", "importanceAtStart",
            "importanceDuring", "cases", "correct", "dangerousYes", "extraStops", "invalid",
            "firstMs", "p50Ms", "p90Ms", "maxMs", "avgMs",
        ]
        print("   " + " ".join(f"{k}={summary.get(k)}" for k in keys))
    for err in errors:
        print(f"   ERROR {err[:300]}")
    for tag, rnd, cid, expected, verdict, ms, imp, reply in cases:
        if expected != verdict:
            kind = "PELIGRO(SI indebido)" if expected == "NO" and verdict == "SI" else "freno-de-mas"
            print(f"   MAL {cid} esperado={expected} dijo={verdict} [{kind}] ms={ms} reply={reply[:90]!r}")
    if cases:
        print(f"   respuestas de ejemplo: {cases[0][2]} -> {cases[0][7][:60]!r}")


for log in sys.argv[1:]:
    show(log)
