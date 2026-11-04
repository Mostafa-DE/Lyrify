"""Golden cases for Lyrify's SegmentAlignment (S1): the bench's own s1_starts, gain and fill (scripts/npu_timing.py,
taken from the file's source so the bench module's heavy imports are not needed), on synthetic made-up text only.

Usage: s1_golden.py <bench scripts dir> <out.json>
"""
import ast
import json
import random
import sys

import numpy as np
from rapidfuzz import fuzz

scripts, out_path = sys.argv[1], sys.argv[2]
sys.path.insert(0, scripts)
from common import normalize_text  # noqa: E402  (light: re, unicodedata)

src = open(f"{scripts}/npu_timing.py", encoding="utf-8").read()
tree = ast.parse(src)
ns = {"np": np, "fuzz": fuzz, "normalize_text": normalize_text, "SEC_PER_WORD": 0.45,
      "TAU": 0.5, "MAX_LINES_PER_SEG": 16, "MAX_SEGS_PER_LINE": 4}
wanted = {"gain", "fill", "s1_starts"}
for node in tree.body:
    if isinstance(node, ast.FunctionDef) and node.name in wanted:
        exec(compile(ast.Module(body=[node], type_ignores=[]), "npu_timing.py", "exec"), ns)
assert wanted <= set(ns), "missing functions"
# The constants must be the bench's.
for node in tree.body:
    if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == "TAU" for t in getattr(node.targets[0], "elts", [node.targets[0]])):
        print("bench constants:", ast.unparse(node))

rng = random.Random(20261103)
SYL = ["ka", "lo", "mi", "ren", "tas", "vo", "qui", "zan", "pe", "dru", "fy", "gol", "ha", "jun", "wex", "bri", "sto", "nal"]


def word():
    return "".join(rng.choice(SYL) for _ in range(rng.randint(1, 3)))


def line():
    w = [word() for _ in range(rng.randint(2, 7))]
    if rng.random() < 0.2:
        w[0] = w[0].capitalize() + ","
    if rng.random() < 0.1:
        w.append("(" + word() + ")")
    return " ".join(w)


def perturb(text):
    ws = text.split()
    out = []
    for x in ws:
        r = rng.random()
        if r < 0.08:
            continue  # dropped word
        if r < 0.16:
            out.append(word())  # misheard word
            continue
        out.append(x)
    if rng.random() < 0.1:
        out.append(word())
    return " ".join(out) if out else word()


def case(n_lines, seed_mode):
    lines = [line() for _ in range(n_lines)]
    if seed_mode == "repeats":
        chorus = [line(), line()]
        lines = [l for k in range(n_lines // 2) for l in (chorus if k % 3 == 2 else [line(), line()])]
    if rng.random() < 0.3:
        lines.insert(rng.randrange(len(lines)), "[Chorus]")
    segs, t, i = [], rng.uniform(2, 15), 0
    while i < len(lines):
        r = rng.random()
        if r < 0.15 and i + 1 < len(lines):
            text, k = lines[i] + " " + lines[i + 1], 2  # two lines in one segment
        elif r < 0.2 and i + 2 < len(lines):
            text, k = " ".join(lines[i:i + 3]), 3
        elif r < 0.3:
            ws = lines[i].split()
            half = max(1, len(ws) // 2)
            for part in (ws[:half], ws[half:]):
                if part:
                    d = rng.uniform(1.0, 3.0)
                    segs.append({"start": round(t, 2), "end": round(t + d, 2), "text": " " + perturb(" ".join(part))})
                    t += d + rng.uniform(0.0, 0.6)
            i += 1
            continue
        elif r < 0.36:
            i += 1  # a line nobody heard
            t += rng.uniform(2, 4)
            continue
        else:
            text, k = lines[i], 1
        d = rng.uniform(1.5, 4.0) * k
        segs.append({"start": round(t, 2), "end": round(t + d, 2), "text": " " + perturb(text)})
        t += d + rng.uniform(0.0, 1.5)
        if rng.random() < 0.08:
            segs.append({"start": round(t, 2), "end": round(t + 1.0, 2), "text": " " + word() + " " + word()})  # stray segment
            t += 1.5
        i += k
    dur = round(t + rng.uniform(5, 20), 3)
    starts, anchored = ns["s1_starts"](lines, segs, dur)
    return {"lines": lines, "segments": segs, "duration_sec": dur, "expected_starts": [float(x) for x in starts],
            "expected_anchored": [bool(a) for a in anchored]}


cases = [case(n, m) for n, m in [(4, "plain"), (12, "plain"), (30, "repeats"), (45, "plain"), (60, "repeats"), (25, "plain")]]
# Edge: nothing heard; one segment only; lines that normalise to nothing.
cases.append({"lines": ["ka lo mi", "ren tas"], "segments": [], "duration_sec": 30.0})
cases.append({"lines": ["ka lo mi", "...", "ren tas vo", "qui zan"], "segments": [{"start": 4.0, "end": 9.0, "text": " ka lo mi ren tas vo qui zan"}], "duration_sec": 30.0})
for c in cases[-2:]:
    s, a = ns["s1_starts"](c["lines"], c["segments"], c["duration_sec"])
    c["expected_starts"], c["expected_anchored"] = [float(x) for x in s], [bool(x) for x in a]

pairs = [("", ""), ("", "abc"), ("abc", "abc"), ("kalo mi", "ka lo mi"), ("café ñu", "cafe nu"), ("日本語", "日本"),
         ("🎵 la", "la 🎵"), ("x" * 70, "x" * 65 + "y" * 9), ("ab" * 50, "ba" * 60)]
for _ in range(20):
    a, b = line().lower(), perturb(line()).lower()
    pairs.append((a, b))
ratios = [{"a": a, "b": b, "ratio": fuzz.ratio(a, b) / 100} for a, b in pairs]
gains = [{"x": a, "y": b, "gain": ns["gain"](a, b)} for a, b in pairs]
json.dump({"source": "lyrify-bench scripts/npu_timing.py s1_starts, gain, fill (rapidfuzz %s); synthetic made-up text"
           % __import__("rapidfuzz").__version__, "cases": cases, "ratios": ratios, "gains": gains},
          open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
print(len(cases), "cases,", sum(len(c["lines"]) for c in cases), "lines,", sum(sum(c["expected_anchored"]) for c in cases), "anchored")
