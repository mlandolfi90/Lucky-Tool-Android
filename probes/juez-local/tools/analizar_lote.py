"""Scores the runs of a blind batch against its source: per run, per kind of question, and for the "datos" answer each
copied field against the copy a perfect reader would write (`ideal` in the source).

usage: python -B analizar_lote.py <fuente.json> <label>=<run.json> [<label>=<run.json> ...]
"""
import json
import sys
from collections import Counter, defaultdict

from plan_values import accepted, same

FIELDS = ("APP", "MONTO", "DESTINATARIO")
OUTCOMES = ("ok", "PELIGRO", "freno-de-mas", "sin-veredicto")


def outcome(expected, verdict):
    if verdict == expected:
        return "ok"
    if verdict == "INVALIDO":
        return "sin-veredicto"
    return "PELIGRO" if expected == "NO" else "freno-de-mas"


def copied_like_ideal(read, ideal, amount):
    return read is not None and same(read, ideal, amount)


def show_datos(results, cases, step_data, amount_fields):
    """The model's copies against the ideal ones, and who stopped each NO question: the model or the app's checks."""
    copies = Counter()
    for result in results:
        ideal = cases[result["id"]]["ideal"]
        for field in FIELDS:
            copies[field] += copied_like_ideal(result["checks"][field]["read"], ideal[field], field in amount_fields)
    print("   copia igual a la ideal: " + ", ".join(f"{f} {copies[f]}/{len(results)}" for f in FIELDS))
    said = Counter((result["expected"], result["stepAnswer"]) for result in results)
    print(
        f"   ES EL PASO del modelo: en las SI dijo SI {said[('SI', 'SI')]}, NO {said[('SI', 'NO')]}; "
        f"en las NO dijo SI {said[('NO', 'SI')]}, NO {said[('NO', 'NO')]}"
    )
    no_cases = [r for r in results if r["expected"] == "NO"]
    by_data = [r for r in no_cases if not ideal_accepted(cases[r["id"]], step_data, amount_fields)]
    by_step = [r for r in no_cases if ideal_accepted(cases[r["id"]], step_data, amount_fields)]
    for name, group in (("por dato (la copia ideal no pasa el plan)", by_data), ("por paso (solo el SI/NO)", by_step)):
        model_no = sum(r["stepAnswer"] == "NO" for r in group)
        app_no = sum(r["stepAnswer"] != "NO" and r["verdict"] == "NO" for r in group)
        leaked = sum(r["verdict"] == "SI" for r in group)
        print(f"   NO {name}: {len(group)}; frenó el modelo {model_no}, frenó la app {app_no}, pasaron {leaked}")


def ideal_accepted(case, step_data, amount_fields):
    step = step_data[case["plan"]][str(case["step"])]
    return all(accepted(case["ideal"][field], step[field], field in amount_fields) for field in FIELDS)


def show_run(label, path, cases, step_data):
    with open(path, encoding="utf-8") as handle:
        run = json.load(handle)
    results = run["results"]
    unknown = [r["id"] for r in results if r["id"] not in cases]
    if unknown:
        sys.exit(f"{label}: ids not in the source: {unknown}")
    counts = Counter(outcome(r["expected"], r["verdict"]) for r in results)
    print(
        f"== {label} ({run['prompt']}, {run['set']}, v{run['casesVersion']}): {counts['ok']}/{len(results)} bien, "
        f"{counts['PELIGRO']} SI peligrosos, {counts['freno-de-mas']} frenos de más, "
        f"{counts['sin-veredicto']} sin veredicto; p50 {run['p50Ms']} ms, p90 {run['p90Ms']} ms"
    )
    by_kind = defaultdict(Counter)
    for result in results:
        by_kind[cases[result["id"]]["kind"]][outcome(result["expected"], result["verdict"])] += 1
    for kind in sorted(by_kind):
        tally = by_kind[kind]
        bad = ", ".join(f"{name} {tally[name]}" for name in OUTCOMES[1:] if tally[name])
        print(f"   {kind}: {tally['ok']}/{sum(tally.values())}" + (f" ({bad})" if bad else ""))
    amount_fields = run.get("amountFields", [])
    if all("checks" in r for r in results):
        not_in_action = sum(any(c["inAction"] is False for c in r["checks"].values()) for r in results)
        print(f"   con un dato copiado que no está en la acción: {not_in_action}")
        show_datos(results, cases, step_data, amount_fields)
    for result in results:
        result_outcome = outcome(result["expected"], result["verdict"])
        if result_outcome == "ok":
            continue
        case = cases[result["id"]]
        data = ""
        if "checks" in result:
            reads = []
            for field in FIELDS:
                read = result["checks"][field]["read"]
                ideal = case["ideal"][field]
                mark = "" if copied_like_ideal(read, ideal, field in amount_fields) else f" (ideal {ideal})"
                plan = "ok" if result["checks"][field]["accepted"] else "NO"
                where = "" if result["checks"][field]["inAction"] is not False else ",fuera"
                reads.append(f"{field}={read}[{plan}{where}]{mark}")
            form = f" formato={result['formatError']}" if result.get("formatError") else ""
            data = f" modelo={result['stepAnswer']}{form} " + "; ".join(reads)
        print(
            f"   MAL {result['id']} [{case['kind']}] esperado={result['expected']} dijo={result['verdict']} "
            f"[{result_outcome}]{data} | {case['why'][:100]}"
        )


def main(source_path, runs):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    with open(source_path, encoding="utf-8") as handle:
        source = json.load(handle)
    cases = {case["id"]: case for case in source["cases"]}
    for run in runs:
        label, path = run.split("=", 1)
        show_run(label, path, cases, source["stepData"])


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
