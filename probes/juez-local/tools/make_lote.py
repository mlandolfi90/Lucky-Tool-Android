"""Builds a question file and its screens from a batch source, and says before any run what the app's checks see.

usage: python -B make_lote.py <fuente.json> <prompts-from.json> <datos-prompt-from.json> <version> <out.json>
                              <screens-dir>

The source (written blind, see lote-v8/) has the plans, the plan values per step, the screens and the cases, each case
with the data a perfect copier would write (`ideal`), its kind and why. The question file gets the prompts `sino` and
`lista` of <prompts-from.json> and `datos` of <datos-prompt-from.json> (with its amount fields); the extra keys of
each case stay in the source. Then push the screens to the app's images folder:
adb push <screens-dir>/. /sdcard/Android/data/com.danielealbano.androidremotecontrolmcp.probejuez/files/images/
"""
import json
import sys
from pathlib import Path

from make_screens import HEIGHT, WIDTH, base, button, font
from plan_values import accepted, in_text, is_none

FIELDS = ("APP", "MONTO", "DESTINATARIO")
CASE_KEYS = ("id", "plan", "step", "action", "expected", "image")
BUTTON_TOP = HEIGHT - 300
VALUE_SIZE, VALUE_LINE = 60, 72
NOTE_SIZE, NOTE_LINE = 46, 56


def wrap(draw, text, size, bold, width):
    """The lines of text that fit in width pixels: broken between words and, inside a longer word, where it fits."""
    face = font(size, bold)
    lines = []
    for word in text.split(" "):
        candidate = f"{lines[-1]} {word}" if lines else word
        if lines and draw.textlength(candidate, font=face) <= width:
            lines[-1] = candidate
            continue
        while draw.textlength(word, font=face) > width:
            cut = max(i for i in range(1, len(word) + 1) if draw.textlength(word[:i], font=face) <= width)
            lines.append(word[:cut])
            word = word[cut:]
        lines.append(word)
    return lines


def value_rows(draw, items, top=420):
    """The rows of make_screens.rows, with a long value wrapped onto more lines instead of running off the screen."""
    y = top
    for label, value in items:
        draw.text((60, y), label, font=font(44), fill="#666666")
        lines = wrap(draw, value, VALUE_SIZE, True, WIDTH - 120)
        for index, line in enumerate(lines):
            draw.text((60, y + 60 + index * VALUE_LINE), line, font=font(VALUE_SIZE, True), fill="#111111")
        extra = VALUE_LINE * (len(lines) - 1)
        draw.line([60, y + 150 + extra, WIDTH - 60, y + 150 + extra], fill="#DDDDDD", width=3)
        y += 180 + extra
    return y


def note(draw, top, header, text):
    """A message box like the order chat of make_screens.py, with its own header; returns its bottom."""
    lines = wrap(draw, text, NOTE_SIZE, False, WIDTH - 200)
    bottom = top + 130 + NOTE_LINE * len(lines)
    draw.rounded_rectangle([60, top, WIDTH - 60, bottom], radius=30, fill="#EEF2F8")
    draw.text((100, top + 30), header, font=font(40, True), fill="#333333")
    for index, line in enumerate(lines):
        draw.text((100, top + 100 + index * NOTE_LINE), line, font=font(NOTE_SIZE), fill="#111111")
    return bottom


def draw_screen(spec, out):
    img, draw = base(spec["app"], spec["title"])
    bottom = value_rows(draw, [tuple(row) for row in spec["rows"]])
    if spec.get("note"):
        bottom = note(draw, bottom + 40, spec["note"]["header"], spec["note"]["text"])
    if spec.get("button"):
        if bottom > BUTTON_TOP:
            sys.exit(f"{spec['file']}: the rows and the note run under the button")
        button(draw, spec["button"])
    img.save(out / spec["file"])


