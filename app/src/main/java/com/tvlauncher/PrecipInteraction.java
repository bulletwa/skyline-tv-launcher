package com.tvlauncher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * Rain and snow meeting the cards and the dock (solid rounded rectangles): splashes, snow that lands and piles up in
 * smooth drifts, and a thin water film that runs over them and drips off the edges. Drawn by {@link SkyView}'s thread.
 */
final class PrecipInteraction {
    private static final int W = SkyRenderer.W, H = SkyRenderer.H;
    private final SkyView v;

    PrecipInteraction(SkyView v) {
        this.v = v;
    }

    void initPaints() {
        splashPaint.setStrokeCap(Paint.Cap.ROUND);
        pilePaint.setStyle(Paint.Style.FILL);
        pilePaint.setColor(android.graphics.Color.argb(240, 248, 250, 255));
    }

    // ---- the cards and the dock as solid objects: rain splashes on them, snow lands and piles up on them
    static final float BIN = 3f, MAX_PILE = 6f;

    static final class Shape {
        final float[] o;
        final float[] openRain, openStorm, openSnow; // gaps the weather can fall through, per drift (see respawnX)
        final float[][] surf;   // height of each object's top at the centre of every 3 px bin (NaN where none)
        float[][] pile;         // snow depth per bin; written only by the drawing thread
        float[][] water;        // rain film depth per bin; also drawing thread only

        Shape(float[] o) {
            this.o = o;
            openRain = Obstacles.openIntervals(o, 0.16f);
            openStorm = Obstacles.openIntervals(o, 0.28f);
            openSnow = Obstacles.openIntervals(o, 0.12f);
            int n = Obstacles.count(o);
            surf = new float[n][];
            pile = new float[n][];
            water = new float[n][];
            for (int i = 0; i < n; i++) {
                float l = o[i * Obstacles.STRIDE], r = o[i * Obstacles.STRIDE + 2];
                int bins = (int) Math.ceil((r - l) / BIN) + 1;
                surf[i] = new float[bins];
                pile[i] = new float[bins];
                water[i] = new float[bins];
                for (int b = 0; b < bins; b++) surf[i][b] = Obstacles.surfaceY(o, i, Math.min(r, l + (b + 0.5f) * BIN));
            }
        }
    }

    volatile Shape shape = new Shape(new float[0]);

    /**
     * The cards and dock on screen, as rounded rectangles (left, top, right, bottom, radius) in the sky's 960x540
     * coordinates. Rain splashes on their tops and snow lands and piles up on them.
     */
    void setObstacles(float[] o) {
        Shape old = shape;
        Shape n = new Shape(o);
        if (Obstacles.sameShape(old.o, o, 14f) && sameSizes(old.pile, n.pile)) { // same objects: keep the snow and the water on them
            n.pile = old.pile;
            n.water = old.water;
        }
        shape = n;
    }

