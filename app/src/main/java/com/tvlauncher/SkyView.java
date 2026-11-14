package com.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.List;
import java.util.Random;

/**
 * The living sky: drifting clouds, mist and haze, twinkling stars and shooting stars, and the rain, snow and
 * lightning on top. A SurfaceView sits above the wallpaper ImageViews and behind all content, drawing on its own
 * thread through a software canvas at 960x540 (the compositor scales it up) at ~12 fps (~25 fps in precipitation, where rain needs the speed).
 *
 * A SurfaceView punches a hole in the window, so it hides the ImageViews and has to draw the wallpaper itself,
 * including the 3 s crossfade between skies. It is only visible while the sky is animated or it is raining or
 * snowing, and the thread stops when the surface is destroyed (the launcher left the screen).
 * lockHardwareCanvas is deliberately not used: it crashed with EGL_BAD_ALLOC on Fire TV.
 */
final class SkyView extends SurfaceView implements SurfaceHolder.Callback {
    enum Mode { NONE, RAIN, SNOW }

    /** One sky ready to draw: the still base, plus the moving layers (null = a complete still picture). */
    static final class Frame {
        final Bitmap base;
        final SkyLayers.Set layers;

        Frame(Bitmap base, SkyLayers.Set layers) {
            this.base = base;
            this.layers = layers;
        }
    }

    private static final int W = SkyRenderer.W, H = SkyRenderer.H;
    private static final long AMBIENT_FRAME_MS = 83, PRECIP_FRAME_MS = 40;
    private static final long FADE_MS = 3000;
    private static final String TAG = "SkyView";

    volatile Mode mode = Mode.NONE;
    private volatile boolean ambient;
    private volatile float intensity = 0.5f, light = 1f;
    private volatile boolean storm;
    private volatile Frame cur, next;
    private volatile long fadeStart;

    private Thread thread;
    private volatile boolean running;
    private final Object gate = new Object();
    private volatile boolean suspended;
    /** For the debug dump: frame time and mode from the latest 5 s window. */
    volatile String lastStats = "";

    SkyView(Context c) {
        super(c);
        SurfaceHolder h = getHolder();
        h.addCallback(this);
        h.setFixedSize(W, H);
        // 32-bit colour: the default 16-bit surface turns the smooth sky gradients into visible horizontal stripes
        h.setFormat(PixelFormat.RGBX_8888);
        setVisibility(GONE);
    }

    /**
     * @param m         what falls (NONE: nothing)
     * @param intensity 0..1
     * @param light     0 (night) .. 1 (day): how bright the streaks and flakes are
     */
    void configure(Mode m, double intensity, boolean storm, double light) {
        this.intensity = (float) Precip.clamp01(intensity);
        this.light = (float) Precip.clamp01(light);
        this.storm = storm && m == Mode.RAIN;
        this.mode = m;
        updateVisibility();
    }

    /** Whether clouds, mist and stars move. Off = the view only appears for rain and snow. */
    void setAmbient(boolean on) {
        ambient = on;
        updateVisibility();
    }

    /** The sky to draw; {@code fade} crossfades from the previous one over 3 s. */
    void setFrame(Frame f, boolean fade) {
        f.base.setHasAlpha(false); // the sky is opaque: lets the canvas copy it instead of blending it
        if (cur == null || !fade) {
            cur = f;
            next = null;
        } else {
            if (next != null) cur = next; // a fade was running: start from where it got to
            fadeStart = SystemClock.uptimeMillis();
            next = f;
        }
        updateVisibility();
    }

    /**
     * While another app is in front the sky is not seen, so the drawing thread sleeps (no polling: it is woken by
     * notify) instead of drawing 12 frames a second behind it.
     */
    void setSuspended(boolean s) {
        synchronized (gate) {
            suspended = s;
            gate.notifyAll();
        }
    }

    /** Drops the moving layers (several MB); the still base stays so the screen is never blank. */
    void releaseLayers() {
        Frame n = next, a = cur;
        if (n != null) a = n; // a crossfade was running: keep where it was heading
        if (a != null && a.layers != null) {
            cur = new Frame(a.base, null);
            next = null;
        }
    }

    private void updateVisibility() {
        boolean show = (ambient || mode != Mode.NONE) && cur != null;
        setVisibility(show ? VISIBLE : GONE);
    }