def ideal_passes(case, step, amount_fields):
    """Whether the app's checks let the perfect copy through: the plan accepts it and, for a text question, every
    value but NINGUNO is written in the action."""
    for field in FIELDS:
        value, amount = case["ideal"][field], field in amount_fields
        if not accepted(value, step[field], amount):
            return False
        if case["image"] is None and not is_none(value) and not in_text(value, case["action"], amount):
            return False
    return True


def check(source, amount_fields):
    """Fails on a broken source; returns, per case, whether the app's checks let its perfect copy through."""
    screens = {spec["file"] for spec in source["screens"]}
    ids = [case["id"] for case in source["cases"]]
    if len(set(ids)) != len(ids):
        sys.exit("case ids are not unique")
    ideal_ok = {}
    for case in source["cases"]:
        step = source["stepData"].get(case["plan"], {}).get(str(case["step"]))
        if step is None or any(not step.get(field) for field in FIELDS):
            sys.exit(f"{case['id']}: no plan values for plan {case['plan']} step {case['step']}")
        if case["image"] is not None and case["image"] not in screens:
            sys.exit(f"{case['id']}: screen {case['image']} is not in the source")
        if case["expected"] not in ("SI", "NO"):
            sys.exit(f"{case['id']}: expected must be SI or NO")
        ideal_ok[case["id"]] = ideal_passes(case, step, amount_fields)
    return ideal_ok


def main(source_path, prompts_path, datos_path, version, out_path, screens_dir):
    sys.stdout.reconfigure(encoding="utf-8")
    with open(source_path, encoding="utf-8") as handle:
        source = json.load(handle)
    with open(prompts_path, encoding="utf-8") as handle:
        prompts_from = json.load(handle)
    with open(datos_path, encoding="utf-8") as handle:
        datos_from = json.load(handle)
    ideal_ok = check(source, datos_from["prompts"]["datos"]["amountFields"])
    questions = {
        "version": int(version),
        "note": (
            f"Lote nuevo v{version}, escrito a ciegas (sin ver respuestas ni prompts afinados): "
            f"{len(source['cases'])} preguntas, fuente en tools/lote-v{version}/fuente.json. "
            f"Los prompts sino y lista son los de {Path(prompts_path).name}; datos, el de {Path(datos_path).name}. "
            + source.get("note", "")
        ).strip(),
        "imageNote": datos_from["imageNote"],
        "plans": source["plans"],
        "stepData": source["stepData"],
        "prompts": {
            "sino": prompts_from["prompts"]["sino"],
            "lista": prompts_from["prompts"]["lista"],
            "datos": datos_from["prompts"]["datos"],
        },
        "cases": [{key: case[key] for key in CASE_KEYS} for case in source["cases"]],
    }
    with open(out_path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(questions, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    out = Path(screens_dir)
    out.mkdir(parents=True, exist_ok=True)
    for spec in source["screens"]:
        draw_screen(spec, out)
    cases = source["cases"]
    text = sum(case["image"] is None for case in cases)
    print(f"{out_path}: {len(cases)} cases ({text} text, {len(cases) - text} image), {len(source['screens'])} screens")
    print(f"SI {sum(c['expected'] == 'SI' for c in cases)}, NO {sum(c['expected'] == 'NO' for c in cases)}")
    # Before any run: what the app's checks alone would do with a perfect copy.
    limits = [c["id"] for c in cases if c["expected"] == "SI" and not ideal_ok[c["id"]]]
    caught = [c["id"] for c in cases if c["expected"] == "NO" and not ideal_ok[c["id"]]]
    unseen = [c["id"] for c in cases if c["expected"] == "NO" and ideal_ok[c["id"]]]
    print(f"SI whose perfect copy the app's checks stop (rule limits): {len(limits)} {limits}")
    print(f"NO the app's checks stop with a perfect copy: {len(caught)}")
    print(f"NO left to the model's own SI/NO: {len(unseen)}")


if __name__ == "__main__":
    main(*sys.argv[1:7])
