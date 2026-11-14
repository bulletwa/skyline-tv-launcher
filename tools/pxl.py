#!/usr/bin/env python3
"""Luminance percentiles of screen rectangles in a PNG screenshot (pure python, no PIL).
usage: pxl.py shot.png l,t,r,b[;l,t,r,b...]   -> bright(p85) dark(p15) over all rects, ignoring pixels that are near-pure ink"""
import sys, zlib, struct

def load(path):
    d = open(path, 'rb').read()
    pos, idat = 8, b''
    while pos < len(d):
        n, t = struct.unpack('>I4s', d[pos:pos + 8])
        body = d[pos + 8:pos + 8 + n]
        if t == b'IHDR': w, h, bd, ct = struct.unpack('>IIBB', body[:10])
        if t == b'IDAT': idat += body
        pos += 12 + n
    raw = zlib.decompress(idat)
    bpp = {2: 3, 6: 4}[ct]
    stride = w * bpp
    rows, prev = [], bytearray(stride)
    i = 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]; c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line); prev = line
    return w, h, bpp, rows

def lin(v):
    c = v / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

LIN = [lin(i) for i in range(256)]

if __name__ == '__main__':
    w, h, bpp, rows = load(sys.argv[1])
    lums = []
    for rect in sys.argv[2].split(';'):
        l, t, r, b = [int(v) for v in rect.split(',')]
        for y in range(max(0, t), min(h, b), 2):
            row = rows[y]
            for x in range(max(0, l), min(w, r), 2):
                R, G, B = row[x * bpp], row[x * bpp + 1], row[x * bpp + 2]
                L = 0.2126 * LIN[R] + 0.7152 * LIN[G] + 0.0722 * LIN[B]
                if min(R, G, B) > 247 or L < 0.012: continue  # the ink itself
                lums.append(L)
    lums.sort()
    if not lums: print('no pixels'); sys.exit()
    print('bright=%.2f dark=%.2f n=%d' % (lums[int(len(lums) * .85)], lums[int(len(lums) * .15)], len(lums)))
