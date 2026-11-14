package com.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/** Draws the whole sky procedurally at 960x540; the ImageView scales it up. No image assets. */
final class SkyRenderer {
    static final int W = 960, H = 540;
    static final float HORIZON = 0.66f * H;

    /** Everything the picture depends on. */
    static final class Input {
        double sunElev, sunAz, moonElev, moonAz, moonPhase;
        /** 0 spring .. 3 winter: set by the controller before drawing. */
        int season = 2;
        WeatherState weather = new WeatherState();

        static Input at(long nowMs, double lat, double lon, WeatherState w) {
            Input in = new Input();
            SkyModel.Pos s = SkyModel.sun(nowMs, lat, lon), m = SkyModel.moon(nowMs, lat, lon);
            in.sunElev = s.elevation;
            in.sunAz = s.azimuth;
            in.moonElev = m.elevation;
            in.moonAz = m.azimuth;
            in.moonPhase = SkyModel.moonPhase(nowMs);
            in.weather = w;
            return in;
        }

        boolean morning() {
            return sunAz < 180;
        }
    }

    /** -1 = follow the calendar; 0..3 forces a season (Settings > Wallpaper > Season). */
    static volatile int seasonOverride = -1;

    static int currentSeason(double lat) {
        return seasonOverride >= 0 ? seasonOverride : SkyModel.season(System.currentTimeMillis(), lat);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    static float azToX(double az) {
        // Window of 240 deg centred on south so a sunrise/sunset in the east/west is still on screen.
        double t = (az - 60) / 240.0;
        return (float) (Math.max(0.04, Math.min(0.96, t)) * W);
    }

    static float elevToY(double elev) {
        if (elev <= 0) return HORIZON;
        return (float) (HORIZON - Math.min(elev, 70) / 70.0 * HORIZON * 0.88);
    }

    private static int argb(int rgb, double alpha) {
        return Color.argb((int) Math.round(255 * Math.max(0, Math.min(1, alpha))),
                SkyModel.red(rgb), SkyModel.green(rgb), SkyModel.blue(rgb));
    }

    /** The whole sky as one still picture (also what the frosted glass blurs, and what shows with motion off). */
    Bitmap render(Input in) {
        long t0 = System.nanoTime();
        lastRaysDrawn = false;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        draw(new Canvas(bmp), in, false);
        grain(bmp);
        lastBaseMs = (System.nanoTime() - t0) / 1_000_000;
        lastRays = lastRaysDrawn;
        return bmp;
    }

    /**
     * Just the parts that stay still: gradient, glow, sun, moon, horizon gap, a thin fog tint and the bottom fade
     * (baked in here once, so it costs nothing per frame). Stars, clouds and mist are left out because
     * {@link SkyView} draws them moving.
     */
    Bitmap renderBase(Input in) {
        long t0 = System.nanoTime();
        lastRaysDrawn = false;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        draw(new Canvas(bmp), in, true);
        grain(bmp);
        bmp.setHasAlpha(false); // opaque: lets the canvas copy it instead of blending it every frame
        lastBaseMs = (System.nanoTime() - t0) / 1_000_000;
        lastRays = lastRaysDrawn;
        return bmp;
    }

    /** @param base true to skip everything that moves in the animated sky */
    void draw(Canvas c, Input in, boolean base) {
        WeatherState w = in.weather;
        boolean morning = in.morning();
        int[] sky = SkyModel.weatherSky(in.sunElev, morning, w);
        double day = SkyModel.daylight(in.sunElev);
        double cloud = Math.max(0, Math.min(1, w.cloud / 100.0));
        boolean wet = w.rain || w.snow;
        Random rnd = new Random(7);

        // 1. base gradient
        p.setShader(null);
        p.setColor(Color.BLACK); // opaque first: a translucent leftover colour would scale the shader
        p.setShader(new LinearGradient(0, 0, 0, H,
                new int[]{argb(sky[0], 1), argb(sky[1], 1), argb(SkyModel.scale(sky[1], 0.8), 1)},
                new float[]{0f, HORIZON / H, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        // 2. stars
        double night = 1 - SkyModel.smoothstep(-14, -4, in.sunElev);
        // Thick cloud and rain hide the stars completely. Fog and haze only soften them: a real fog still lets a few
        // through, and a sky with nothing in it looks like a broken wallpaper on a TV.
        double clearness = (1 - Math.min(1, cloud * 1.2)) * (1 - 0.3 * w.fogAmount());
        if (!base && night * clearness > 0.02) drawStars(c, night * clearness, rnd);

        // 3. twilight / sun glow along the horizon
        double near = 1 - SkyModel.smoothstep(0, 16, Math.abs(in.sunElev + 2));
        float sunX = azToX(in.sunAz), sunY = elevToY(in.sunElev);
        double glowDim = 1 - 0.75 * cloud - 0.5 * w.fogAmount();
        if (near > 0.01) {
            int warm = SkyModel.mix(0xFF9A50, 0xFF6A3A, morning ? 0 : 0.6);
            if (in.sunElev < -3) warm = SkyModel.mix(warm, 0xC85A78, Math.min(1, (-3 - in.sunElev) / 8));
            glow(c, sunX, HORIZON, 560, 0.5f, warm, 0.8 * near * glowDim);
        }

        // 4. moon (under the clouds)
        double moonVis = (1 - SkyModel.smoothstep(-8, 4, in.sunElev)) * (1 - 0.9 * cloud) * (1 - 0.6 * w.fogAmount());
        moonVis *= SkyModel.riseFade(in.moonElev);
        if (in.moonElev > -4 && moonVis > 0.03) drawMoon(c, in, moonVis);

        // 5. sun
        boolean hidden = wet || cloud >= 0.9; // a thick deck hides the disc, leaving only a faint glow
        if (in.sunElev > -2) drawSun(c, in, sunX, sunY, glowDim, hidden);

        // 6. horizon lit gap + clouds
        if (wet || cloud > 0.8) {
            drawHorizonGap(c, in, day, wet);
            if (!base) drawFlatClouds(c, in, rnd, wet ? 5 : 3, wet);
        } else if (cloud > 0.2 && !base) {
            drawPuffs(c, in, rnd, (int) Math.round(cloud * 16));
        }

        // 7. fog + smog
        double fog = w.fogAmount();
        if (fog > 0) {
            int f = SkyModel.mix(sky[1], 0xFFFFFF, 0.12);
            p.setColor(Color.BLACK);
            p.setShader(new LinearGradient(0, 0, 0, HORIZON + 40,
                    argb(f, 0.10 * fog), argb(f, 0.8 * fog), Shader.TileMode.CLAMP)); // thin overhead, thick at the horizon
            c.drawRect(0, 0, W, H, p);
            p.setShader(null);
        }
        if (w.aqi > 100) {
            double smog = Math.min(1, (w.aqi - 100) / 200.0);
            int haze = SkyModel.scale(0x9C6C3A, 0.25 + 0.75 * day);
            p.setColor(Color.BLACK);
            p.setShader(new LinearGradient(0, HORIZON - 170, 0, HORIZON + 30,
                    new int[]{argb(haze, 0), argb(haze, 0.55 * smog), argb(haze, 0.35 * smog)},
                    new float[]{0f, 0.75f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, HORIZON - 170, W, HORIZON + 30, p);
            p.setShader(null);
        }

        // 7b. one finish for every weather: soft corner vignette and a pale haze that lifts the horizon (aerial
        //     perspective). Thick fog already is haze, so it is left alone.
        atmosphere(c, sky, fog);

        // 8. bottom ~40% fades smoothly to black so the dock stays readable. The moving layers have the same fade
        //    baked into their masks (see SkyLayers), so nothing needs to be darkened over them each frame.
        //    Instead of a flat black band the foot of the sky is two ranges of hills in the sky's own colours, fading
        //    into a deep tint of the horizon (never pure black), so there is no empty stripe under the dock.
        int[] ground = ground(c, in, sky, fog);
        p.setColor(Color.BLACK);
        p.setShader(groundFade(ground[2]));
        c.drawRect(0, H * 0.6f, W, H, p);
        p.setShader(null);

        // 9. the contrast veil: darkens bright skies behind the text. It lives in the picture, not on top of the
        //    screen, so rain, snow and lightning drawn over it stay bright.
        p.setColor(Color.BLACK);
        p.setShader(veilGradient(Contrast.zoneAlphas(in)));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
    }

    /** Black, transparent where the sky is already dark and stronger where it is bright (see {@link Contrast}). */
    private void atmosphere(Canvas c, int[] sky, double fog) {
        p.setColor(Color.BLACK);
        p.setShader(new RadialGradient(W / 2f, H * 0.42f, W * 0.72f, new int[]{Color.TRANSPARENT, Color.TRANSPARENT,
                Color.argb(70, 0, 0, 0)}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        double haze = 0.22 * (1 - Math.min(1, fog * 1.5));
        if (haze > 0.01) {
            int h = SkyModel.mix(sky[1], 0xFFFFFF, 0.18);
            p.setShader(new LinearGradient(0, HORIZON - 130, 0, HORIZON + 10,
                    new int[]{argb(h, 0), argb(h, haze)}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, HORIZON - 130, W, HORIZON + 10, p);
        }
        p.setShader(null);
    }

    /** +-3 levels of fixed noise: hides gradient banding on the TV. Baked once per picture, never per frame. */
    private static void grain(Bitmap bmp) {
        int[] px = new int[W * H];
        bmp.getPixels(px, 0, W, 0, 0, W, H);
        Random r = new Random(11);
        int[] noise = new int[1024];
        for (int i = 0; i < noise.length; i++) noise[i] = r.nextInt(7) - 3;
        for (int i = 0; i < px.length; i++) {
            int n = noise[(i * 31 + (i >> 10)) & 1023], v = px[i];
            int rr = Math.max(0, Math.min(255, ((v >> 16) & 255) + n)), g = Math.max(0, Math.min(255, ((v >> 8) & 255) + n)),
                    b = Math.max(0, Math.min(255, (v & 255) + n));
            px[i] = (v & 0xFF000000) | (rr << 16) | (g << 8) | b;
        }
        bmp.setPixels(px, 0, W, 0, 0, W, H);
    }

    static LinearGradient veilGradient(float[] a) {
        return new LinearGradient(0, 0, 0, H,
                new int[]{blackAlpha(a[0]), blackAlpha(a[0]), blackAlpha(a[1]), blackAlpha(a[2]), blackAlpha(a[2])},
                new float[]{0f, Contrast.ZONES[0], Contrast.ZONES[1], Contrast.ZONES[2], 1f}, Shader.TileMode.CLAMP);
    }

    private static int blackAlpha(float alpha) {
        return Color.argb(Math.round(255 * Math.max(0f, Math.min(1f, alpha))), 0, 0, 0);
    }

    /** The black fade over the bottom of the picture. Shared with the animated sky, which draws it over the moving layers. */
    /**
     * The foot of the picture: a jagged mountain range far away and a city skyline in front of it, both in the sky's own
     * colours (fog melts them into the haze, night lights the windows). Returns {far, near, fade-tint}.
     */
    private int[] ground(Canvas c, Input in, int[] sky, double fog) {
        int far = SkyModel.mix(sky[1], 0x2B3A4A, 0.5 - 0.3 * fog);
        int near = SkyModel.mix(sky[1], 0x141C26, 0.8 - 0.2 * fog);
        int tint = SkyModel.scale(near, 0.55);
        double day = SkyModel.daylight(in.sunElev);
        int mid = SkyModel.mix(far, near, 0.45);
        // snow on the high crests follows the season: heaviest in winter, little in summer
        double[] caps = {0.55, 0.25, 0.5, 1.0};
        mountains(c, in, HORIZON - 8, 120, 330, 31, far, 0.9 * (1 - 0.55 * fog), day, fog, caps[in.season]);  // distant, hazy
        mountains(c, in, HORIZON + 6, 78, 210, 77, mid, 0.95 * (1 - 0.35 * fog), day, fog, 0);                // nearer, darker
        nature(c, in, sky, fog, day);
        return new int[]{far, near, tint};
    }

    /** Smooth 1-D value noise: random lattice values joined with a cosine ease, so no straight zig-zag edges. */
    private static float noise(float[] lattice, float x) {
        int i = (int) Math.floor(x);
        float t = x - i;
        t = (1 - (float) Math.cos(t * Math.PI)) * 0.5f;
        float a = lattice[Math.floorMod(i, lattice.length)], b = lattice[Math.floorMod(i + 1, lattice.length)];
        return a + (b - a) * t;
    }

    /**
     * A natural ridgeline: four octaves of "ridged" noise (sharp crests, broad valleys), lit on the side facing the sun
     * or moon, darker on the other, snow on the highest crests, and a haze toward the foot.
     */
    private void mountains(Canvas c, Input in, float base, float amp, float spacing, long seed, int rgb, double alpha,
                           double day, double fog, double snowK) {
        Random r = new Random(seed);
        float[][] lat = new float[4][64];
        for (float[] l : lat) for (int i = 0; i < l.length; i++) l[i] = r.nextFloat();
        float sunX = azToX(in.sunElev > 0 || in.moonElev < 0 ? in.sunAz : in.moonAz);
        int n = W / 4 + 2;
        float[] y = new float[n];
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float h = 0, a = 1, norm = 0;
            for (int o = 0; o < 4; o++) {
                float v = 1 - Math.abs(2 * noise(lat[o], i * 4 / (spacing / (1 << o))) - 1); // ridged
                h += (float) Math.pow(v, 1.7) * a;
                norm += a;
                a *= 0.5f;
            }
            y[i] = base - (h / norm) * amp;
            lo = Math.min(lo, y[i]);
            hi = Math.max(hi, y[i]);
        }
        Path path = new Path();
        path.moveTo(0, H);
        for (int i = 0; i < n; i++) path.lineTo(i * 4, y[i]);
        path.lineTo(W + 4, H);
        path.close();
        p.setColor(Color.BLACK);
        p.setShader(new LinearGradient(0, lo, 0, base + 40,
                new int[]{argb(SkyModel.mix(rgb, 0xFFFFFF, 0.08), alpha), argb(SkyModel.scale(rgb, 0.78), alpha)},
                null, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
        c.save();
        c.clipPath(path);
        // rim light along the crest on the lit side of the sky (a thin, pale edge) gives the ridge its shape
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(argb(0xFFFFFF, (0.1 + 0.22 * day) * alpha * (1 - 0.7 * fog)));
        Path crest = new Path();
        crest.moveTo(0, y[0]);
        for (int i = 1; i < n; i++) crest.lineTo(i * 4, y[i]);
        c.drawPath(crest, p);
        p.setStyle(Paint.Style.FILL);
        if (snowK > 0) { // caps on the tallest crests only, fading out downhill
            float line = lo + (base - lo) * 0.42f;
            p.setShader(new LinearGradient(0, lo, 0, line + 14,
                    new int[]{argb(0xF4F8FF, (0.35 + 0.6 * day) * alpha * (1 - 0.7 * fog) * snowK), argb(0xF4F8FF, 0)},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, lo, W + 4, line + 14, p);
            p.setShader(null);
        }
        c.restore();
    }

    // ------------------------------------------------------------------ nature: pines and a pond, by season

    private static final int[] GROUND = {0x6FA848, 0x3F7A34, 0x9A7436, 0xE8EFF6}; // spring, summer, autumn, winter
    private static final int[] PINE = {0x2A6040, 0x1F5236, 0x2C5A3C, 0x1B3F34};

    private int tone(int col, double day, int haze, double hazeAmt) {
        int lit = SkyModel.mix(SkyModel.scale(col, 0.2 + 0.8 * day), 0x0E1630, 0.35 * (1 - day));
        return SkyModel.mix(lit, haze, Math.min(1, hazeAmt));
    }

    /**
     * From the mountains to the bottom edge: meadow (or snow) rolling down, pine trees framing a little pond in the
     * middle, in the season's colours (blossom-green spring, summer green, golden autumn, snow-laden winter with the
     * pond frozen). In clean mode the whole of it is visible; otherwise the dock covers the middle.
     */
    private void nature(Canvas c, Input in, int[] sky, double fog, double day) {
        int season = in.season;
        // {base line, scale, haze}: far pines sit in the haze, the last row's feet are off-screen
        float[][] rows = {{366, 0.5f, 0.5f}, {392, 0.72f, 0.36f}, {430, 1.0f, 0.22f}, {480, 1.45f, 0.1f}, {548, 2.2f, 0f}};
        float pondX = W * 0.5f, pondY = 472, pondRx = 175, pondRy = 24;
        for (int i = 0; i < rows.length; i++) {
            float base = rows[i][0], sc = rows[i][1];
            double haze = rows[i][2] + 0.5 * fog;
            float top = i == 0 ? HORIZON : rows[i - 1][0];
            int g = tone(SkyModel.mix(GROUND[season], 0x000000, 0.08 * i), day, sky[1], haze);
            p.setColor(Color.BLACK);
            p.setShader(new LinearGradient(0, top, 0, H,
                    new int[]{argb(g, 0.99), argb(SkyModel.scale(g, 0.85), 0.99)}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, top, W, H, p);
            p.setShader(null);
            if (i == 3) pond(c, in, sky, day, season, pondX, pondY, pondRx, pondRy);
            // nearer rows keep clear of the pond; the biggest trees frame the scene from the two edges
            float clearX = i >= 3 ? pondX : -1, clearHalf = pondRx * (i == 3 ? 1.15f : 1.6f);
            if (i >= 2) understory(c, base, sc, i, season, day, sky[1], haze, clearX, clearHalf);
            pines(c, in, base, sc, haze, i, season, day, sky[1], clearX, clearHalf);
        }
    }

    /** Groups of pines with gaps between them, in uneven heights, so a row reads as a wood and not a fence. */
    private void pines(Canvas c, Input in, float base, float sc, double haze, int row, int season, double day, int skyH,
                       float clearX, float clearHalf) {
        Random r = new Random(500 + row * 17);
        float sunX = azToX(in.sunElev > 0 ? in.sunAz : in.moonAz);
        float x = -10;
        while (x < W + 20) {
            float clump = 0.55f + 0.45f * (float) Math.sin(x / (60.0 + row * 9) + row * 2.3); // dense and sparse stretches
            float h = (30 + r.nextFloat() * 40 * (0.5f + clump)) * sc, w = (10 + r.nextFloat() * 6) * sc;
            boolean inPond = clearX >= 0 && Math.abs(x - clearX) < clearHalf;
            boolean edgeOnly = row == 4 && x > 170 && x < W - 170; // the largest pines only at the two edges
            boolean gap = clump < 0.18f && r.nextFloat() < 0.6f;
            if (!inPond && !edgeOnly && !gap) {
                pine(c, x, base + r.nextFloat() * 4 * sc, h, w, season, day, skyH, haze, r, sunX);
            }
            x += w * (0.7f + r.nextFloat() * 0.8f) * (row >= 3 ? 1.5f : 1.1f);
        }
    }

    /**
     * A pine, still a flat illustration but with real structure: a trunk, seven ragged tiers of boughs that droop at
     * the ends, a leaning tip, a ground shadow, the side away from the light in shade and a pale rim on the lit side;
     * in winter each tier carries snow with a bluish shaded side.
     */
    private void pine(Canvas c, float x, float base, float h, float hw, int season, double day, int skyH, double haze,
                      Random r, float sunX) {
        boolean litLeft = sunX < x;
        int mid = tone(SkyModel.mix(PINE[season], 0x000000, r.nextFloat() * 0.2f), day, skyH, haze);
        int shade = SkyModel.scale(mid, 0.7), lit = SkyModel.mix(mid, 0xE8F4D8, 0.16 * (0.3 + 0.7 * day));
        p.setColor(argb(0x000000, 0.2 * (1 - Math.min(1, haze)))); // shadow on the ground, thrown away from the light
        float sx = litLeft ? hw * 0.5f : -hw * 0.5f;
        c.drawOval(x - hw * 0.9f + sx, base - hw * 0.1f, x + hw * 0.9f + sx, base + hw * 0.28f, p);
        p.setColor(argb(tone(0x3E3026, day, skyH, haze), 1));
        c.drawRect(x - hw * 0.09f, base - h * 0.2f, x + hw * 0.09f, base, p);
        int tiers = h > 40 ? 7 : 4;
        float lean = (r.nextFloat() - 0.5f) * hw * 0.5f;
        float y0 = base - h, th = h * (tiers > 4 ? 0.26f : 0.4f);
        float[] lx = new float[8], ly = new float[8], rx = new float[8], ry = new float[8];
        for (int k = 0; k < tiers; k++) {
            float t = k / (float) (tiers - 1);
            float ax = x + lean * (1 - t), top = y0 + (h * 0.8f - th) * t, bottom = top + th;
            float half = hw * (0.3f + 0.7f * (float) Math.pow(t, 0.85)) * (0.9f + r.nextFloat() * 0.2f);
            // ragged outline: left edge down, droop, right edge back up
            int n = 4;
            for (int j = 0; j < n; j++) {
                float u = (j + 1f) / n;
                float jag = (j % 2 == 0 ? 1 : -0.4f) * half * 0.14f * r.nextFloat();
                lx[j] = ax - half * u - jag;
                ly[j] = top + (bottom - top) * u * u + (j == n - 1 ? th * 0.1f : 0);
                rx[j] = ax + half * u + jag * (0.6f + r.nextFloat() * 0.8f);
                ry[j] = ly[j] + (r.nextFloat() - 0.5f) * th * 0.06f;
            }
            Path whole = new Path(), dark = new Path(), light = new Path();
            whole.moveTo(ax, top);
            for (int j = 0; j < n; j++) whole.lineTo(lx[j], ly[j]);
            whole.lineTo(ax, bottom - th * 0.04f);
            for (int j = n - 1; j >= 0; j--) whole.lineTo(rx[j], ry[j]);
            whole.close();
            p.setColor(argb(SkyModel.scale(mid, 1 - 0.04 * k), 1));
            c.drawPath(whole, p);
            // the half away from the light is shaded; the lit half gets a pale rim along its edge
            Path half2 = new Path();
            half2.moveTo(ax, top);
            if (litLeft) {
                for (int j = 0; j < n; j++) half2.lineTo(rx[j], ry[j]);
            } else {
                for (int j = 0; j < n; j++) half2.lineTo(lx[j], ly[j]);
            }
            half2.lineTo(ax, bottom - th * 0.04f);
            half2.close();
            p.setColor(argb(shade, 0.9));
            c.drawPath(half2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(0.8f, hw * 0.07f));
            p.setColor(argb(lit, 0.75));
            float[] ex = litLeft ? lx : rx, ey = litLeft ? ly : ry;
            light.moveTo(ax, top);
            for (int j = 0; j < n; j++) light.lineTo(ex[j], ey[j]);
            c.drawPath(light, p);
            p.setStyle(Paint.Style.FILL);
            if (season == 3) { // snow laid along the top of the tier, bluish on the shaded side
                float cut = top + (bottom - top) * 0.55f;
                Path sn = new Path();
                sn.moveTo(ax, top);
                sn.lineTo(lx[1] + (ax - lx[1]) * 0.1f, ly[1]);
                sn.lineTo(ax - half * 0.3f, cut);
                sn.lineTo(ax, cut + th * 0.14f);
                sn.lineTo(ax + half * 0.3f, cut - th * 0.02f);
                sn.lineTo(rx[1] - (rx[1] - ax) * 0.1f, ry[1]);
                sn.close();
                p.setColor(argb(tone(0xF6FAFE, day, skyH, haze), 0.97));
                c.drawPath(sn, p);
                p.setColor(argb(tone(0xBFD0E6, day, skyH, haze), 0.55));
                Path sh = new Path();
                sh.moveTo(ax, top);
                sh.lineTo(litLeft ? rx[1] : lx[1], litLeft ? ry[1] : ly[1]);
                sh.lineTo(litLeft ? ax + half * 0.3f : ax - half * 0.3f, cut);
                sh.lineTo(ax, cut + th * 0.14f);
                sh.close();
                c.drawPath(sh, p);
            }
        }
        // the tip: a slim leader
        p.setColor(argb(mid, 1));
        c.drawRect(x + lean - hw * 0.04f, y0 - h * 0.06f, x + lean + hw * 0.04f, y0 + h * 0.05f, p);
    }

    /** Low growth between the trees and the water: shrubs, grass tufts and, by the pond, a few rocks. */
    private void understory(Canvas c, float base, float sc, int row, int season, double day, int skyH, double haze,
                            float clearX, float clearHalf) {
        Random r = new Random(700 + row);
        int[][] shrub = {{0x5E9A3E, 0x7DB84F}, {0x2F6E34, 0x3E8E41}, {0xB8641C, 0xD9A02C}, {0xE0E8F2, 0xC9D6E8}};
        for (float x = 6; x < W; x += 20 + r.nextFloat() * 40) {
            float y = base + (6 + r.nextFloat() * 14) * sc;
            if (y > H) continue;
            float s1 = (5 + r.nextFloat() * 7) * sc;
            boolean nearPond = clearX >= 0 && Math.abs(x - clearX) < clearHalf * 1.05f;
            if (nearPond && r.nextFloat() < 0.6f) { // a rock on the bank
                p.setColor(argb(tone(0x7A7F86, day, skyH, haze), 1));
                c.drawOval(x - s1, y - s1 * 0.6f, x + s1, y + s1 * 0.3f, p);
                p.setColor(argb(tone(0xB4BAC2, day, skyH, haze), 0.8));
                c.drawOval(x - s1 * 0.6f, y - s1 * 0.55f, x + s1 * 0.1f, y - s1 * 0.1f, p);
                continue;
            }
            int[] pal = shrub[season];
            for (int k = 0; k < 3; k++) { // a shrub: overlapping humps, darker below and lighter on top
                p.setColor(argb(tone(k == 0 ? SkyModel.scale(pal[0], 0.8) : pal[k % 2], day, skyH, haze), 1));
                c.drawOval(x + (k - 1) * s1 * 0.7f - s1, y - s1 * (1.1f - 0.2f * k), x + (k - 1) * s1 * 0.7f + s1, y + s1 * 0.3f, p);
            }
            if (season == 0 && r.nextFloat() < 0.5f) { // spring: a few pale blossoms on it
                p.setColor(argb(tone(0xFFFFFF, day, skyH, haze), 0.9));
                for (int k = 0; k < 4; k++) c.drawCircle(x + (r.nextFloat() - 0.5f) * s1 * 1.6f, y - s1 * r.nextFloat(), 0.9f * sc, p);
            }
        }
        // tufts of grass (or reeds poking through the snow)
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(0.9f, 0.8f * sc));
        for (float x = 3; x < W; x += 9 + r.nextFloat() * 14) {
            float y = base + (4 + r.nextFloat() * 30) * sc;
            if (y > H || (clearX >= 0 && Math.abs(x - clearX) < clearHalf * 0.9f && row == 3)) continue;
            int col = tone(season == 3 ? 0x8A8A6A : SkyModel.mix(GROUND[season], 0xFFFFFF, 0.22f), day, skyH, haze);
            p.setColor(argb(col, 0.9));
            for (int k = -1; k <= 1; k++) {
                float len = (4 + r.nextFloat() * 5) * sc;
                c.drawLine(x, y, x + k * 2.5f * sc, y - len, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** A small pond: the sky in it, a darker bank, a few ripples and reeds; frozen white-blue in winter. */
    private void pond(Canvas c, Input in, int[] sky, double day, int season, float cx, float cy, float rx, float ry) {
        boolean ice = season == 3;
        int top = ice ? SkyModel.mix(0xDCE8F4, sky[1], 0.2) : SkyModel.mix(sky[1], 0xFFFFFF, 0.12);
        int bottom = ice ? SkyModel.mix(0xB4C8DE, sky[0], 0.2) : SkyModel.scale(sky[0], 0.85);
        int bank = tone(SkyModel.mix(GROUND[season], 0x000000, 0.45), day, sky[1], 0.1);
        RectF o = new RectF(cx - rx - 5, cy - ry - 3, cx + rx + 5, cy + ry + 4);
        p.setColor(argb(bank, 0.95)); // the bank
        c.drawOval(o, p);
        o.set(cx - rx, cy - ry, cx + rx, cy + ry);
        p.setColor(Color.BLACK);
        p.setShader(new LinearGradient(0, cy - ry, 0, cy + ry, new int[]{argb(top, 1), argb(bottom, 1)}, null, Shader.TileMode.CLAMP));
        c.drawOval(o, p);
        p.setShader(null);
        // the light on the water: a soft streak under the sun or moon when it is up
        double glint = in.sunElev > 3 ? 0.35 : in.moonElev > 3 ? 0.22 : 0;
        if (glint > 0 && !ice) {
            float gx = Math.max(cx - rx * 0.6f, Math.min(cx + rx * 0.6f, (float) (in.sunElev > 3 ? azToX(in.sunAz) : SkyModel.trackFraction(in.moonAz) * W)));
            p.setColor(argb(0xFFFFFF, glint * (0.3 + 0.7 * (1 - in.weather.cloud / 100.0))));
            c.drawOval(gx - 26, cy - ry * 0.35f, gx + 26, cy + ry * 0.35f, p);
        }
        // ripples (or cracks in the ice)
        Random r = new Random(77);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f);
        p.setColor(argb(ice ? 0xFFFFFF : 0xFFFFFF, ice ? 0.5 : 0.22));
        for (int i = 0; i < 7; i++) {
            float x = cx + (r.nextFloat() - 0.5f) * rx * 1.5f, y = cy + (r.nextFloat() - 0.5f) * ry * 1.2f, l = 10 + r.nextFloat() * 22;
            if (ice) c.drawLine(x, y, x + l * 0.6f, y + (r.nextFloat() - 0.5f) * 8, p);
            else c.drawLine(x - l / 2, y, x + l / 2, y, p);
        }
        // reeds along the near edge
        p.setColor(argb(tone(0x5A6A32, day, sky[1], 0.1), 0.95));
        for (int i = 0; i < 14; i++) {
            float x = cx - rx * 0.9f + r.nextFloat() * rx * 1.8f, y = cy + ry * (0.85f + r.nextFloat() * 0.25f);
            c.drawLine(x, y, x + (r.nextFloat() - 0.5f) * 3, y - 7 - r.nextFloat() * 8, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** Fade to a dark tint instead of black; the dock still sits on a dark enough ground for its labels. */
    private static LinearGradient groundFade(int tint) {
        return new LinearGradient(0, H * 0.6f, 0, H,
                new int[]{argb(tint, 0), argb(tint, 0.04), argb(tint, 0.16), argb(tint, 0.42), argb(tint, 0.8)},
                new float[]{0f, 0.25f, 0.55f, 0.82f, 1f}, Shader.TileMode.CLAMP);
    }

    static LinearGradient bottomFade() {
        return new LinearGradient(0, H * 0.6f, 0, H,
                new int[]{0x00000000, 0x33000000, 0x99000000, 0xDD000000, 0xFF000000},
                new float[]{0f, 0.25f, 0.55f, 0.82f, 1f}, Shader.TileMode.CLAMP);
    }

    // ------------------------------------------------------------------ pieces

    private void glow(Canvas c, float x, float y, float radius, float squash, int rgb, double alpha) {
        if (alpha <= 0.01) return;
        c.save();
        c.translate(x, y);
        c.scale(1f, squash);
        p.setColor(Color.BLACK);
        p.setShader(new RadialGradient(0, 0, radius,
                new int[]{argb(rgb, alpha), argb(rgb, alpha * 0.4), argb(rgb, 0)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, radius, p);
        p.setShader(null);
        c.restore();
    }

    private void drawStars(Canvas c, double amount, Random rnd) {
        p.setShader(null);
        for (int i = 0; i < 260; i++) {
            float x = rnd.nextFloat() * W;
            float y = rnd.nextFloat() * rnd.nextFloat() * HORIZON * 0.95f; // denser toward the zenith
            float r = 0.5f + rnd.nextFloat() * rnd.nextFloat() * 1.4f;
            double a = amount * (0.35 + 0.65 * rnd.nextFloat()) * (1 - 0.7 * (y / HORIZON));
            p.setColor(argb(rnd.nextInt(5) == 0 ? 0xFFE6C8 : 0xE8F0FF, a));
            c.drawCircle(x, y, r, p);
        }
    }

    private void drawSun(Canvas c, Input in, float x, float y, double dim, boolean hideDisc) {
        double low = 1 - SkyModel.smoothstep(2, 30, in.sunElev);
        int col = SkyModel.mix(SkyModel.mix(0xFFF4D6, 0xFFA85A, low), 0xFF8A40, SkyModel.lowness(in.sunElev, 8) * 0.6);
        glow(c, x, y, 150 * (1 + 0.35f * (float) low), 1f, col, 0.55 * dim);
        if (!hideDisc) rays(c, in, x, y, col, dim);
        if (hideDisc) return;
        glow(c, x, y, 60, 1f, 0xFFFFFF, 0.5 * dim);
        c.save();
        c.clipRect(0, 0, W, HORIZON + 3);
        p.setColor(argb(SkyModel.mix(0xFFFFFF, col, 0.35), Math.max(0.12, dim)));
        c.drawCircle(x, y, 17 * (1 + 0.3f * (float) low), p); // a low sun looks larger and redder
        c.restore();
    }

    /**
     * Faint crepuscular rays fanning down from the sun, on skies where the disc is visible. Strongest with a low sun
     * and a little cloud, gone with fog or thick cloud. Baked into the still picture with a radial fade, so no per-frame cost.
     */
    /** For the debug dump: how long the last still picture took (gradient, haze, rays, grain) and whether rays were drawn. */
    volatile long lastBaseMs = -1;
    volatile boolean lastRays;
    private boolean lastRaysDrawn;

    private void rays(Canvas c, Input in, float x, float y, int col, double dim) {
        lastRaysDrawn = false;
        double low = 1 - SkyModel.smoothstep(4, 35, in.sunElev);
        double amount = (0.04 + 0.07 * low) * dim * (1 - in.weather.fogAmount());
        if (amount < 0.01 || in.sunElev < 0) return;
        lastRaysDrawn = true;
        float reach = 520;
        c.save();
        c.clipRect(0, 0, W, HORIZON + 3);
        p.setColor(Color.BLACK);
        p.setShader(new RadialGradient(x, y, reach, new int[]{argb(col, amount), argb(col, 0)}, null, Shader.TileMode.CLAMP));
        Path ray = new Path();
        Random r = new Random(23);
        for (int i = 0; i < 9; i++) {
            double ang = Math.PI * (0.12 + 0.76 * i / 8.0) + (r.nextDouble() - 0.5) * 0.12; // downward fan
            double half = 0.035 + r.nextDouble() * 0.04;
            ray.rewind();
            ray.moveTo(x, y);
            ray.lineTo(x + (float) Math.cos(ang - half) * reach * 1.4f, y + (float) Math.sin(ang - half) * reach * 1.4f);
            ray.lineTo(x + (float) Math.cos(ang + half) * reach * 1.4f, y + (float) Math.sin(ang + half) * reach * 1.4f);
            ray.close();
            c.drawPath(ray, p);
        }
        p.setShader(null);
        c.restore();
    }

    private void drawMoon(Canvas c, Input in, double vis) {
        float x = (float) (SkyModel.trackFraction(in.moonAz) * W);
        float y = (float) (HORIZON - Math.min(in.moonElev, 70) / 70.0 * HORIZON * 0.88); // below 0 it sinks behind the horizon
        double low = SkyModel.lowness(in.moonElev, 16);
        float r = 19 * (1 + 0.25f * (float) low);
        int glowCol = SkyModel.mix(0xB4C8F0, 0xFFB880, low * 0.7);
        glow(c, x, y, 90, 1f, glowCol, 0.28 * vis);
        c.save();
        c.clipRect(0, 0, W, HORIZON + 2);
        double ph = in.moonPhase, ang = ph * 2 * Math.PI;
        boolean waxing = ph < 0.5;
        c.save();
        c.translate(x, y);
        if (!waxing) c.scale(-1, 1);
        RectF disc = new RectF(-r, -r, r, r);
        p.setColor(argb(0x2A3350, 0.9 * vis)); // earthshine
        c.drawCircle(0, 0, r, p);
        float k = (float) Math.abs(Math.cos(ang));
        RectF term = new RectF(-r * k, -r, r * k, r);
        Path lit = new Path();
        lit.arcTo(disc, -90, 180); // right half
        boolean crescent = (ph < 0.25) || (ph > 0.75);
        if (crescent) lit.arcTo(term, 90, -180); else lit.arcTo(term, 90, 180);
        lit.close();
        p.setColor(argb(SkyModel.mix(0xF2EEDC, 0xFFC890, low * 0.7), vis));
        c.drawPath(lit, p);
        c.restore();
        c.restore();
    }

    /** Warm at sunrise/sunset, pale and cool otherwise: the bright strip under the cloud deck. */
    private void drawHorizonGap(Canvas c, Input in, double day, boolean wet) {
        double warm = 1 - SkyModel.smoothstep(0, 9, Math.abs(in.sunElev + 1));
        int base = SkyModel.scale(0xC8D4E0, 0.12 + 0.88 * day);
        int lit = SkyModel.mix(base, in.morning() ? 0xFFB88A : 0xFFA060, warm);
        // The lit gap is a real feature, but a pale flat band just above the dock reads as a blank stripe (the rain
        // streaks are nearly its colour). Keep it faint and dim it, more in a storm, so rain stays visible across it.
        double a = wet ? (in.weather.storm ? 0.22 : 0.34) : 0.5;
        if (wet) lit = SkyModel.scale(lit, in.weather.storm ? 0.55 : 0.7);
        float top = HORIZON - 90; // a longer, softer ramp: no visible start
        p.setColor(Color.BLACK);
        p.setShader(new LinearGradient(0, top, 0, HORIZON + 26,
                new int[]{argb(lit, 0), argb(lit, a), argb(lit, a * 0.7)},
                new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, top, W, HORIZON + 26, p);
        p.setShader(null);
    }

    static int cloudTint(Input in, boolean dark) {
        double day = SkyModel.daylight(in.sunElev);
        int base = SkyModel.mix(0x222A40, dark ? 0x9AA4B0 : 0xF6F8FC, day);
        double warm = 1 - SkyModel.smoothstep(0, 11, Math.abs(in.sunElev + 1));
        return SkyModel.mix(base, 0xFFB890, 0.55 * warm * (dark ? 0.5 : 1));
    }

    /** Wide, flat, layered cloud bands that leave the horizon strip open. */
    private void drawFlatClouds(Canvas c, Input in, Random rnd, int layers, boolean wet) {
        int tint = cloudTint(in, wet);
        float bottom = HORIZON - 52;
        for (int l = 0; l < layers; l++) {
            float t = (float) l / Math.max(1, layers - 1);        // 0 = top layer
            float cy = 10 + t * (bottom - 40);
            double layerDark = wet ? 0.55 + 0.45 * t : 0.75 + 0.25 * t;
            int col = SkyModel.scale(tint, layerDark);
            int blobs = 9;
            for (int i = 0; i < blobs; i++) {
                float bx = -80 + (W + 160) * (i + rnd.nextFloat() * 0.8f) / blobs;
                float bw = 220 + rnd.nextFloat() * 260;
                float bh = 26 + t * 26 + rnd.nextFloat() * 22;
                float by = cy + (rnd.nextFloat() - 0.5f) * 30;
                if (by + bh > bottom) by = bottom - bh;
                oval(c, bx, by, bw, bh, col, wet ? 0.5 : 0.32);
            }
        }
    }

    private void drawPuffs(Canvas c, Input in, Random rnd, int count) {
        int tint = cloudTint(in, false);
        for (int i = 0; i < count; i++) {
            float cx = rnd.nextFloat() * W;
            float cy = HORIZON * (0.14f + rnd.nextFloat() * 0.5f);
            float scale = 0.7f + rnd.nextFloat() * 0.9f;
            for (int j = 0; j < 5; j++) {
                float ox = (j - 2) * 34 * scale + (rnd.nextFloat() - 0.5f) * 20;
                float oy = (rnd.nextFloat() - 0.5f) * 10 * scale - (j == 2 ? 8 : 0);
                oval(c, cx + ox, cy + oy, 110 * scale, 38 * scale, tint, 0.34);
            }
        }
    }

    private void oval(Canvas c, float cx, float cy, float w, float h, int rgb, double alpha) {
        c.save();
        c.translate(cx, cy);
        c.scale(1f, h / w);
        p.setColor(Color.BLACK);
        p.setShader(new RadialGradient(0, 0, w / 2,
                new int[]{argb(rgb, alpha), argb(rgb, alpha * 0.55), argb(rgb, 0)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, w / 2, p);
        p.setShader(null);
        c.restore();
    }
}
