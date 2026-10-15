"""Summarize probe logs: one line per run plus every wrong answer.

usage: python -I parse_probe.py <log> [<log> ...]
"""
import json
import re
import sys

# key=value fields up to the reply, which is free text and always last.
CASE = re.compile(r"JUEZ CASE (.*?) reply=(.*)$")
SUMMARY = re.compile(r"JUEZ SUMMARY (\{.*\})\s*$")
ERROR = re.compile(r"JUEZ ERROR (.*)$")
SUMMARY_KEYS = [
    "model", "backend", "casesFile", "prompt", "thinking", "speculative", "visual", "initMs", "pssAfterInitKb",
    "pssEndKb", "peakRssKb", "importanceAtStart", "importanceDuring", "cases", "correct", "dangerousYes",
    "extraStops", "invalid", "firstMs", "p50Ms", "p90Ms", "maxMs", "avgMs", "prefillTpsAvg", "decodeTpsAvg",
    "decodeTokensAvg", "modelYes", "appCaught", "appExtraStops", "copyNotInAction",
]


def show(path):
    cases, summary, errors = [], None, []
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            if m := CASE.search(line):
                fields = dict(pair.split("=", 1) for pair in m.group(1).split() if "=" in pair)
                fields["reply"] = m.group(2).strip()
                cases.append(fields)
            elif m := SUMMARY.search(line):
                summary = json.loads(m.group(1))
            elif m := ERROR.search(line):
                errors.append(m.group(1).strip())
    print(f"== {path}")
    if summary:
        print("   " + " ".join(f"{k}={summary[k]}" for k in SUMMARY_KEYS if k in summary))
    for err in errors:
        print(f"   ERROR {err[:300]}")
    for case in cases:
        if case["expected"] != case["verdict"]:
            if case["verdict"] == "INVALIDO":
                kind = "sin-veredicto"
            elif case["expected"] == "NO":
                kind = "PELIGRO(SI indebido)"
            else:
                kind = "freno-de-mas"
            # A "datos" answer also says what the model said on the step, the checks of each copied field and, when
            # the reply was out of format, why. Logs before the strict reading call the step answer `model=`.
            step = case.get("stepAnswer", case.get("model"))
            form = f"formato={case['formato']} " if "formato" in case else ""
            data = f"modelo={step} {case['checks']} {form}" if "checks" in case else ""
            print(
                f"   MAL {case['id']} esperado={case['expected']} dijo={case['verdict']} [{kind}] "
                f"{data}ms={case['ms']} reply={case['reply'][:90]!r}"
            )
    if cases:
        print(f"   respuestas de ejemplo: {cases[0]['id']} -> {cases[0]['reply'][:60]!r}")


# Replies may carry any character (arrows, quotes); the Windows console default (cp1252) cannot print them all.
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
for log in sys.argv[1:]:
    show(log)
