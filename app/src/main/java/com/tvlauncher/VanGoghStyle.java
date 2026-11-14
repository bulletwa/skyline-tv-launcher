package com.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.Random;

/**
 * The same sky, repainted as thick oriented brush strokes in the manner of Van Gogh: swirling flow around the sun, the
 * moon and a pair of night eddies, short impasto strokes with a shadow and a highlight, concentric halo rings, and
 * every colour sampled from the classic picture, so the weather, contrast veil and text ink behave exactly as before.
 */
final class VanGoghStyle implements SkyStyle {
    private final SkyRenderer renderer = new SkyRenderer();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[3];
    private volatile long lastMs = -1;
    private volatile int lastStrokes;

    VanGoghStyle() {
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStyle(Paint.Style.STROKE);
    }

    @Override public String name() { return "vangogh"; }

    @Override public boolean painted() { return true; }

    @Override public Bitmap renderBase(SkyRenderer.Input in) {
        return paint(renderer.renderBase(in), in);
    }

    @Override public Bitmap render(SkyRenderer.Input in) {
        return paint(renderer.render(in), in);
    }

    @Override public SkyLayers.Set layers(SkyRenderer.Input in) {
        SkyLayers.Set s = SkyLayers.build(in);
        s.painted = true;
        float[][] eddies = {};
        for (int i = 0; i < s.clouds.size(); i++) s.clouds.set(i, repaintStrip(s.clouds.get(i), eddies));
        for (int i = 0; i < s.mists.size(); i++) s.mists.set(i, repaintStrip(s.mists.get(i), eddies));
        return s;
    }

    @Override public String debug() {
        return "vangogh strokes=" + lastStrokes + " paint=" + lastMs + "ms";
    }

    // ------------------------------------------------------------------ the still sky

