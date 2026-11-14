package com.tvlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Numbers behind the rain / snow / lightning layer. Pure Java so the shapes and timings can be tested. */
final class Precip {
    private Precip() {}

    /** Software canvas on a cheap ARM core: ~100 lines cost ~3 ms, so keep the totals modest. */
    static final int MAX_PARTICLES = 330;

    static int rainCount(double intensity) {
        return (int) Math.round(Math.min(MAX_PARTICLES, 70 + 250 * clamp01(intensity)));
    }

    static int snowCount(double intensity) {
        return (int) Math.round(Math.min(MAX_PARTICLES, 60 + 200 * clamp01(intensity)));
    }

    static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    // ------------------------------------------------------------------ lightning timing

    /** Time until the next strike in storms: 5-15 s. */
    static long nextStrikeDelayMs(Random r) {
        return 5000 + (long) (r.nextDouble() * 10000);
    }

    /**
     * Full-screen flash brightness 0..1 at {@code elapsedMs} after a strike starts: a short flicker, a
     * dark beat, a stronger second flash, then a decay.
     */
    static double flashAlpha(long elapsedMs) {
        if (elapsedMs < 0) return 0;
        if (elapsedMs < 70) return 0.55;
        if (elapsedMs < 140) return 0.06;
        if (elapsedMs < 230) return 0.9;
        if (elapsedMs < 650) return 0.9 * (1 - (elapsedMs - 230) / 420.0);
        return 0;
    }

    /** The bolt itself stays visible a little longer than the flash. */
    static double boltAlpha(long elapsedMs) {
        if (elapsedMs < 0 || elapsedMs > 480) return 0;
        if (elapsedMs < 230) return 1;
        return 1 - (elapsedMs - 230) / 250.0;
    }

    // ------------------------------------------------------------------ bolt shape

    /** One straight piece of the bolt. */
    static final class Segment {
        final float x1, y1, x2, y2;
        /** 0 = main trunk, 1 = fork. */
        final int kind;

        Segment(float x1, float y1, float x2, float y2, int kind) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.kind = kind;
        }
    }

    /**
     * A jagged trunk from the top of the picture down to {@code endY}, with two to three forks that
     * branch off downwards and sideways.
     */
    static List<Segment> bolt(Random r, int width, int endY) {
        List<Segment> out = new ArrayList<>();
        float x = width * (0.2f + 0.6f * r.nextFloat());
        float y = 0;
        List<float[]> joints = new ArrayList<>();
        while (y < endY) {
            float step = 22 + r.nextFloat() * 18;
            float nx = x + (r.nextFloat() - 0.5f) * 40;
            nx = Math.max(10, Math.min(width - 10, nx));
            float ny = Math.min(endY, y + step);
            out.add(new Segment(x, y, nx, ny, 0));
            joints.add(new float[]{nx, ny});
            x = nx;
            y = ny;
        }
        int forks = 2 + r.nextInt(2);
        for (int f = 0; f < forks && joints.size() > 4; f++) {
            float[] start = joints.get(2 + r.nextInt(joints.size() - 4));
            float dir = r.nextBoolean() ? 1 : -1;
            float fx = start[0], fy = start[1];
            int pieces = 3 + r.nextInt(4);
            for (int i = 0; i < pieces; i++) {
                float nx = fx + dir * (10 + r.nextFloat() * 22) + (r.nextFloat() - 0.5f) * 10;
                float ny = fy + 14 + r.nextFloat() * 20;
                nx = Math.max(0, Math.min(width, nx));
                if (ny > endY) break;
                out.add(new Segment(fx, fy, nx, ny, 1));
                fx = nx;
                fy = ny;
            }
        }
        return out;
    }
}
