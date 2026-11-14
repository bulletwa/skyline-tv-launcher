#!/usr/bin/env python3
"""rectstat.py image.png scale l,t,r,b[;...]  -> bright/dark percentile luminance; rect coords are divided by scale."""
import sys; sys.path.insert(0, __file__.rsplit('/', 1)[0]); import pxl
w, h, bpp, rows = pxl.load(sys.argv[1]); sc = float(sys.argv[2]); L = []
for rect in sys.argv[3].split(';'):
    l, t, r, b = [int(int(v) / sc) for v in rect.split(',')]
    for y in range(t, min(h, b)):
        for x in range(l, min(w, r)):
            R, G, B = rows[y][x * bpp:x * bpp + 3]; L.append(0.2126 * pxl.LIN[R] + 0.7152 * pxl.LIN[G] + 0.0722 * pxl.LIN[B])
L.sort(); print('bright=%.2f dark=%.2f n=%d' % (L[int(len(L) * .85)], L[int(len(L) * .15)], len(L)))