    private Bitmap paint(Bitmap src, SkyRenderer.Input in) {
        long t0 = System.nanoTime();
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);
        foot(c, in); // the cypress goes in before the strokes so it is painted like everything else
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        float[][] eddies = eddies(in);
        Random r = new Random(5);
        lastStrokes = 0;
        pass(c, px, w, h, 13, 11f, 11f, true, eddies, r, 0, 255);   // broad impasto strokes
        pass(c, px, w, h, 8, 6f, 5.5f, false, eddies, r, 0, 255);   // fine strokes on top
        rings(c, in);
        out.setHasAlpha(false);
        lastMs = (System.nanoTime() - t0) / 1_000_000;
        return out;
    }

    // ------------------------------------------------------------------ the foot: a Saint-Remy village

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** After the 1889 Starry Night: a dark flame-shaped cypress at the left edge rising towards the sky. */
    private void foot(Canvas c, SkyRenderer.Input in) {
        final int W = SkyRenderer.W, H = SkyRenderer.H;
        double day = SkyModel.daylight(in.sunElev);
        // the cypress: a dark flame, Prussian blue-green, tip well into the sky
        Path cy = new Path();
        float cx = 46;
        cy.moveTo(cx - 38, H);
        cy.quadTo(cx - 46, 410, cx - 16, 340);
        cy.quadTo(cx - 32, 300, cx - 6, 252);
        cy.quadTo(cx + 12, 300, cx + 9, 342);
        cy.quadTo(cx + 44, 410, cx + 40, H);
        cy.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF000000 | SkyModel.mix(0x0C2230, 0x1C3A2E, day * 0.8));
        c.drawPath(cy, fill);
    }

    /** Vortex centres {x, y, radius, strength}: the sun and moon when they are up, two eddies at night. */
    private float[][] eddies(SkyRenderer.Input in) {
        WeatherState wx = in.weather;
        double night = 1 - SkyModel.smoothstep(-12, 0, in.sunElev);
        boolean wet = wx.rain || wx.snow;
        java.util.ArrayList<float[]> l = new java.util.ArrayList<>();
        if (in.sunElev > 0 && !wet) {
            l.add(new float[]{SkyRenderer.azToX(in.sunAz), SkyRenderer.elevToY(in.sunElev), 150, 0.9f});
        }
        if (in.moonElev > 2 && night > 0.3) {
            l.add(new float[]{(float) (SkyModel.trackFraction(in.moonAz) * SkyRenderer.W), SkyRenderer.elevToY(in.moonElev), 120, 0.9f});
        }
        if (night > 0.3 && !wet) {
            float s = (float) night;
            l.add(new float[]{SkyRenderer.W * 0.32f, SkyRenderer.H * 0.20f, 125, 0.9f * s});
            l.add(new float[]{SkyRenderer.W * 0.64f, SkyRenderer.H * 0.28f, 115, -0.85f * s});
        }
        return l.toArray(new float[0][]);
    }

    /** Stroke direction (radians) at a point: long waves, pulled into a spiral near each eddy. */
    private static float angle(float x, float y, float[][] eddies) {
        double a = 0.55 * Math.sin(2 * Math.PI * x / 480 + y / 70.0) + 0.35 * Math.sin(2 * Math.PI * x / 320 - y / 45.0);
        double vx = Math.cos(a), vy = Math.sin(a);
        for (float[] e : eddies) {
            float dx = x - e[0], dy = y - e[1];
            double d = Math.sqrt(dx * dx + dy * dy) + 1e-3;
            double k = Math.abs(e[3]) * Math.exp(-(d * d) / (e[2] * e[2]));
            double dir = e[3] < 0 ? -1 : 1; // two eddies turning opposite ways make the big S of the wind
            double tx = -dy / d * dir, ty = dx / d * dir; // tangent: strokes circle the eddy
            vx = vx * (1 - k) + tx * k;
            vy = vy * (1 - k) + ty * k;
        }
        return (float) Math.atan2(vy, vx);
    }

    /**
     * One layer of strokes on a jittered grid. Colour comes from the source pixel (so the weather and contrast veil are
     * kept), saturated a little, pulled towards 1889 pigments and varied per stroke; broad strokes also get a dark
     * underside and a pale ridge.
     */
    private void pass(Canvas c, int[] px, int w, int h, int step, float half, float width, boolean impasto,
                      float[][] eddies, Random r, int yOffset, int tilePeriod) {
        for (int gy = 0; gy < h + step; gy += step) {
            for (int gx = 0; gx < w + step; gx += step) {
                float x = gx + (r.nextFloat() - 0.5f) * step, y = gy + (r.nextFloat() - 0.5f) * step;
                int sx = Math.max(0, Math.min(w - 1, (int) x)), sy = Math.max(0, Math.min(h - 1, (int) y));
                int p = px[sy * w + sx];
                int alpha = p >>> 24;
                if (alpha < 12) continue;
                int lum = SkyModel.luminance(p);
                if (lum < 9 && alpha > 240) continue; // the black bottom fade needs no paint
                float ang = angle(x, y + yOffset, eddies);
                float dx = (float) Math.cos(ang) * half, dy = (float) Math.sin(ang) * half;
                Color.colorToHSV(p | 0xFF000000, hsv);
                hsv[0] = (hsv[0] + (r.nextFloat() - 0.5f) * 8 + 360) % 360;
                hsv[1] = Math.min(1f, hsv[1] * 1.22f + 0.03f);
                // 1889 pigments: blues pulled towards ultramarine / cobalt, bright yellows to chrome yellow
                if (hsv[0] > 170 && hsv[0] < 265) {
                    hsv[0] += (225 - hsv[0]) * 0.4f;
                    hsv[1] = Math.min(1f, hsv[1] * 1.12f + 0.04f);
                } else if (hsv[0] > 35 && hsv[0] < 70 && hsv[2] > 0.55f) {
                    hsv[0] = 48;
                    hsv[1] = Math.max(hsv[1], 0.75f);
                }
                hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * (0.94f + r.nextFloat() * 0.12f)));
                if (tilePeriod == 255 && y < SkyRenderer.HORIZON && hsv[2] < 0.4f) {
                    // a night sky painted in deep cobalt rather than near-black: still dark enough for white text
                    float t = 1 - hsv[2] / 0.4f;
                    hsv[0] = hsv[0] + (225 - hsv[0]) * 0.55f * t;
                    hsv[1] = Math.max(hsv[1], 0.35f + 0.3f * t);
                    hsv[2] = Math.min(0.46f, hsv[2] * 1.5f + 0.05f * t);
                }
                int base = Color.HSVToColor(alpha, hsv);
                stroke.setStrokeWidth(width);
                if (impasto) {
                    float v = hsv[2];
                    hsv[2] = v * 0.78f;
                    stroke.setColor(Color.HSVToColor(alpha * 6 / 10, hsv));
                    line(c, x - dx + 1.3f, y - dy + 1.6f, x + dx + 1.3f, y + dy + 1.6f, w, tilePeriod);
                    hsv[2] = v;
                }
                stroke.setColor(base);
                line(c, x - dx, y - dy, x + dx, y + dy, w, tilePeriod);
                if (impasto) {
                    hsv[2] = Math.min(1f, hsv[2] * 1.1f);
                    hsv[1] *= 0.85f;
                    stroke.setStrokeWidth(width * 0.28f);
                    stroke.setColor(Color.HSVToColor(alpha / 2, hsv));
                    line(c, x - dx * 0.8f - 1.8f, y - dy * 0.8f - 2.2f, x + dx * 0.8f - 1.8f, y + dy * 0.8f - 2.2f, w, tilePeriod);
                }
                lastStrokes++;
            }
        }
    }

    /** Draws a stroke, and again one tile away when it straddles the edge of a tileable strip. */
    private void line(Canvas c, float x0, float y0, float x1, float y1, int w, int tilePeriod) {
        c.drawLine(x0, y0, x1, y1, stroke);
        if (tilePeriod == 255) return; // not a tiling strip
        if (Math.min(x0, x1) < 14) c.drawLine(x0 + w, y0, x1 + w, y1, stroke);
        if (Math.max(x0, x1) > w - 14) c.drawLine(x0 - w, y0, x1 - w, y1, stroke);
    }

    /** Concentric halo rings, made of short tangential dabs, around a visible sun or moon. */
    private void rings(Canvas c, SkyRenderer.Input in) {
        WeatherState wx = in.weather;
        boolean wet = wx.rain || wx.snow;
        double cloud = Math.max(0, Math.min(1, wx.cloud / 100.0)), fog = wx.fogAmount();
        if (in.sunElev > 0 && !wet && cloud < 0.9) {
            double a = (1 - cloud) * (1 - fog) * Math.min(1, in.sunElev / 6);
            ring(c, SkyRenderer.azToX(in.sunAz), SkyRenderer.elevToY(in.sunElev), Color.rgb(255, 232, 140), a);
        }
        double night = 1 - SkyModel.smoothstep(-8, 4, in.sunElev);
        if (in.moonElev > 0 && night > 0.2 && cloud < 0.8) {
            double a = night * (1 - cloud) * (1 - fog) * SkyModel.riseFade(in.moonElev);
            ring(c, (float) (SkyModel.trackFraction(in.moonAz) * SkyRenderer.W), SkyRenderer.elevToY(in.moonElev),
                    Color.rgb(240, 238, 200), a);
        }
    }

    private void ring(Canvas c, float cx, float cy, int rgb, double amount) {
        if (amount < 0.05) return;
        c.save();
        c.clipRect(0, 0, SkyRenderer.W, SkyRenderer.HORIZON + 2);
        float[] radius = {30, 44, 60};
        double[] weight = {0.55, 0.38, 0.24};
        Random r = new Random(9);
        for (int k = 0; k < radius.length; k++) {
            int n = (int) (radius[k] * 0.55);
            for (int i = 0; i < n; i++) {
                double ang = 2 * Math.PI * (i + r.nextDouble() * 0.5) / n;
                float x = cx + (float) Math.cos(ang) * radius[k], y = cy + (float) Math.sin(ang) * radius[k];
                float tx = (float) -Math.sin(ang) * 6.5f, ty = (float) Math.cos(ang) * 6.5f;
                stroke.setStrokeWidth(4.2f - k * 0.5f);
                stroke.setColor(Color.argb((int) (255 * weight[k] * amount), Color.red(rgb), Color.green(rgb), Color.blue(rgb)));
                c.drawLine(x - tx, y - ty, x + tx, y + ty, stroke);
            }
        }
        c.restore();
    }

    // ------------------------------------------------------------------ the moving layers

    private SkyLayers.Strip repaintStrip(SkyLayers.Strip st, float[][] eddies) {
        Bitmap src = st.pixels;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Random r = new Random(17 + st.y);
        pass(c, px, w, h, 11, 9f, 9f, true, eddies, r, st.y, w);
        pass(c, px, w, h, 8, 6f, 5f, false, eddies, r, st.y, w);
        return new SkyLayers.Strip(out, st.y, st.speed);
    }
}
