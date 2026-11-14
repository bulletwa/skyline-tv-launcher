package com.tvlauncher;

import java.util.List;

/**
 * The cards and the dock as solid objects for falling rain and snow. Each obstacle is a rounded rectangle given as
 * five numbers (left, top, right, bottom, corner radius) in the sky's 960x540 coordinates, flattened into one array.
 * Pure Java so the maths can be tested.
 */
final class Obstacles {
    private Obstacles() {}

    static final int STRIDE = 5;

    static float[] flatten(List<float[]> rects) {
        float[] out = new float[rects.size() * STRIDE];
        for (int i = 0; i < rects.size(); i++) System.arraycopy(rects.get(i), 0, out, i * STRIDE, STRIDE);
        return out;
    }

    static int count(float[] o) {
        return o.length / STRIDE;
    }

    /** Height of obstacle {@code i}'s top surface at {@code x}, following its rounded corners; NaN outside it. */
    static float surfaceY(float[] o, int i, float x) {
        int k = i * STRIDE;
        float l = o[k], t = o[k + 1], r = o[k + 2], b = o[k + 3];
        if (x < l || x > r) return Float.NaN;
        float rad = Math.max(0f, Math.min(o[k + 4], Math.min((r - l) / 2f, (b - t) / 2f)));
        if (rad > 0f) {
            float cx = x < l + rad ? l + rad : x > r - rad ? r - rad : x;
            float dx = x - cx;
            if (dx != 0f) return t + rad - (float) Math.sqrt(Math.max(0f, rad * rad - dx * dx));
        }
        return t;
    }

    /**
     * Where something falling from {@code yPrev} to {@code yNow} at {@code x} first lands on an obstacle's top.
     *
     * @param out receives the landing height in out[0]
     * @return the obstacle's index, or -1 if nothing was hit this step
     */
    static int landing(float[] o, float x, float yPrev, float yNow, float[] out) {
        int hit = -1;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < count(o); i++) {
            float sy = surfaceY(o, i, x);
            if (Float.isNaN(sy)) continue;
            if (yPrev < sy && sy <= yNow && sy < best) {
                best = sy;
                hit = i;
            }
        }
        if (hit >= 0) out[0] = best;
        return hit;
    }

    /** The dock is the lowest object on screen (largest top); -1 when there are none. */
    static int dockIndex(float[] o) {
        int best = -1;
        float top = -1;
        for (int i = 0; i < count(o); i++) {
            if (o[i * STRIDE + 1] > top) {
                top = o[i * STRIDE + 1];
                best = i;
            }
        }
        return best;
    }

    /**
     * An x at the top of the screen from which a particle falling with horizontal drift {@code slant} (px sideways per
     * px down) clears every card above the dock and lands on the dock: i.e. it falls through a gap or past the side of
     * the cards. {@code u} in [0,1) picks uniformly along the open stretches. NaN when the cards cover the whole dock.
     */
    static float openSample(float[] o, float slant, float u) {
        return sampleFrom(openIntervals(o, slant), u);
    }

    /**
     * The open stretches (left, right, left, right ...) at the top of the screen for a given drift, computed once per
     * layout so that picking a respawn position (hundreds of times a second) allocates nothing.
     */
    static float[] openIntervals(float[] o, float slant) {
        int dock = dockIndex(o);
        if (dock < 0) return new float[0];
        float dTop = o[dock * STRIDE + 1];
        float lo = o[dock * STRIDE] - slant * dTop, hi = o[dock * STRIDE + 2] - slant * dTop;
        int n = count(o), m = 0;
        float[] bl = new float[n], br = new float[n];
        for (int i = 0; i < n; i++) {
            if (i == dock || o[i * STRIDE + 1] >= dTop - 1f) continue;
            float shift = slant * o[i * STRIDE + 1];
            bl[m] = o[i * STRIDE] - shift;
            br[m] = o[i * STRIDE + 2] - shift;
            m++;
        }
        for (int a = 1; a < m; a++) { // sort the blocked stretches by their left end (tiny n)
            float kl = bl[a], kr = br[a];
            int b = a - 1;
            while (b >= 0 && bl[b] > kl) { bl[b + 1] = bl[b]; br[b + 1] = br[b]; b--; }
            bl[b + 1] = kl;
            br[b + 1] = kr;
        }
        float[] g = new float[(m + 1) * 2];
        int k = 0;
        float cursor = lo;
        for (int a = 0; a < m; a++) {
            if (bl[a] > cursor) {
                float r = Math.min(bl[a], hi);
                if (r > cursor) { g[k++] = cursor; g[k++] = r; }
            }
            cursor = Math.max(cursor, br[a]);
            if (cursor >= hi) break;
        }
        if (cursor < hi) { g[k++] = cursor; g[k++] = hi; }
        float total = 0;
        for (int a = 0; a < k; a += 2) total += g[a + 1] - g[a];
        if (total <= 0.5f) return new float[0];
        return java.util.Arrays.copyOf(g, k);
    }

    /** A position chosen uniformly along the open stretches (u in [0,1)); NaN when there are none. */
    static float sampleFrom(float[] intervals, float u) {
        float total = 0;
        for (int a = 0; a < intervals.length; a += 2) total += intervals[a + 1] - intervals[a];
        if (total <= 0.5f) return Float.NaN;
        float at = Math.max(0f, Math.min(0.9999f, u)) * total;
        for (int a = 0; a < intervals.length; a += 2) {
            float len = intervals[a + 1] - intervals[a];
            if (at < len) return intervals[a] + at;
            at -= len;
        }
        return intervals[intervals.length - 2];
    }

    /** True when two sets of obstacles are the same objects in (nearly) the same places. */
    static boolean sameShape(float[] a, float[] b, float tolerance) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > tolerance) return false;
        return true;
    }
}