    final PrecipInteraction fx = new PrecipInteraction(this);

    /** The cards and dock on screen, as rounded rectangles in sky coordinates. */
    void setObstacles(float[] o) {
        fx.setObstacles(o);
    }

    private volatile boolean forceStrike;
    private volatile long holdAtMs = -1;

    /**
     * Debug aid (`am start ... --es strike hold`): fire a strike now and freeze it at the peak of the second
     * flash so it can be screenshotted; `--es strike off` releases it.
     */
    void debugStrike(boolean hold) {
        holdAtMs = hold ? 180 : -1;
        forceStrike = hold;
    }

    // ------------------------------------------------------------------ surface lifecycle

    @Override public void surfaceCreated(SurfaceHolder holder) { startThread(); }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) { stopThread(); }

    private synchronized void startThread() {
        if (thread != null) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "sky-layer");
        thread.start();
    }

    private synchronized void stopThread() {
        running = false;
        synchronized (gate) {
            gate.notifyAll(); // wake it if it is sleeping because the launcher is paused
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(800); // make sure nothing locks the canvas after the surface is gone
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ drawing thread

    final Random rnd = new Random();
    private final float[] px = new float[Precip.MAX_PARTICLES], py = new float[Precip.MAX_PARTICLES];
    private final float[] pv = new float[Precip.MAX_PARTICLES], pp = new float[Precip.MAX_PARTICLES];
    private final float[][] lines = new float[3][Precip.MAX_PARTICLES * 4];
    private final float[][] points = new float[3][Precip.MAX_PARTICLES * 2];
    private volatile boolean painted;

    /** Van Gogh style: rain as long thin strokes, snow as fat dabs, stars with halos. */
    void setPainted(boolean on) { painted = on; }

    private final Paint bg = new Paint();
    // no anti-aliasing for the thin streaks and flakes: invisible at this size once scaled up, and much cheaper
    private final Paint streak = new Paint();
    private final Paint flake = new Paint();
    private final Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strip = new Paint();
    private final Paint star = new Paint();
    private final Paint shoot = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long tDraw, tBase, tStars, tStrips, tFade;
    private Mode seeded = Mode.NONE;
    private int seededCount;
    private long nextStrike, strikeStart = -1;
    private List<Precip.Segment> boltShape;
    float wind = 0.16f;
    // shooting star
    private long shootStart = -1, nextShoot;
    private float shootX, shootY;

    private void loop() {
        SurfaceHolder holder = getHolder();
        long last = SystemClock.uptimeMillis();
        long statStart = last;
        int frames = 0;
        streak.setStrokeCap(Paint.Cap.ROUND);
        flake.setStrokeCap(Paint.Cap.ROUND);
        star.setStrokeCap(Paint.Cap.ROUND);
        shoot.setStrokeCap(Paint.Cap.ROUND);
        bolt.setStrokeCap(Paint.Cap.ROUND);
        bolt.setStyle(Paint.Style.STROKE);
        fx.initPaints();
        flake.setAntiAlias(true); // round flakes: square dots made the snow look pixelated
        while (running) {
            synchronized (gate) {
                while (suspended && running) {
                    try {
                        gate.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!running) return;
            }
            long t0 = SystemClock.uptimeMillis();
            float dt = Math.min(0.1f, (t0 - last) / 1000f);
            last = t0;
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) frame(c, dt, t0);
            } catch (RuntimeException e) {
                Log.w(TAG, "frame failed", e);
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            long spent = SystemClock.uptimeMillis() - t0;
            tDraw += spent;
            frames++;
            if (t0 - statStart >= 5000) {
                lastStats = String.format(java.util.Locale.ENGLISH, "%.1f ms/frame mode=%s ambient=%s", tDraw / (double) frames, mode, ambient);
                Log.i(TAG, String.format(java.util.Locale.ENGLISH,
                        "%d frames in 5 s, %.1f ms/frame (base %.1f, stars %.1f, strips %.1f, fade %.1f) mode=%s ambient=%s landed: dock %d cards %d drips %d",
                        frames, tDraw / (double) frames, tBase / 1e6 / frames, tStars / 1e6 / frames,
                        tStrips / 1e6 / frames, tFade / 1e6 / frames, mode, ambient, fx.hitsDock, fx.hitsCards, fx.hitsDrip));
                fx.resetHits();
                statStart = t0;
                tDraw = tBase = tStars = tStrips = tFade = 0;
                frames = 0;
            }
            long budget = mode != Mode.NONE ? PRECIP_FRAME_MS : AMBIENT_FRAME_MS;
            if (spent < budget) {
                try {
                    Thread.sleep(budget - spent);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void frame(Canvas c, float dt, long now) {
        double t = now / 1000.0;
        drawSky(c, now, t);

        Mode m = mode;
        if (m != Mode.SNOW && m != Mode.RAIN) fx.clearPrecipState();
        else if (m == Mode.RAIN && fx.pilesDirty) fx.clearPrecipState();
        if (m != Mode.NONE) {
            float inten = intensity, lit = light;
            int n = m == Mode.RAIN ? Precip.rainCount(inten) : Precip.snowCount(inten);
            if (m != seeded || n != seededCount) seed(m, n);
            drawLightning(c, now);
            if (m == Mode.RAIN) drawRain(c, dt, n, lit, inten);
            else drawSnow(c, dt, n, lit);
        } else {
            nextStrike = 0;
            strikeStart = -1;
        }
        // water on the cards and dock: fed by the rain, and it keeps running and dripping for a while after the rain stops
        if (m == Mode.RAIN) fx.waterPass(c, fx.shape, dt, storm ? 6.5f : 5f);
        else if (fx.waterDirty) fx.waterPass(c, fx.shape, dt, 0f);
    }

    /** The base picture and its moving layers, crossfading to the next sky when one is arriving. */
    private void drawSky(Canvas c, long now, double t) {
        Frame a = cur, b = next;
        if (a == null) {
            c.drawColor(Color.BLACK);
            return;
        }
        drawOne(c, a, 1f, t, now);
        if (b != null) {
            float k = (now - fadeStart) / (float) FADE_MS;
            if (k >= 1f) {
                cur = b;
                next = null;
                drawOne(c, b, 1f, t, now);
            } else {
                drawOne(c, b, k, t, now);
            }
        }
    }

    private void drawOne(Canvas c, Frame f, float alpha, double t, long now) {
        long t0 = System.nanoTime();
        if (alpha >= 1f) {
            c.drawBitmap(f.base, 0, 0, null);
        } else {
            bg.setAlpha((int) (255 * alpha));
            c.drawBitmap(f.base, 0, 0, bg);
        }
        long t1 = System.nanoTime();
        tBase += t1 - t0;
        SkyLayers.Set s = f.layers;
        if (s == null) return;
        drawStars(c, s, alpha, t);
        if (s.shooting && alpha >= 1f) drawShootingStar(c, now);
        long t2 = System.nanoTime();
        tStars += t2 - t1;
        for (SkyLayers.Strip st : s.clouds) drawStrip(c, st, alpha, t);
        for (SkyLayers.Strip st : s.mists) drawStrip(c, st, alpha, t);
        tStrips += System.nanoTime() - t2;
    }

    private void drawStrip(Canvas c, SkyLayers.Strip st, float alpha, double t) {
        // colour, strength and the bottom fade are baked into the pixels; only a crossfade needs an alpha
        Paint paint = null;
        if (alpha < 1f) {
            strip.setAlpha((int) (255 * alpha));
            paint = strip;
        }
        float off = SkyPlan.offset(t, st.speed, SkyLayers.STRIP_W);
        // two copies wrap the strip: [off - STRIP_W, off] and [off, off + STRIP_W]; the screen is half that wide
        c.drawBitmap(st.pixels, off - SkyLayers.STRIP_W, st.y, paint);
        if (off < W) c.drawBitmap(st.pixels, off, st.y, paint);
    }

    private void drawStars(Canvas c, SkyLayers.Set s, float alpha, double t) {
        if (s.stars.isEmpty() || s.starAmount <= 0.02) return;
        for (SkyLayers.StarBatch sb : s.stars) {
            float a = (float) (s.starAmount * 0.9 * SkyPlan.twinkle(t, sb.phase, sb.rate) * alpha);
            if (painted) {
                star.setAntiAlias(true);
                star.setStrokeCap(Paint.Cap.ROUND);
                star.setColor(Color.argb((int) (90 * Math.min(1f, a)), 250, 230, 140)); // soft ringed halo, then the core
                star.setStrokeWidth(sb.width * 3.6f);
                c.drawPoints(sb.points, 0, sb.points.length, star);
            }
            star.setColor(Color.argb((int) (255 * Math.min(1f, a)), 232, 240, 255));
            star.setStrokeWidth(sb.width);
            c.drawPoints(sb.points, 0, sb.points.length, star);
        }
    }

    /** Now and then a streak crosses the upper sky, on a really clear night. */
    private void drawShootingStar(Canvas c, long now) {
        if (nextShoot == 0) nextShoot = now + 9000 + rnd.nextInt(12000);
        if (shootStart < 0) {
            if (now < nextShoot) return;
            shootStart = now;
            shootX = 120 + rnd.nextFloat() * (W - 360);
            shootY = 20 + rnd.nextFloat() * 120;
        }
        float p = (now - shootStart) / 900f;
        if (p >= 1f) {
            shootStart = -1;
            nextShoot = now + 18000 + rnd.nextInt(22000);
            return;
        }
        float hx = shootX + 230 * p, hy = shootY + 110 * p;
        for (int i = 0; i < 6; i++) { // a short tail that fades out behind the head
            float k0 = i / 6f, k1 = (i + 1) / 6f;
            float x0 = hx - 70 * k0, y0 = hy - 34 * k0, x1 = hx - 70 * k1, y1 = hy - 34 * k1;
            float fadeOut = (1 - k0) * (float) Math.sin(Math.PI * p); // brightest mid-flight
            shoot.setColor(Color.argb((int) (230 * fadeOut), 245, 248, 255));
            shoot.setStrokeWidth(2.2f * (1 - k0 * 0.6f));
            c.drawLine(x0, y0, x1, y1, shoot);
        }
    }

    // ------------------------------------------------------------------ rain, snow, lightning

    private void drawLightning(Canvas c, long now) {
        double flash = 0, boltA = 0;
        if (storm) {
            if (nextStrike == 0) nextStrike = now + 1800 + rnd.nextInt(2500); // the first one arrives soon
            if (forceStrike) {
                forceStrike = false;
                strikeStart = -1;
                nextStrike = now;
            }
            if (strikeStart < 0 && now >= nextStrike) {
                strikeStart = now;
                boltShape = Precip.bolt(rnd, W, (int) (SkyRenderer.HORIZON * (0.78f + 0.2f * rnd.nextFloat())));
                nextStrike = now + Precip.nextStrikeDelayMs(rnd);
                Log.i(TAG, "lightning strike, next in " + (nextStrike - now) + " ms, " + boltShape.size() + " bolt segments");
            }
            if (strikeStart >= 0) {
                long held = holdAtMs;
                long e = held >= 0 ? held : now - strikeStart;
                flash = Precip.flashAlpha(e);
                boltA = Precip.boltAlpha(e);
                if (e > 700) strikeStart = -1;
            }
        } else {
            nextStrike = 0;
            strikeStart = -1;
        }
        if (flash > 0.01) c.drawColor(Color.argb((int) (flash * 200), 225, 232, 255));
        if (boltA > 0.01 && boltShape != null) drawBolt(c, boltA);
    }

    private void seed(Mode m, int n) {
        seeded = m;
        seededCount = n;
        for (int i = 0; i < Precip.MAX_PARTICLES; i++) {
            px[i] = rnd.nextFloat() * (W + 120) - 60;
            py[i] = rnd.nextFloat() * H;
            int layer = i % 3;
            if (m == Mode.RAIN) pv[i] = 430 + layer * 190 + rnd.nextFloat() * 120;
            else pv[i] = 28 + layer * 22 + rnd.nextFloat() * 24;
            pp[i] = rnd.nextFloat() * 6.28f;
        }
    }

    private final int[] count = new int[3];

    private void drawRain(Canvas c, float dt, int n, float lit, float inten) {
        java.util.Arrays.fill(count, 0);
        float slant = wind + (storm ? 0.12f : 0);
        float[] o = fx.shape.o;
        for (int i = 0; i < n; i++) {
            int layer = i % 3;
            float y0 = py[i];
            py[i] += pv[i] * dt;
            px[i] += pv[i] * slant * dt;
            int hit = o.length > 0 ? Obstacles.landing(o, px[i], y0, py[i], fx.landY) : -1;
            if (hit >= 0) {
                fx.splash(px[i], fx.landY[0]); // it hit a card or the dock: splash, leave some water, then start again at the top
                fx.addWater(fx.shape, hit, px[i], 0.22f);
                fx.countHit(fx.shape, hit);
                py[i] = -rnd.nextFloat() * 80;
                px[i] = fx.respawnX(storm ? fx.shape.openStorm : fx.shape.openRain, 0.2f, rnd.nextFloat() * (W + 160) - 80);
            } else if (py[i] > H + 24) {
                py[i] = -rnd.nextFloat() * 80;
                px[i] = rnd.nextFloat() * (W + 160) - 80;
            }
            float len = (7 + layer * 6 + inten * 4) * (painted ? 1.7f : 1f);
            float[] l = lines[layer];
            int k = count[layer]++ * 4;
            l[k] = px[i];
            l[k + 1] = py[i];
            l[k + 2] = px[i] - len * slant;
            l[k + 3] = py[i] - len;
        }
        float bright = 0.45f + 0.55f * lit; // dim at night
        for (int layer = 0; layer < 3; layer++) {
            streak.setStrokeWidth((0.9f + layer * 0.5f) * (painted ? 1.5f : 1f));
            if (painted) streak.setColor(Color.argb((int) ((85 + layer * 45) * bright), 222, 222, 178)); // grey-yellow rain strokes
            else streak.setColor(Color.argb((int) ((70 + layer * 40) * bright), 205, 220, 240));
            c.drawLines(lines[layer], 0, count[layer] * 4, streak);
        }
        fx.drawSplashes(c, dt, lit);
    }

    private void drawSnow(Canvas c, float dt, int n, float lit) {
        java.util.Arrays.fill(count, 0);
        long t = SystemClock.uptimeMillis();
        float time = t / 1000f;
        PrecipInteraction.Shape sh = fx.shape;
        float[] o = sh.o;
        for (int i = 0; i < n; i++) {
            int layer = i % 3;
            float y0 = py[i];
            py[i] += pv[i] * dt;
            px[i] += (float) Math.sin(time * (0.6f + layer * 0.3f) + pp[i]) * (10 + layer * 8) * dt + 6 * dt;
            int hit = o.length > 0 ? Obstacles.landing(o, px[i], y0, py[i], fx.landY) : -1;
            if (hit >= 0) { // it landed on a card or the dock: it joins the drift there
                fx.addSnow(sh, hit, px[i]);
                fx.countHit(sh, hit);
                py[i] = -rnd.nextFloat() * 40;
                px[i] = fx.respawnX(sh.openSnow, 0.4f, rnd.nextFloat() * (W + 80) - 40);
            } else if (py[i] > H + 8) {
                py[i] = -rnd.nextFloat() * 40;
                px[i] = rnd.nextFloat() * (W + 80) - 40;
            }
            if (px[i] > W + 20) px[i] -= W + 40;
            float[] p = points[layer];
            int k = count[layer]++ * 2;
            p[k] = px[i];
            p[k + 1] = py[i];
        }
        for (int layer = 0; layer < 3; layer++) {
            flake.setStrokeWidth((1.8f + layer * 1.3f) * (painted ? 1.5f : 1f));
            flake.setColor(painted ? Color.argb(255, 255, 250, 226) : Color.argb(255, 255, 255, 255)); // bright, solid by day and by night
            c.drawPoints(points[layer], 0, count[layer] * 2, flake);
        }
        fx.drawPiles(c, sh, dt);
    }

    private void drawBolt(Canvas c, double alpha) {
        // soft glow, then the bright core; forks are thinner
        for (int pass = 0; pass < 2; pass++) {
            for (Precip.Segment s : boltShape) {
                boolean fork = s.kind == 1;
                if (pass == 0) {
                    bolt.setStrokeWidth(fork ? 4.5f : 8f);
                    bolt.setColor(Color.argb((int) (70 * alpha), 160, 190, 255));
                } else {
                    bolt.setStrokeWidth(fork ? 1.2f : 2.4f);
                    bolt.setColor(Color.argb((int) (255 * alpha), 250, 252, 255));
                }
                c.drawLine(s.x1, s.y1, s.x2, s.y2, bolt);
            }
        }
    }
}
