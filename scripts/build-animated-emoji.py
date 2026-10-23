#!/usr/bin/env python3
"""Builds the animated emoji shipped with the apps (assets/animated-emoji).

Source: Animated Noto Emoji by Google, CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/),
animations from https://googlefonts.github.io/noto-emoji-animation/. Every animation is cut to
frames of equal length, scaled to FRAME pixels and packed into one palette PNG (a sprite sheet of
COLS frames per row). index.json says how many frames each sheet has and how long a frame lasts.
All three apps decode the same PNG and draw the frames on a timer, so no animated-image decoder
is needed.

    python -I scripts/build-animated-emoji.py [--cache DIR] [--out DIR]

Needs Pillow with WebP support. The downloads are cached; delete the cache to fetch again.
"""
import argparse
import json
import os
import sys
import tempfile
import time
import urllib.request

from PIL import Image, ImageSequence

URL = "https://fonts.gstatic.com/s/e/notoemoji/latest/{}/512.webp"
FRAME = 96
COLS = 8
STEP_MS = 66  # about 15 frames a second
MIN_FRAMES, MAX_FRAMES = 8, 48

# Noto code points of the emoji that get an animation; the order is the order of the picker.
EMOJI = """
1f600 1f603 1f604 1f601 1f606 1f605 1f602 1f923 1f62d 1f609 1f60a 1f642 1f643 1f607 1f970 1f60d 1f929 1f618 1f617 1f61a
1f972 1f60b 1f61b 1f61c 1f92a 1f61d 1f911 1f917 1f92d 1f92b 1f914 1f928 1f610 1f611 1f636 1f60f 1f612 1f644 1f62c 1f62e_200d_1f4a8
1f925 1f60c 1f614 1f62a 1f924 1f634 1f637 1f912 1f915 1f922 1f92e 1f927 1f975 1f976 1f974 1f635 1f92f 1f920 1f973 1f60e 1f913
1f615 1f61f 1f641 2639_fe0f 1f62e 1f62f 1f632 1f633 1f97a 1f626 1f627 1f628 1f630 1f625 1f622 1f62d 1f631 1f616 1f623 1f61e 1f613 1f629 1f62b
1f624 1f621 1f620 1f92c 1f608 1f480 1f4a9 1f921 1f47b 1f916 1f648 1f649 1f64a
2764_fe0f 1f9e1 1f49b 1f49a 1f499 1f49c 1f5a4 1f90d 1f494 1f495 1f49e 1f493 1f497 1f496 1f498 1f49d
1f525 2728 1f31f 1f4a5 1f4af 1f389 1f38a 1f4ac
1f44d 1f44e 1f44f 1f64f 1f64c 1f44b 1f44c 270c_fe0f 1f91e 1f91d 1f4aa 1f440 1f91f 1f918 1f919
""".split()


def key_of(noto):
    """The name used by the apps: code points in hex, joined by '-', without the variation selector FE0F."""
    return "-".join(p for p in noto.split("_") if p != "fe0f")


def download(noto, cache):
    path = os.path.join(cache, key_of(noto) + ".webp")
    if os.path.exists(path):
        return path
    for attempt in range(4):
        try:
            with urllib.request.urlopen(URL.format(noto), timeout=60) as r, open(path + ".part", "wb") as f:
                f.write(r.read())
            os.replace(path + ".part", path)
            return path
        except Exception as e:  # network trouble: wait and try again
            print(f"  {noto}: {e}", file=sys.stderr)
            time.sleep(2 + attempt * 3)
    raise SystemExit(f"cannot download {noto}")


def timeline(path):
    """(start ms, frame) for every frame of the animation and the total length."""
    frames, t = [], 0
    with Image.open(path) as im:
        for f in ImageSequence.Iterator(im):
            d = f.info.get("duration") or 40
            frames.append((t, f.convert("RGBA").copy()))
            t += d
    return frames, t


def sheet_of(path):
    frames, total = timeline(path)
    n = max(MIN_FRAMES, min(MAX_FRAMES, round(total / STEP_MS)))
    step = total / n
    picked, j = [], 0
    for i in range(n):
        at = i * step
        while j + 1 < len(frames) and frames[j + 1][0] <= at:
            j += 1
        picked.append(frames[j][1].resize((FRAME, FRAME), Image.LANCZOS))
    rows = -(-n // COLS)
    sheet = Image.new("RGBA", (COLS * FRAME, rows * FRAME), (0, 0, 0, 0))
    for i, f in enumerate(picked):
        sheet.paste(f, ((i % COLS) * FRAME, (i // COLS) * FRAME))
    return sheet.quantize(colors=128, method=Image.FASTOCTREE), n, round(step)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.path.join(tempfile.gettempdir(), "noto-animated-emoji"))
    ap.add_argument("--out", default=os.path.join(here, "..", "assets", "animated-emoji"))
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    os.makedirs(args.out, exist_ok=True)
    index, total, seen = {}, 0, set()
    for noto in EMOJI:
        key = key_of(noto)
        if key in seen:
            continue
        seen.add(key)
        sheet, n, step = sheet_of(download(noto, args.cache))
        out = os.path.join(args.out, key + ".png")
        sheet.save(out, "PNG", optimize=True)
        index[key] = {"frames": n, "ms": step}
        total += os.path.getsize(out)
        print(f"{key}: {n} frames x {step} ms, {os.path.getsize(out) // 1024} KB")
    with open(os.path.join(args.out, "index.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"size": FRAME, "cols": COLS, "emoji": index}, f, separators=(",", ":"), sort_keys=True)
    print(f"{len(index)} emoji, {total // 1024} KB")


if __name__ == "__main__":
    main()
