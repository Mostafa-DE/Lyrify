"""Golden cases for Lyrify's PlainLyricsTiming (align mode, "S4 gated by S2c") and Ctc.wholeSong: the bench's own code
(scripts/npu_timing.py s1_starts, Ctc.global_lp / global_ / windowed_on; scripts/npu_timing_report.py agreement and the
monotonic rule; scripts/ctc_align.py). Synthetic, made-up text and synthetic emissions only.

Emissions are rows of a codebook (stored once, exactly) so the Kotlin test rebuilds them bit for bit; each song stores
its frames as runs of codebook rows.

Usage (the bench's venv): timing_golden.py <bench scripts dir> <out.json>
"""
import json
import random
import sys

import numpy as np

scripts, out_path = sys.argv[1], sys.argv[2]
sys.path.insert(0, scripts)
import ctc_align  # noqa: E402
import npu_timing  # noqa: E402
import npu_timing_report  # noqa: E402

# The numbers the Kotlin port pins must be the bench's, and the recommendation's accept rule.
assert (npu_timing.GROUP, npu_timing.MARGIN, npu_timing.S4_WIN, npu_timing.S4_HOP) == (6, 5.0, 30.0, 25.0)
assert (npu_timing.TAU, npu_timing.MAX_LINES_PER_SEG, npu_timing.MAX_SEGS_PER_LINE) == (0.5, 16, 4)
assert (npu_timing_report.AGREE_WIN, npu_timing_report.MONO_MIN, npu_timing_report.MONO_STEP) == (0.5, 0.9, 0.2)
AGREE_MIN = 0.8  # recommendation.json accept.agreement_min
Ctc = npu_timing.Ctc

rng = random.Random(20261104)
SYL = ["ka", "lo", "mi", "ren", "tas", "vo", "qui", "zan", "pe", "dru", "fy", "gol", "ha", "jun", "wex", "bri", "sto", "nal"]
F = ctc_align.FRAME_SEC
C = len(ctc_align.VOCAB)


def word():
    return "".join(rng.choice(SYL) for _ in range(rng.randint(1, 3)))


def line():
    return " ".join(word() for _ in range(rng.randint(2, 6)))


# ---------- codebook ----------
def peaked(c, p):
    row = np.full(C, np.log((1 - p) / (C - 1)))
    row[c] = np.log(p)
    return row


codebook = [peaked(c, p) for p in (0.95, 0.7, 0.45) for c in range(C)]
nprng = np.random.default_rng(7)
for _ in range(48):  # music bleed: random rows, blank still likely
    z = nprng.normal(0, 1.5, C)
    z[0] += 2.0
    codebook.append(z - z.max() - np.log(np.exp(z - z.max()).sum()))
codebook = np.array(codebook)
NOISE = list(range(3 * C, len(codebook)))


def row(c, conf):
    return {"high": 0, "mid": C, "low": 2 * C}[conf] + c


def plant(n_frames, lines, starts):
    """Frame rows: blank (some noise), and each line's tokens from the frame of its start, 1-4 frames per token."""
    frames = [row(0, "high") if rng.random() < 0.85 else rng.choice(NOISE) for _ in range(n_frames)]
    tokens, first = ctc_align.encode_lines(lines)
    firsts = [k for k in first if k is not None]
    for li, k in enumerate(first):
        if k is None:
            continue
        nxt = min((x for x in firsts if x > k), default=len(tokens) + 1)
        f = int(round(starts[li] / F))
        for i in range(k, nxt - 1):
            if i > k and tokens[i] == tokens[i - 1]:
                frames[f] = row(0, "high")
                f += 1
            for _ in range(rng.randint(1, 4)):
                frames[f] = row(tokens[i], "high" if rng.random() < 0.8 else rng.choice(("mid", "low")))
                f += 1
        if nxt <= len(tokens):
            frames[f] = row(0, "high")
            frames[f + 1] = row(ctc_align.VOCAB["|"], "mid")
    return frames


def segments_for(lines, starts, shift):
    """Whisper-like segments: 1-3 lines each, starting near (shift of) the first line's start; a junk one too."""
    segs, i = [], 0
    while i < len(lines):
        k = min(len(lines) - i, rng.choice((1, 1, 2, 3)))
        text = " ".join(x for x in lines[i:i + k] if any(ch.isalpha() for ch in x))
        if text:
            ws = [w for w in text.split() if rng.random() > 0.1] or [word()]
            s = max(0.0, shift(starts[i]) + rng.uniform(-0.8, 0.3))
            e = shift(starts[i + k]) - 0.1 if i + k < len(lines) else shift(starts[-1]) + 3.0
            segs.append({"start": round(s, 3), "end": round(max(e, s), 3), "text": " " + " ".join(ws)})
        i += k
    segs.insert(rng.randrange(len(segs)), {"start": segs[0]["start"], "end": segs[0]["start"] + 0.5, "text": " ♪"})
    return segs


def timeline(n, t0, gap=(2.0, 4.5)):
    out, t = [], t0
    for _ in range(n):
        out.append(round(t, 2))
        t += rng.uniform(*gap)
    return out


