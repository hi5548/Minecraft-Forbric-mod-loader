#!/usr/bin/env python3
"""PNG decoder (stdlib) + pixel classifiers for the 2026-10-04-tint-repro lane.

Classes (per pixel, on the raw RGB):
  black        : max(R,G,B) < 40                      -- a surface that should never be black here
  grass_green  : G >= R+12 and G >= B+12 and G >= 60   -- tinted grass/foliage top
  dirt_brown   : R > G >= B and R >= 60 and R-B >= 25 and not near-black -- untinted dirt
  grey_tex     : |R-G| <= 8 and |G-B| <= 8 and 60 <= G <= 210 -- untinted greyscale texture (grass_block_top with no tint)
  sky          : B > R and B > G and B >= 120
  other        : everything else

ROIs are given as x0,y0,x1,y1 in screenshot pixels (inclusive-exclusive), or as a fraction of width/height.
"""
import struct
import sys
import zlib
from collections import Counter


def decode_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    pos = 8
    idat = b""
    w = h = bitd = color = None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bitd, color = struct.unpack(">IIBB", chunk[:10])
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
        pos += 12 + ln
    assert bitd == 8, bitd
    channels = {0: 1, 2: 3, 4: 2, 6: 4}[color]
    raw = zlib.decompress(idat)
    stride = w * channels
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        if ft == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 255
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif ft == 3:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif ft == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, channels, out


def classify(r, g, b):
    if max(r, g, b) < 40:
        return "black"
    if b > r and b > g and b >= 120:
        return "sky"
    if g >= r + 12 and g >= b + 12 and g >= 60:
        return "grass_green"
    if r > g >= b and r >= 60 and (r - b) >= 25:
        return "dirt_brown"
    if abs(r - g) <= 8 and abs(g - b) <= 8 and 60 <= g <= 210:
        return "grey_tex"
    return "other"


def roi_box(w, h, spec):
    parts = [float(x) for x in spec.split(",")]
    if all(0.0 <= x <= 1.0 for x in parts):
        x0, y0, x1, y1 = parts
        return int(x0 * w), int(y0 * h), int(x1 * w), int(y1 * h)
    return int(parts[0]), int(parts[1]), int(parts[2]), int(parts[3])


def sample(path, rois=None, step=1):
    w, h, channels, buf = decode_png(path)
    stride = w * channels
    rois = rois or {"whole": (0, 0, w, h)}
    out = {}
    for name, spec in rois.items():
        x0, y0, x1, y1 = spec if isinstance(spec, tuple) else roi_box(w, h, spec)
        counts = Counter()
        total = 0
        modal = Counter()
        rsum = gsum = bsum = 0
        for y in range(y0, y1, step):
            row = y * stride
            for x in range(x0, x1, step):
                i = row + x * channels
                r, g, b = buf[i], buf[i + 1], buf[i + 2]
                counts[classify(r, g, b)] += 1
                modal[(r, g, b)] += 1
                rsum += r; gsum += g; bsum += b
                total += 1
        frac = {k: round(v / total, 4) for k, v in counts.items()} if total else {}
        out[name] = {
            "box": (x0, y0, x1, y1), "n": total,
            "counts": dict(counts.most_common()), "frac": frac,
            "mean_rgb": (round(rsum / total, 1), round(gsum / total, 1), round(bsum / total, 1)) if total else None,
            "top_rgb": [(k, v) for k, v in modal.most_common(6)],
        }
    return w, h, out


if __name__ == "__main__":
    import json
    args = sys.argv[1:]
    rois = {}
    files = []
    for a in args:
        if "=" in a and a.split("=", 1)[0].isidentifier():
            k, v = a.split("=", 1)
            rois[k] = v
        else:
            files.append(a)
    for f in files:
        w, h, res = sample(f, rois)
        print(f"== {f} ({w}x{h})")
        for name, r in res.items():
            print(f"  {name} box={r['box']} n={r['n']} mean_rgb={r['mean_rgb']}")
            print(f"    frac={r['frac']}")
            print(f"    top_rgb={r['top_rgb']}")
