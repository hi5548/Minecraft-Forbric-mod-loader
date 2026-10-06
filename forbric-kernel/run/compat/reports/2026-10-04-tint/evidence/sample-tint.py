#!/usr/bin/env python3
"""Pixel-sample a Forbric client screenshot for the colour fix, stdlib only.

Decodes an 8-bit non-interlaced PNG (what Minecraft's Screenshot.grab writes) and reports the two
colour families the fix moves between:

  grass_green : G >= R+12 and G >= B+12 and G >= 60   -- a tinted grass/foliage top
  grey_tex    : |R-G| <= 8 and |G-B| <= 8 and 60 <= G <= 200  -- the same texture with no tint (which
                is what the broken key produces: -1 tint leaves the greyscale texture as-is)

It also prints the modal RGB values and a DREW/BLACK verdict (a frame that is not one flat colour).

Usage: python3 sample-tint.py <screenshot.png> [more.png ...]
"""
import struct
import sys
import zlib
from collections import Counter


def decode_png(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path}: not a PNG")
    pos, idat = 8, b""
    w = h = bitd = color = None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b"IHDR":
            w, h, bitd, color, _comp, _filt, inter = struct.unpack(">IIBBBBB", chunk)
            if bitd != 8 or inter != 0:
                raise SystemExit(f"{path}: expected 8-bit non-interlaced, got bitdepth={bitd} interlace={inter}")
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
    channels = {0: 1, 2: 3, 4: 2, 6: 4}[color]
    raw = zlib.decompress(idat)
    stride = w * channels
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
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


def sample(path, step=2):
    w, h, channels, buf = decode_png(path)
    stride = w * channels
    total = green = grey = sky = 0
    green_excess = 0
    coloured = Counter()
    for y in range(0, h, step):
        row = y * stride
        for x in range(0, w, step):
            o = row + x * channels
            r, g, b = buf[o], buf[o + 1], buf[o + 2]
            total += 1
            coloured[(r, g, b)] += 1
            if b >= g and b >= r and b >= 120:
                sky += 1
            if g >= r + 12 and g >= b + 12 and g >= 60:
                green += 1
                green_excess += g - max(r, b)
            elif abs(r - g) <= 8 and abs(g - b) <= 8 and 60 <= g <= 200:
                grey += 1
    verdict = "BLACK" if len(coloured) <= 2 else "DREW"
    frac = lambda n: round(n / total, 4)
    print(f"{path}")
    print(f"  size={w}x{h} sampled={total} verdict={verdict}")
    print(f"  grass_green={green} ({frac(green)})  grey_tex={grey} ({frac(grey)})  sky={sky} ({frac(sky)})")
    print(f"  mean_green_excess_over_grass={round(green_excess / green, 1) if green else 0}")
    print(f"  top_rgb=" + ", ".join(f"({r},{g},{b})x{n}" for (r, g, b), n in coloured.most_common(6)))
    return {"file": path, "verdict": verdict, "green": green, "green_frac": frac(green),
            "grey": grey, "grey_frac": frac(grey), "sky_frac": frac(sky),
            "mean_green_excess": round(green_excess / green, 1) if green else 0}


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    for arg in sys.argv[1:]:
        sample(arg)