def fill_json(s4):
    """recommendation.json line_without_letters: the next placed line's start, else the previous one's."""
    out = []
    for i, t in enumerate(s4):
        if t is None:
            t = next((x for x in s4[i + 1:] if x is not None), None)
            if t is None:
                t = next(x for x in reversed(s4[:i]) if x is not None)
        out.append(t)
    return out


def song(name, lines, planted, heard_shift=lambda t: t, extra=12.0):
    dur = round(max(planted) + extra, 3)
    frames = plant(int(dur / F), lines, planted)
    segs = segments_for(lines, planted, heard_shift)
    lp = codebook[np.array(frames)]
    # The bench's song_segments keeps segments with a letter or digit only.
    heard = [s for s in segs if any(ch.isalnum() for ch in s["text"])]
    s1, _ = npu_timing.s1_starts(lines, heard, dur)
    s4 = Ctc.global_(lp, lines)
    s2c_raw = Ctc.windowed_on(lp, dur, lines, s1)
    s2c = [x if x is not None else b for x, b in zip(s2c_raw, s1)]  # npu_timing_report.methods
    times = fill_json(s4)
    agreement = npu_timing_report.agreement({"s": times}, {"s": s2c})["s"]
    monotonic = float(np.mean(np.diff(times) >= npu_timing_report.MONO_STEP)) if len(times) > 1 else 1.0
    runs = []
    for r in frames:
        if runs and runs[-1][0] == r:
            runs[-1][1] += 1
        else:
            runs.append([r, 1])
    return {"name": name, "lines": lines, "segments": segs, "duration_sec": dur, "frames": runs,
            "expected_s1": [float(x) for x in s1], "expected_s4": [None if x is None else float(x) for x in s4],
            "expected_s2c": [float(x) for x in s2c], "expected_times": [float(x) for x in times],
            "expected_agreement": float(agreement), "expected_monotonic": monotonic,
            "expected_accepted": bool(agreement >= AGREE_MIN - 1e-9 and monotonic >= npu_timing_report.MONO_MIN)}


songs = []
lines = [line() for _ in range(14)]
songs.append(song("good: Whisper hears the lines where they are sung", lines, timeline(14, 8.0)))
lines = [line() for _ in range(26)]
songs.append(song("long: five S2c windows", lines, timeline(26, 15.0, gap=(2.5, 6.0))))
lines = [line() for _ in range(12)]
starts = timeline(12, 6.0)
# Whisper's segments for the second half sit 20 s late: S2c's windows there miss the singing.
songs.append(song("bad: segments far from the singing", lines, starts, lambda t: t + 20.0 if t > starts[5] else t, 30.0))
lines = [line() for _ in range(24)]
lines[7], lines[23] = "42 !!", "..."
songs.append(song("lines without letters take the next placed line's start, the last the previous one's", lines, timeline(24, 5.0)))
lines = [line() for _ in range(5)] + ["o", "a", "e", "i"] + [line() for _ in range(3)]
starts = timeline(5, 4.0)
starts += [starts[-1] + 3.0 + 0.1 * k for k in range(4)]
songs.append(song("not monotonic: four one-letter lines 0.1 s apart", lines, starts + timeline(3, starts[-1] + 2.5)))


# ---------- Ctc.global_lp: the window cuts and the trimmed concatenation, with a stand-in model ----------
class Probe:
    """A stand-in for wav2vec2: frames as its conv stack makes them; class 0 = the window's first sample / 320 + frame,
    so the result tells which window each kept frame came from."""

    def lp(self, key, wav, w0, w1):
        x = wav[int(round(w0 * 16000)): int(round(w1 * 16000))]
        n = max(0, (len(x) - 400) // 320 + 1)
        out = np.zeros((n, C))
        out[:, 0] = (float(x[0]) if len(x) else 0.0) / 320 + np.arange(n)
        return out


whole = []
for dur in (12.3, 30.0, 30.01, 55.0, 55.013, 251.5333125, 347.14):
    wav = np.arange(int(round(dur * 16000)), dtype=np.float32)
    lp = Ctc.global_lp(Probe(), "k", wav)
    ids = [int(v) for v in lp[:, 0]]
    assert all(v == int(v) for v in lp[:, 0])
    runs = []  # [first id, count] of each run of consecutive ids
    for v in ids:
        if runs and runs[-1][0] + runs[-1][1] == v:
            runs[-1][1] += 1
        else:
            runs.append([v, 1])
    whole.append({"samples": len(wav), "expected_id_runs": runs})

json.dump({"source": "lyrify-bench scripts/npu_timing.py (s1_starts, Ctc.global_lp, Ctc.global_, Ctc.windowed_on), "
                     "npu_timing_report.py (agreement, the monotonic rule), ctc_align.py; synthetic made-up text and "
                     "emissions (Lyrify tools/timing_golden.py)",
           "codebook": codebook.tolist(), "songs": songs, "whole_song": whole},
          open(out_path, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
for s in songs:
    print(f"{s['name']}: {len(s['lines'])} lines, agreement {s['expected_agreement']:.2f}, monotonic "
          f"{s['expected_monotonic']:.2f}, accepted {s['expected_accepted']}, S4 none {s['expected_s4'].count(None)}")
