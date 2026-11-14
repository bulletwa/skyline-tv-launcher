package com.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The moving parts of the sky, prepared once per sky change on a background thread so that drawing a frame is
 * only a few sideways blits. Each cloud / mist / haze layer is a tileable ARGB strip twice the screen wide with its
 * colour, strength and the bottom-of-screen fade already baked in (plain ARGB blits are Skia's fast path on this
 * TV; tinted alpha-only masks were several times slower). {@link SkyView} slides a strip along and wraps it.
 */
final class SkyLayers {
    static final int STRIP_W = SkyRenderer.W * 2;
    private static final float HORIZON = SkyRenderer.HORIZON;

    /** One drifting strip. */
    static final class Strip {
        final Bitmap pixels; // ARGB_8888, STRIP_W wide, tileable, colour + strength + fade baked in
        final int y;         // top edge on the 960x540 picture
        final float speed;   // px per second, positive = to the right

        Strip(Bitmap pixels, int y, float speed) {
            this.pixels = pixels;
            this.y = y;
            this.speed = speed;
        }
    }

    /** A batch of stars that twinkle together (same size, same phase). */
    static final class StarBatch {
        final float[] points;
        final float width, phase, rate;

        StarBatch(float[] points, float width, float phase, float rate) {
            this.points = points;
            this.width = width;
            this.phase = phase;
            this.rate = rate;
        }
    }

    /** Everything that moves in one sky. */
    static final class Set {
        final List<Strip> clouds = new ArrayList<>();   // drawn first, behind the mist
        final List<Strip> mists = new ArrayList<>();    // fog banks and smog haze, drawn over the clouds
        final List<StarBatch> stars = new ArrayList<>();
        double starAmount;
        boolean shooting;
        boolean painted; // set by the Van Gogh style: stars get a halo
    }

    private SkyLayers() {}