    static boolean sameSizes(float[][] a, float[][] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i].length != b[i].length) return false;
        return true;
    }

    final float[] landY = new float[1];
    static final int MAX_SPLASH = 160;
    final float[] sx = new float[MAX_SPLASH], sy = new float[MAX_SPLASH], svx = new float[MAX_SPLASH],
            svy = new float[MAX_SPLASH], sl = new float[MAX_SPLASH];
    final float[] splashBright = new float[MAX_SPLASH * 2], splashDim = new float[MAX_SPLASH * 2];
    final float[] pileLines = new float[4 * 2400];
    final Paint splashPaint = new Paint(), pilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    boolean pilesDirty;

    /** A little burst where a raindrop hits something: two droplets thrown up and sideways. */
    void splash(float x, float y) {
        if (v.rnd.nextFloat() > 0.6f) return; // not every drop: keeps the particle count (and the cost) down
        for (int k = 0; k < 2; k++) {
            for (int i = 0; i < MAX_SPLASH; i++) {
                if (sl[i] > 0) continue;
                sx[i] = x;
                sy[i] = y - 0.5f;
                svx[i] = (v.rnd.nextFloat() - 0.5f) * 70f;
                svy[i] = -(35f + v.rnd.nextFloat() * 55f);
                sl[i] = 0.22f + v.rnd.nextFloat() * 0.1f;
                break;
            }
        }
    }

    void drawSplashes(Canvas c, float dt, float lit) {
        int nb = 0, nd = 0;
        for (int i = 0; i < MAX_SPLASH; i++) {
            if (sl[i] <= 0) continue;
            sl[i] -= dt;
            svy[i] += 420f * dt; // gravity
            sx[i] += svx[i] * dt;
            sy[i] += svy[i] * dt;
            if (sl[i] <= 0) continue;
            if (sl[i] > 0.11f) {
                splashBright[nb++] = sx[i];
                splashBright[nb++] = sy[i];
            } else {
                splashDim[nd++] = sx[i];
                splashDim[nd++] = sy[i];
            }
        }
        float bright = 0.5f + 0.5f * lit;
        splashPaint.setStrokeWidth(1.6f);
        if (nb > 0) {
            splashPaint.setColor(Color.argb((int) (210 * bright), 225, 236, 250));
            c.drawPoints(splashBright, 0, nb, splashPaint);
        }
        if (nd > 0) {
            splashPaint.setColor(Color.argb((int) (110 * bright), 225, 236, 250));
            c.drawPoints(splashDim, 0, nd, splashPaint);
        }
    }

    /** A flake lands: it adds to the drift under it and a little to the neighbours. */
    void addSnow(Shape sh, int obstacle, float x) {
        float l = sh.o[obstacle * Obstacles.STRIDE];
        float[] pile = sh.pile[obstacle];
        int b = Math.max(0, Math.min(pile.length - 1, (int) ((x - l) / BIN)));
        pile[b] = Math.min(MAX_PILE, pile[b] + 1.4f);
        if (b > 0) pile[b - 1] = Math.min(MAX_PILE, pile[b - 1] + 0.5f);
        if (b < pile.length - 1) pile[b + 1] = Math.min(MAX_PILE, pile[b + 1] + 0.5f);
        pilesDirty = true;
    }

    final android.graphics.Path pilePath = new android.graphics.Path();
    final float[] ph = new float[1400]; // smoothed drift height per bin for one object (up to ~4000 px wide)

    /**
     * Smooths and slowly melts the drifts, then draws each as one smooth, anti-aliased shape that follows the top of
     * its card or the dock, with a soft darker edge underneath so the snow has some volume.
     */
    void drawPiles(Canvas c, Shape sh, float dt) {
        float melt = 0.02f * dt;
        for (int i = 0; i < sh.pile.length; i++) {
            float[] p = sh.pile[i], sf = sh.surf[i];
            float l = sh.o[i * Obstacles.STRIDE];
            int n = p.length;
            boolean any = false;
            for (int b = 0; b < n; b++) {
                float left = b > 0 ? p[b - 1] : p[b], right = b < n - 1 ? p[b + 1] : p[b];
                p[b] = Math.max(0f, p[b] + (left + right - 2 * p[b]) * 0.08f - melt); // slumps flat, melts slowly
                if (p[b] > 0.2f) any = true;
            }
            if (!any || n > ph.length) continue;
            // a second smoothing pass for drawing only, so the outline has no steps at all
            for (int b = 0; b < n; b++) {
                float a1 = b > 0 ? p[b - 1] : p[b], a2 = b < n - 1 ? p[b + 1] : p[b];
                ph[b] = p[b] * 0.5f + (a1 + a2) * 0.25f;
            }
            // each run of bins that has snow becomes one closed shape: the smoothed snow line on top, the object's
            // exact surface along the bottom
            int b0 = 0;
            while (b0 < n) {
                if (!(ph[b0] > 0.2f && !Float.isNaN(sf[b0]))) { b0++; continue; }
                int b1 = b0;
                while (b1 + 1 < n && ph[b1 + 1] > 0.2f && !Float.isNaN(sf[b1 + 1])) b1++;
                pilePath.rewind();
                float x0 = l + (b0 + 0.5f) * BIN;
                pilePath.moveTo(x0, sf[b0] + 0.6f);
                pilePath.lineTo(x0, sf[b0] - ph[b0]);
                for (int b = b0 + 1; b <= b1; b++) {
                    float px = l + (b - 0.5f) * BIN, x = l + (b + 0.5f) * BIN;
                    float ptop = sf[b - 1] - ph[b - 1], top = sf[b] - ph[b];
                    pilePath.quadTo(px, ptop, (px + x) / 2f, (ptop + top) / 2f); // rounded between neighbours
                }
                pilePath.lineTo(l + (b1 + 0.5f) * BIN, sf[b1] - ph[b1]);
                for (int b = b1; b >= b0; b--) pilePath.lineTo(l + (b + 0.5f) * BIN, sf[b] + 0.6f);
                pilePath.close();
                c.drawPath(pilePath, pilePaint);
                b0 = b1 + 1;
            }
        }
    }

    // ---- rain collects on the cards and the dock as a thin film, runs over them and drips off their edges
    static final int MAX_DRIP = 48;
    final float[] dx = new float[MAX_DRIP], dy = new float[MAX_DRIP], dvx = new float[MAX_DRIP],
            dvy = new float[MAX_DRIP];
    final boolean[] dAlive = new boolean[MAX_DRIP];
    final float[][] dripAcc = new float[16][2];
    final float[] dripOut = new float[2];
    final float[] dripLines = new float[MAX_DRIP * 4];
    final android.graphics.Paint waterPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    final float[] filmThin = new float[4 * 700], filmThick = new float[4 * 700];
    boolean waterDirty;
    // statistics for the log: where the weather actually lands
    int hitsDock, hitsCards, hitsDrip;

    /** Debug dump: live splash droplets and the deepest snow pile / water film right now (cells = 3 px bins). */
    String debug() {
        int splashes = 0;
        for (int i = 0; i < MAX_SPLASH; i++) if (sl[i] > 0) splashes++;
        float pile = 0, water = 0;
        Shape sh = shape;
        for (float[] p : sh.pile == null ? new float[0][] : sh.pile) for (float x : p) pile = Math.max(pile, x);
        for (float[] p : sh.water == null ? new float[0][] : sh.water) for (float x : p) water = Math.max(water, x);
        return String.format(java.util.Locale.ENGLISH, "fx splashes=%d maxPile=%.1fpx maxFilm=%.1fpx landed dock=%d cards=%d drips=%d",
                splashes, pile, water, hitsDock, hitsCards, hitsDrip);
    }

    void resetHits() {
        hitsDock = hitsCards = hitsDrip = 0;
    }

    void countHit(Shape sh, int obstacle) {
        if (obstacle == Obstacles.dockIndex(sh.o)) hitsDock++; else hitsCards++;
    }

    /** A raindrop hit an object: some of it stays as a film. */
    void addWater(Shape sh, int obstacle, float x, float amount) {
        float l = sh.o[obstacle * Obstacles.STRIDE];
        float[] w = sh.water[obstacle];
        int b = Math.max(0, Math.min(w.length - 1, (int) ((x - l) / BIN)));
        w[b] = Math.min(WaterFlow.MAX_DEPTH, w[b] + amount);
        waterDirty = true;
    }

    /** Where particles that hit something start again: a share of them above the gaps and margins, so the dock gets weather too. */
    float respawnX(float[] open, float share, float fallback) {
        if (open.length > 0 && v.rnd.nextFloat() < share) {
            float x = Obstacles.sampleFrom(open, v.rnd.nextFloat());
            if (!Float.isNaN(x)) return x;
        }
        return fallback;
    }

    void spawnDrip(float x, float y, float side) {
        for (int i = 0; i < MAX_DRIP; i++) {
            if (dAlive[i]) continue;
            dAlive[i] = true;
            dx[i] = x;
            dy[i] = y;
            dvx[i] = side * (6f + v.rnd.nextFloat() * 10f) + v.wind * 40f;
            dvy[i] = 25f;
            return;
        }
    }

    /**
     * One frame of water: the film flows (downhill, with the v.wind) and drips off the ends; the drips fall, and land on
     * whatever is below (a card's drips usually reach the dock). Then the film and the drips are drawn.
     */
    void waterPass(Canvas c, Shape sh, float dt, float windPush) {
        float evap = v.mode == SkyView.Mode.RAIN ? 0.10f : 0.5f; // dries off quickly once the rain stops
        boolean any = false;
        int nf = 0, nk = 0;
        for (int i = 0; i < sh.water.length && i < dripAcc.length; i++) {
            float[] w = sh.water[i], sf = sh.surf[i];
            float l = sh.o[i * Obstacles.STRIDE], r = sh.o[i * Obstacles.STRIDE + 2];
            dripOut[0] = dripOut[1] = 0;
            WaterFlow.step(w, sf, dt, windPush, evap, dripOut);
            for (int side = 0; side < 2; side++) {
                dripAcc[i][side] += dripOut[side];
                while (dripAcc[i][side] >= 0.9f) {
                    dripAcc[i][side] -= 0.9f;
                    int e = side == 0 ? 0 : w.length - 1;
                    float ey = Float.isNaN(sf[e]) ? sh.o[i * Obstacles.STRIDE + 3] : sf[e];
                    spawnDrip(side == 0 ? l - 0.5f : r + 0.5f, ey, side == 0 ? -1f : 1f);
                }
            }
            // draw the film as short segments along the surface, thicker where there is more water
            for (int b = 0; b + 1 < w.length; b++) {
                float a = w[b], bb = w[b + 1];
                if (a < 0.08f && bb < 0.08f) continue;
                any = true;
                if (Float.isNaN(sf[b]) || Float.isNaN(sf[b + 1])) continue;
                float x0 = l + (b + 0.5f) * BIN, x1 = x0 + BIN;
                float y0 = sf[b] - Math.min(a, 1.6f) * 0.45f, y1 = sf[b + 1] - Math.min(bb, 1.6f) * 0.45f;
                if ((a + bb) * 0.5f > 0.9f) {
                    if (nk + 4 <= filmThick.length) { filmThick[nk++] = x0; filmThick[nk++] = y0; filmThick[nk++] = x1; filmThick[nk++] = y1; }
                } else if (nf + 4 <= filmThin.length) {
                    filmThin[nf++] = x0; filmThin[nf++] = y0; filmThin[nf++] = x1; filmThin[nf++] = y1;
                }
            }
        }
        if (nf > 0) {
            waterPaint.setStrokeWidth(1.1f);
            waterPaint.setColor(Color.argb(150, 190, 215, 245));
            c.drawLines(filmThin, 0, nf, waterPaint);
        }
        if (nk > 0) {
            waterPaint.setStrokeWidth(2.2f);
            waterPaint.setColor(Color.argb(190, 205, 226, 250));
            c.drawLines(filmThick, 0, nk, waterPaint);
        }
        // the drips
        int nd = 0;
        float[] o = sh.o;
        for (int i = 0; i < MAX_DRIP; i++) {
            if (!dAlive[i]) continue;
            any = true;
            float y0 = dy[i];
            dvy[i] += 520f * dt;
            dy[i] += dvy[i] * dt;
            dx[i] += dvx[i] * dt;
            dvx[i] *= 1f - Math.min(1f, 2f * dt);
            int hit = o.length > 0 ? Obstacles.landing(o, dx[i], y0, dy[i], landY) : -1;
            if (hit >= 0) {
                hitsDrip++;
                splash(dx[i], landY[0]);
                addWater(sh, hit, dx[i], 0.3f);
                dAlive[i] = false;
            } else if (dy[i] > H + 10) {
                dAlive[i] = false;
            } else {
                dripLines[nd++] = dx[i];
                dripLines[nd++] = dy[i];
                dripLines[nd++] = dx[i] - dvx[i] * 0.012f;
                dripLines[nd++] = dy[i] - Math.min(9f, dvy[i] * 0.02f);
            }
        }
        if (nd > 0) {
            waterPaint.setStrokeWidth(1.5f);
            waterPaint.setColor(Color.argb(215, 205, 222, 245));
            c.drawLines(dripLines, 0, nd, waterPaint);
        }
        waterDirty = any;
    }

    /** Snow melts away when it stops snowing; splashes stop when it stops raining. */
    void clearPrecipState() {
        if (pilesDirty) {
            for (float[] p : shape.pile) java.util.Arrays.fill(p, 0f);
            pilesDirty = false;
        }
        if (v.mode != SkyView.Mode.RAIN) for (int i = 0; i < MAX_SPLASH; i++) sl[i] = 0;
        if (v.mode == SkyView.Mode.SNOW) {
            for (float[] w : shape.water) java.util.Arrays.fill(w, 0f);
            for (int i = 0; i < MAX_DRIP; i++) dAlive[i] = false;
            waterDirty = false;
        }
    }
}