    static Set build(SkyRenderer.Input in) {
        WeatherState w = in.weather;
        SkyPlan plan = SkyPlan.of(in.sunElev, w);
        Set s = new Set();
        s.starAmount = plan.starAmount;
        s.shooting = plan.shooting;
        Random rnd = new Random(11);
        veil = Contrast.zoneAlphas(in);
        double day = SkyModel.daylight(in.sunElev);
        boolean wet = w.rain || w.snow;

        if (plan.starAmount > 0.02) buildStars(s, rnd);

        // ---- cloud decks: flat layers from the top down; lower ones drift faster
        if (plan.decks > 0) {
            int tint = SkyRenderer.cloudTint(in, wet);
            float bottom = HORIZON - 52;
            for (int l = 0; l < plan.decks; l++) {
                float t = (float) l / Math.max(1, plan.decks - 1);
                float cy = 10 + t * (bottom - 40);
                double dark = wet ? 0.55 + 0.45 * t : 0.75 + 0.25 * t;
                int rgb = SkyModel.scale(tint, dark);
                int y = Math.max(0, (int) cy - 65);
                int h = 130;
                Bitmap m = newStrip(h);
                Canvas c = new Canvas(m);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                int blobs = 14;
                for (int i = 0; i < blobs; i++) {
                    float bx = STRIP_W * (i + rnd.nextFloat() * 0.8f) / blobs;
                    float bw = 220 + rnd.nextFloat() * 260;
                    float bh = 26 + t * 26 + rnd.nextFloat() * 22;
                    float by = (cy - y) + (rnd.nextFloat() - 0.5f) * 30;
                    blob(c, p, bx, by, bw, bh, rgb, wet ? 0.5 : 0.32);
                }
                addStrip(s.clouds, m, y, 4f + 3f * l);
            }
        } else if (plan.puffStrips > 0 || plan.wispStrips > 0) {
            boolean wisps = plan.puffStrips == 0;
            int strips = wisps ? plan.wispStrips : plan.puffStrips;
            int per = wisps ? plan.wispsPerStrip : plan.puffsPerStrip;
            int tint = SkyRenderer.cloudTint(in, false);
            // in deep night a wisp would just be a smudge: dim it with the daylight
            double strength = wisps ? Math.min(1, day * 1.4) : 1;
            for (int l = 0; l < strips; l++) {
                int y = l == 0 ? 36 : 150, h = 150;
                Bitmap m = newStrip(h);
                Canvas c = new Canvas(m);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                // depth: the strip nearer the horizon is "further away", so its clouds are smaller, paler and
                // closer to the horizon colour (aerial perspective); each puff also gets a darker underside
                double far = l == 0 ? 0 : 0.3;
                int ltint = SkyModel.mix(tint, SkyModel.weatherSky(in.sunElev, in.morning(), w)[1], far);
                int under = SkyModel.scale(ltint, 0.72);
                float depthScale = l == 0 ? 1.12f : 0.82f;
                for (int i = 0; i < per; i++) {
                    float cx = STRIP_W * (i + rnd.nextFloat() * 0.7f) / per;
                    float cy = 50 + rnd.nextFloat() * 50;
                    float scale = ((wisps ? 1.0f : 0.7f) + rnd.nextFloat() * 0.9f) * depthScale;
                    for (int j = 0; j < 5; j++) {
                        float ox = (j - 2) * 34 * scale + (rnd.nextFloat() - 0.5f) * 20;
                        float oy = (rnd.nextFloat() - 0.5f) * 10 * scale - (j == 2 ? 8 : 0);
                        float bw = (wisps ? 150 : 110) * scale, bh = (wisps ? 26 : 38) * scale;
                        if (!wisps) blob(c, p, cx + ox, cy + oy + 9 * scale, bw, bh * 0.8f, under, 0.16 * strength);
                        blob(c, p, cx + ox, cy + oy, bw, bh, ltint, (wisps ? 0.24 : 0.34) * (1 - 0.2 * far) * strength);
                    }
                }
                addStrip(s.clouds, m, y, 5f + 4f * l);
            }
        }

        // ---- mist (fog): three overlapping, tall layers drifting at different speeds. Each one fades to nothing at its
        // top and bottom edge (so there are no visible bands or lines), they overlap heavily so they merge into one
        // veil, the highest reaches towards the top of the screen, and the colour comes from the sky behind.
        if (plan.mistStrips > 0) {
            int[] sky = SkyModel.weatherSky(in.sunElev, in.morning(), w);
            int[] ys = {0, 110, 215}, hs = {300, 300, 270};
            float[] speeds = {6f, 11f, 18f}, strength = {0.55f, 0.85f, 0.8f};
            for (int l = 0; l < plan.mistStrips; l++) {
                int tint = SkyPlan.mistColor(sky, ys[l] + hs[l] * 0.6, HORIZON, day);
                Bitmap m = newStrip(hs[l]);
                Canvas c = new Canvas(m);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                double a = 0.5 * plan.mistAlpha * strength[l];
                int blobs = 10;
                for (int i = 0; i < blobs; i++) {
                    float bx = STRIP_W * (i + rnd.nextFloat() * 0.8f) / blobs;
                    float bw = 460 + rnd.nextFloat() * 380;
                    float bh = 130 + rnd.nextFloat() * 110;
                    float by = hs[l] * (0.35f + 0.35f * rnd.nextFloat()); // mostly the lower, thicker part
                    blob(c, p, bx, by, bw, bh, tint, a);
                }
                featherVertically(m);
                addStrip(s.mists, m, ys[l], speeds[l]);
            }
        }

        // ---- smog: one slow brown haze bank low over the horizon
        if (plan.haze) {
            int tint = SkyModel.scale(0x9C6C3A, 0.25 + 0.75 * day);
            int h = 240;
            Bitmap m = newStrip(h);
            Canvas c = new Canvas(m);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < 8; i++) {
                blob(c, p, STRIP_W * (i + rnd.nextFloat() * 0.8f) / 8, h / 2f + (rnd.nextFloat() - 0.5f) * 60,
                        500 + rnd.nextFloat() * 300, 120 + rnd.nextFloat() * 70, tint, 0.5 * plan.hazeAlpha);
            }
            featherVertically(m);
            addStrip(s.mists, m, (int) HORIZON - 170, 4f);
        }
        return s;
    }

    private static Bitmap newStrip(int h) {
        return Bitmap.createBitmap(STRIP_W, h, Bitmap.Config.ARGB_8888);
    }

    /**
     * Fades a strip's top and bottom ~30% to nothing (DST_IN with a vertical alpha ramp) so it has no hard edge; where
     * strips overlap they then blend into each other.
     */
    private static void featherVertically(Bitmap pixels) {
        Canvas c = new Canvas(pixels);
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, pixels.getHeight(),
                new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[]{0f, 0.32f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, STRIP_W, pixels.getHeight(), p);
    }

    private static float[] veil = {0f, 0f, 0f}; // set by build(), which runs on one worker thread

    private static void addStrip(List<Strip> list, Bitmap pixels, int y, float speed) {
        bakeVeil(pixels, y);
        bakeFade(pixels, y);
        list.add(new Strip(pixels, y, speed));
    }

    /**
     * Darkens a strip by the same contrast veil as the sky behind it (SRC_ATOP only changes colour where the strip has
     * pixels, so its soft edges stay soft), so a bright cloud does not poke through the veil as a pale patch.
     */
    private static void bakeVeil(Bitmap pixels, int y) {
        if (veil[0] <= 0.002f && veil[1] <= 0.002f) return;
        Canvas c = new Canvas(pixels);
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        p.setShader(SkyRenderer.veilGradient(veil));
        c.translate(0, -y); // the gradient is in screen coordinates
        c.drawRect(0, y, STRIP_W, y + pixels.getHeight(), p);
    }

    /**
     * Applies the bottom-of-screen fade to a strip once (DST_OUT with the same gradient the sky uses), so a strip
     * that drifts over the dock area fades into black with no per-frame work.
     */
    private static void bakeFade(Bitmap pixels, int y) {
        float top = SkyRenderer.H * 0.6f;
        if (y + pixels.getHeight() <= top) return;
        Canvas c = new Canvas(pixels);
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        p.setShader(SkyRenderer.bottomFade());
        c.translate(0, -y); // the gradient is in screen coordinates
        c.drawRect(0, top, STRIP_W, SkyRenderer.H, p);
    }

    /**
     * A soft elliptical blob, also drawn one strip-width to each side so the strip tiles with no seam.
     */
    private static void blob(Canvas c, Paint p, float cx, float cy, float w, float h, int rgb, double alpha) {
        int r = SkyModel.red(rgb), g = SkyModel.green(rgb), b = SkyModel.blue(rgb);
        for (int k = -1; k <= 1; k++) {
            float x = cx + k * STRIP_W;
            if (x + w / 2 < 0 || x - w / 2 > STRIP_W) continue;
            c.save();
            c.translate(x, cy);
            c.scale(1f, h / w);
            p.setColor(Color.BLACK); // opaque first: a translucent leftover colour would scale the shader
            int a = (int) Math.round(255 * Math.max(0, Math.min(1, alpha)));
            p.setShader(new RadialGradient(0, 0, w / 2,
                    new int[]{Color.argb(a, r, g, b), Color.argb((int) (a * 0.55), r, g, b), Color.argb(0, r, g, b)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, w / 2, p);
            p.setShader(null);
            c.restore();
        }
    }

    /** About 190 stars, denser toward the zenith, grouped into 4 twinkle phases x 3 sizes (12 batches). */
    private static void buildStars(Set s, Random rnd) {
        int groups = 4, sizes = 3;
        List<List<Float>> pts = new ArrayList<>();
        for (int i = 0; i < groups * sizes; i++) pts.add(new ArrayList<Float>());
        for (int i = 0; i < 190; i++) {
            float x = rnd.nextFloat() * SkyRenderer.W;
            float y = rnd.nextFloat() * rnd.nextFloat() * HORIZON * 0.95f;
            float r = 0.5f + rnd.nextFloat() * rnd.nextFloat() * 1.4f;
            int size = r < 0.9f ? 0 : r < 1.5f ? 1 : 2;
            List<Float> l = pts.get((i % groups) * sizes + size);
            l.add(x);
            l.add(y);
        }
        float[] widths = {1.2f, 2.0f, 2.8f};
        for (int g = 0; g < groups; g++) {
            float phase = g * 1.7f + rnd.nextFloat();
            float rate = 1.1f + g * 0.55f; // different groups twinkle at different speeds
            for (int z = 0; z < sizes; z++) {
                List<Float> l = pts.get(g * sizes + z);
                float[] a = new float[l.size()];
                for (int i = 0; i < a.length; i++) a[i] = l.get(i);
                if (a.length > 0) s.stars.add(new StarBatch(a, widths[z], phase, rate));
            }
        }
    }
}
